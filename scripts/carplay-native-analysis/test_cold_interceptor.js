'use strict';

const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { createColdOemInterceptor } = require('./cold_oem_interceptor');

const directory = process.argv[2];
const profile = JSON.parse(fs.readFileSync(path.join(directory, 'oem-cold-profile.json'), 'utf8'));
const checksum = value => crypto.createHash('sha256').update(typeof value === 'string' ? value : Buffer.from(value)).digest('hex');
const arrayBuffer = bytes => Uint8Array.from(bytes).buffer;

function runtime(fault = {}) {
    const images = new Map(profile.modules.map(module => [module.path,
        fs.readFileSync(path.join(directory, 'vendor__app__TsCarPlayService__lib__arm64__' + module.name))]));
    if (fault.file) images.get(profile.modules[0].path)[images.get(profile.modules[0].path).length - 1] ^= 1;
    images.set('/proc/self/cmdline', Buffer.from((fault.process ? 'wrong.process' : 'com.ts.carplay') + '\0'));
    const bridgePath = '/data/local/tmp/impulse-carplay/test/libcarplay_video_bridge.so';
    const bridgeBytes = Buffer.alloc(4096, 0);
    bridgeBytes.writeUInt32LE(0x464c457f, 0); bridgeBytes[4] = 2; bridgeBytes[5] = 1;
    bridgeBytes.writeUInt16LE(3, 16); bridgeBytes.writeUInt16LE(183, 18);
    bridgeBytes.writeBigUInt64LE(64n, 32); bridgeBytes.writeUInt16LE(56, 54); bridgeBytes.writeUInt16LE(1, 56);
    bridgeBytes.writeUInt32LE(1, 64); bridgeBytes.writeUInt32LE(5, 68); bridgeBytes.writeBigUInt64LE(4096n, 96);
    images.set(bridgePath, bridgeBytes);
    const modules = [];
    const regions = [];
    const patched = new Map();
    const operations = [];
    let callback = null;
    let timer = null;
    let pins = 0;
    let opened = 0;
    let closed = 0;
    let next = 0x1000000n;
    let nativeReady = 0;
    let readyThrows = false;

    class Pointer {
        constructor(value, region, label = null) { this.value = BigInt(value); this.region = region; this.label = label; }
        add(offset) { return new Pointer(this.value + BigInt(offset), this.region); }
        compare(other) { return this.value < other.value ? -1 : this.value > other.value ? 1 : 0; }
        equals(other) { return this.value === other.value; }
        isNull() { return this.value === 0n; }
        readByteArray(size) {
            const offset = Number(this.value - this.region.base.value);
            assert.ok(offset >= 0 && offset + size <= this.region.memory.length);
            return arrayBuffer(this.region.memory.subarray(offset, offset + size));
        }
        writePointer(value) { this.region.slots.set(Number(this.value - this.region.base.value), value); }
    }
    function region(memory, file = null) {
        const result = { memory: Buffer.from(memory), size: memory.length, slots: new Map(), protection: 'r-x', file };
        result.base = new Pointer(next, result); next += 0x1000000n; regions.push(result); return result;
    }
    function loaded(expected) {
        const object = region(images.get(expected.path), { path: expected.path });
        object.name = expected.name; object.path = expected.path; object.profile = expected;
        modules.push(object); return object;
    }
    function loadOem(index) {
        const object = loaded(profile.modules[index]);
        if (fault.memory && index === 1) object.memory[0x217f8 + 40] ^= 1;
        if (fault.writable && index === 1) object.protection = 'rwx';
        if (fault.path && index === 1) object.path = '/wrong/' + object.name;
        if (callback) callback.onAdded(object);
        return object;
    }
    class File {
        constructor(filePath) { assert.ok(images.has(filePath)); this.bytes = images.get(filePath); this.position = 0; opened++; }
        seek(offset, mode) { this.position = mode === File.SEEK_END ? this.bytes.length + offset : offset; }
        tell() { return this.position; }
        readBytes(size) { return arrayBuffer(this.bytes.subarray(this.position, this.position + size)); }
        close() { closed++; }
    }
    File.SEEK_SET = 0; File.SEEK_END = 2;
    const api = {
        Process: {
            arch: 'arm64', pointerSize: 8, id: 1234,
            enumerateModules: () => modules.slice(),
            findRangeByAddress(address) { return regions.find(item => address.compare(item.base) >= 0 && address.compare(item.base.add(item.size)) < 0) || null; },
            attachModuleObserver(observer) {
                callback = observer;
                if (fault.registrationRace) loadOem(0);
                for (const object of modules.slice()) observer.onAdded(object);
                return { detach() { callback = null; } };
            }
        },
        Module: { load(filePath) {
            const object = loaded({ name: 'libcarplay_video_bridge.so', path: filePath });
            if (fault.bridgeMemory) object.memory[0x500] ^= 1;
            const exports = new Map();
            object.getExportByName = name => {
                if (!exports.has(name)) exports.set(name, new Pointer(object.base.value + 0x100n + BigInt(exports.size * 16), object, name));
                return exports.get(name);
            };
            return object;
        } },
        File,
        Memory: {
            alloc(size) { return region(Buffer.alloc(size)).base; },
            allocUtf8String(value) { return region(Buffer.from(value + '\0')).base; }
        },
        Checksum: { compute(algorithm, value) { assert.equal(algorithm, 'sha256'); return checksum(value); } },
        NativeFunction: function(address) {
            if (address.label.endsWith('_abi')) return () => fault.abi ? 2 : 1;
            if (address.label.endsWith('_state')) return () => { if (readyThrows) throw new Error('state unavailable'); return nativeReady; };
            if (address.label === 'impulse_carplay_prepare_initial_entries') return () => {
                operations.push('pin-modules'); return fault.prepare ? -1 : 0;
            };
            if (address.label === 'impulse_carplay_release_initial_preparation') return () => {
                operations.push('release-modules'); return fault.release ? -1 : 0;
            };
            assert.equal(address.label, 'impulse_carplay_bootstrap_initial_entries');
            return (table, session, displays, length, copy, retain, release, uuid, uuidLength) => {
                operations.push('bootstrap'); assert.equal(table.region.slots.size, 7); assert.equal(patched.size, 9);
                const originals = [...table.region.slots.values(), session, displays];
                assert.equal(new Set(originals.map(pointer => pointer.value.toString())).size, 9);
                const core = modules.find(module => module.name === 'libCoreUtils_ts.so');
                for (const [label, pointer] of Object.entries({ getLength: length, copyString: copy, retain, release })) {
                    assert.ok(pointer.equals(core.base.add(core.profile.functions[label].offset)));
                }
                assert.equal(uuidLength, 4);
                assert.equal(Buffer.from(uuid.readByteArray(uuidLength)).toString(), 'main');
                if (fault.bootstrapPinned) { nativeReady = 1; throw new Error('ambiguous pinned bootstrap'); }
                if (fault.bootstrapUnknown) { readyThrows = true; throw new Error('unknown bootstrap'); }
                if (fault.bootstrap) return -1;
                nativeReady = 1; return 0;
            };
        },
        Interceptor: {
            replaceFast(target) {
                operations.push('replace');
                const offset = Number(target.value - target.region.base.value);
                patched.set(target.value, { target, offset, byte: target.region.memory[offset] });
                target.region.memory[offset] ^= 0xff;
                if (fault.replaceAt === patched.size) throw new Error('failure after replacement');
                return fault.trampoline ? target : region(Buffer.alloc(64)).base;
            },
            revert(target) {
                operations.push('revert');
                if (fault.revert) throw new Error('restore failed');
                const patch = patched.get(target.value);
                if (patch) { target.region.memory[patch.offset] = patch.byte; patched.delete(target.value); }
            },
            flush() { operations.push('flush'); }
        },
        Script: { pin() { pins++; }, unpin() { pins--; assert.ok(pins >= 0); } },
        setTimeout(fn) { timer = fn; return 1; }, clearTimeout() { timer = null; }
    };
    return { api, loadOem, modules, patched, operations,
        options: { enabled: true, bridgePath, bridgeSize: bridgeBytes.length, bridgeSha256: checksum(bridgeBytes), principalUuid: 'main', expectedPid: 1234 },
        metrics: () => ({ pins, opened, closed }),
        timeout() { if (timer) timer(); },
        unload(object) { if (callback) callback.onRemoved(object); }
    };
}

let passed = 0;
function test(name, action) { action(); passed++; process.stdout.write(name + ': passed\n'); }
function complete(env) { [0, 1, 2].forEach(index => env.loadOem(index)); }

test('default disabled is inert', () => {
    const env = runtime(); const installer = createColdOemInterceptor(env.api);
    assert.equal(installer.arm(null, {}).phase, 'disabled'); assert.equal(env.metrics().opened, 0);
});
test('full loaded module attestation', () => {
    const env = runtime(); complete(env); const installer = createColdOemInterceptor(env.api);
    assert.equal(installer.inspect(profile).verified, 13); assert.equal(env.patched.size, 0);
    assert.equal(env.metrics().opened, env.metrics().closed);
});
test('warm install refused', () => {
    const env = runtime(); complete(env); const installer = createColdOemInterceptor(env.api);
    assert.throws(() => installer.arm(profile, env.options), /warm interception/);
    assert.equal(env.metrics().opened, env.metrics().closed); assert.equal(env.patched.size, 0);
});
test('unreviewed profile refused', () => {
    const env = runtime(); const edited = JSON.parse(JSON.stringify(profile)); edited.modules[0].fileSize++;
    assert.throws(() => createColdOemInterceptor(env.api).arm(edited, env.options), /Unreviewed/);
});
test('wrong process identity refused', () => {
    const env = runtime({ process: true });
    assert.throws(() => createColdOemInterceptor(env.api).arm(profile, env.options), /Wrong OEM process/);
    assert.equal(env.patched.size, 0);
});
test('wrong PID refused', () => {
    const env = runtime(); env.options.expectedPid++;
    assert.throws(() => createColdOemInterceptor(env.api).arm(profile, env.options), /process ID/);
    assert.equal(env.patched.size, 0);
});
for (const fault of ['memory', 'writable', 'path', 'file']) test('loaded ' + fault + ' mismatch refused', () => {
    const env = runtime({ [fault]: true }); complete(env);
    assert.throws(() => createColdOemInterceptor(env.api).inspect(profile)); assert.equal(env.patched.size, 0);
});
test('duplicate loaded image refused', () => {
    const env = runtime(); complete(env); env.loadOem(0);
    assert.throws(() => createColdOemInterceptor(env.api).inspect(profile), /duplicate/);
    assert.equal(env.patched.size, 0);
});
test('bridge code attested before interception', () => {
    const env = runtime({ bridgeMemory: true });
    assert.throws(() => createColdOemInterceptor(env.api).arm(profile, env.options), /Bridge memory/);
    assert.equal(env.metrics().pins, 0); assert.equal(env.patched.size, 0);
});
test('native ABI refused before replacement', () => {
    const env = runtime({ abi: true }); assert.throws(() => createColdOemInterceptor(env.api).arm(profile, env.options), /ABI/);
    assert.equal(env.patched.size, 0);
});
test('cold installation owns nine originals', () => {
    const env = runtime(); const installer = createColdOemInterceptor(env.api);
    assert.equal(installer.arm(profile, env.options).phase, 'armed');
    env.options.principalUuid = 'changed-after-arm'; complete(env);
    assert.equal(installer.status().phase, 'installed-pinned'); assert.equal(env.patched.size, 9);
    assert.equal(env.metrics().pins, 1); assert.throws(() => installer.cancel());
    assert.equal(env.metrics().opened, env.metrics().closed);
});
for (const fault of [{ replaceAt: 4 }, { trampoline: true }, { bootstrap: true }, { prepare: true }]) test('cold failure rolls back ' + JSON.stringify(fault), () => {
    const env = runtime(fault); const installer = createColdOemInterceptor(env.api);
    installer.arm(profile, env.options); complete(env);
    assert.equal(installer.status().phase, 'failed'); assert.equal(env.patched.size, 0); assert.equal(env.metrics().pins, 0);
    assert.equal(installer.inspect(profile).verified, 13);
});
for (const fault of [{ replaceAt: 4, revert: true }, { bootstrapPinned: true }, { bootstrapUnknown: true }, { replaceAt: 4, release: true }]) test('unsafe rollback keeps ownership ' + JSON.stringify(fault), () => {
    const env = runtime(fault); const installer = createColdOemInterceptor(env.api);
    installer.arm(profile, env.options); complete(env);
    assert.equal(installer.status().phase, 'failed-pinned'); assert.equal(env.metrics().pins, 1);
    if (!fault.release) assert.ok(env.patched.size > 0);
});
test('observer registration race refused', () => {
    const env = runtime({ registrationRace: true }); const installer = createColdOemInterceptor(env.api);
    assert.equal(installer.arm(profile, env.options).phase, 'failed'); assert.equal(env.metrics().pins, 0);
    assert.equal(env.patched.size, 0);
});
test('armed cancellation is inert', () => {
    const env = runtime(); const installer = createColdOemInterceptor(env.api);
    installer.arm(profile, env.options); assert.equal(installer.cancel().phase, 'cancelled'); complete(env);
    assert.equal(env.patched.size, 0); assert.equal(env.metrics().pins, 0);
});
test('cold timeout releases observer', () => {
    const env = runtime(); const installer = createColdOemInterceptor(env.api);
    installer.arm(profile, env.options); env.timeout(); assert.equal(installer.status().phase, 'failed');
    assert.equal(env.metrics().pins, 0);
});
test('unload during preparation refuses installation', () => {
    const env = runtime(); const installer = createColdOemInterceptor(env.api);
    installer.arm(profile, env.options); env.unload(env.loadOem(0)); assert.equal(installer.status().phase, 'failed');
    assert.equal(env.patched.size, 0); assert.equal(env.metrics().pins, 0);
});
process.stdout.write(passed + ' cold installer model scenarios passed; no OEM process execution\n');
