'use strict';

const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { createColdOemInterceptor } = require('./cold_oem_interceptor');

const directory = process.argv[2];
const profile = JSON.parse(fs.readFileSync(path.join(directory, 'oem-cold-profile.json'), 'utf8'));
const boundary = JSON.parse(fs.readFileSync(path.join(__dirname, 'testdata/android9-linker-profile.json'), 'utf8'));
const linkerFile = process.argv[3];
const eligibilityPath = '/data/local/tmp/impulse-carplay-input/safety.json';
const bootIdPath = '/proc/sys/kernel/random/boot_id';
const processStatPath = '/proc/self/stat';
const checksum = value => crypto.createHash('sha256').update(typeof value === 'string' ? value : Buffer.from(value)).digest('hex');
const arrayBuffer = bytes => Uint8Array.from(bytes).buffer;

function runtime(fault = {}) {
    const images = new Map(profile.modules.map(module => [module.path,
        fs.readFileSync(path.join(directory, 'vendor__app__TsCarPlayService__lib__arm64__' + module.name))]));
    images.set(boundary.path, fs.readFileSync(linkerFile));
    for (const dependency of boundary.dependencies) {
        const captured = path.join(directory, 'vendor__app__TsCarPlayService__lib__arm64__' + dependency.name);
        images.set(dependency.path, fs.readFileSync(fs.existsSync(captured) ? captured :
            path.join(process.argv[4] || directory, dependency.name)));
    }
    if (fault.file) images.get(profile.modules[0].path)[images.get(profile.modules[0].path).length - 1] ^= 1;
    if (fault.dependencyFile) images.get(boundary.dependencies[0].path)[100] ^= 1;
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
    let timerDelay = null;
    let pins = 0;
    let opened = 0;
    let closed = 0;
    let next = 0x1000000n;
    let nativeReady = 0;
    let readyThrows = false;
    let loaderListener = null;
    let pendingLoaderListener = null;
    let notification = false;
    let loaderComplete = false;
    let loaderLocked = false;
    let currentThread = 77;
    let firstTrampoline = null;
    let eligibilityReads = 0;
    let eligibilityReadHook = null;

    class Pointer {
        constructor(value, region, label = null) { this.value = BigInt(value); this.region = region; this.label = label; }
        add(offset) { return new Pointer(this.value + BigInt(offset), this.region); }
        compare(other) { return this.value < other.value ? -1 : this.value > other.value ? 1 : 0; }
        equals(other) { return this.value === other.value; }
        isNull() { return this.value === 0n; }
        toString() { return '0x' + this.value.toString(16); }
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
        if (callback) {
            notification = true;
            try { callback.onAdded(object); } finally { notification = false; }
        }
        return object;
    }
    const linker = loaded(boundary);
    if (fault.linkerMemory) linker.memory[boundary.load.offset + 40] ^= 1;
    function withLoad(action, options = {}) {
        const previousComplete = loaderComplete, previousLock = loaderLocked;
        const listener = loaderListener;
        const context = { returnAddress: linker.base.add(options.unreviewedCaller ? 32 :
            boundary.callers[options.wrapper || 0].returnOffset) };
        loaderLocked = true; loaderComplete = false;
        if (listener) listener.onEnter.call(context);
        action();
        loaderComplete = true;
        if (listener) listener.onLeave.call(context, options.failed ? new Pointer(0, linker) : linker.base);
        loaderComplete = previousComplete; loaderLocked = previousLock;
    }
    class File {
        constructor(filePath) {
            if (filePath === eligibilityPath) {
                eligibilityReads++;
                if (eligibilityReadHook) eligibilityReadHook(eligibilityReads);
            }
            assert.ok(images.has(filePath)); this.bytes = images.get(filePath); this.position = 0; opened++;
        }
        seek(offset, mode) { this.position = mode === File.SEEK_END ? this.bytes.length + offset : offset; }
        tell() { return this.position; }
        readBytes(size) { return arrayBuffer(this.bytes.subarray(this.position, this.position + size)); }
        readText(size) {
            const bytes = this.bytes.subarray(this.position, this.position + size);
            this.position += bytes.length;
            return bytes.toString('utf8');
        }
        close() { closed++; }
    }
    File.SEEK_SET = 0; File.SEEK_END = 2;
    const api = {
        Process: {
            arch: 'arm64', pointerSize: 8, id: 1234,
            getCurrentThreadId: () => currentThread,
            enumerateModules: () => modules.slice(),
            findRangeByAddress(address) { return regions.find(item => !item.hidden && address.compare(item.base) >= 0 && address.compare(item.base.add(item.size)) < 0) || null; },
            attachModuleObserver(observer) {
                callback = observer;
                if (fault.registrationRace) loadOem(0);
                for (const object of modules.slice()) observer.onAdded(object);
                return { detach() { callback = null; } };
            }
        },
        coldLoadProfile: fault.boundaryProfile ? {} : boundary,
        Module: { getGlobalExportByName(name) {
            assert.equal(name, 'dlerror');
            return { label: 'dlerror' };
        }, load(filePath) {
            const object = loaded({ name: 'libcarplay_video_bridge.so', path: filePath });
            if (fault.bridgeMemory) object.memory[0x500] ^= 1;
            const exports = new Map();
            object.getExportByName = name => {
                if (!exports.has(name)) exports.set(name, new Pointer(object.base.value + 0x100n + BigInt(exports.size * 16), object, name));
                return exports.get(name);
            };
            object.findExportByName = name => name === 'impulse_carplay_prepare_initial_entries_for_oem_namespace'
                && !fault.legacyNamespaceApi ? object.getExportByName(name) : null;
            return object;
        } },
        File,
        Memory: {
            queryProtection(address) {
                if (fault.protectionQuery) throw Error('Protection query failed');
                const found = regions.find(item => address.compare(item.base) >= 0 && address.compare(item.base.add(item.size)) < 0);
                if (!found) throw Error('Unmapped trampoline');
                return found.protection;
            },
            alloc(size) { return region(Buffer.alloc(size)).base; },
            allocUtf8String(value) { return region(Buffer.from(value + '\0')).base; }
        },
        Cloak: { hasRangeContaining(address) { return !!address.region.cloaked; } },
        Checksum: { compute(algorithm, value) { assert.equal(algorithm, 'sha256'); return checksum(value); } },
        NativeFunction: function(address) {
            if (address.label === 'dlerror') return () => ({
                isNull: () => false,
                readUtf8String: () => 'test module is unavailable in caller namespace'
            });
            if (address.label.endsWith('_abi')) return () => fault.abi ? 2 : 1;
            if (address.label.endsWith('_state')) return () => { if (readyThrows) throw new Error('state unavailable'); return nativeReady; };
            if (address.label === 'impulse_carplay_prepare_initial_entries') return () => {
                assert.ok(loaderLocked && loaderComplete && !notification, 'pin only after completed load under mutex');
                operations.push('pin-modules'); return fault.prepare ? -1 : 0;
            };
            if (address.label === 'impulse_carplay_prepare_initial_entries_for_oem_namespace') return caller => {
                assert.ok(loaderLocked && loaderComplete && !notification, 'pin only after completed load under mutex');
                if (fault.reentrantPrepare) withLoad(() => { operations.push('nested-noload'); });
                const stack = modules.find(module => module.name === 'libcarplaystack.so');
                assert.ok(caller.equals(stack.base.add(stack.profile.functions.initialize.offset)));
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
            attach(target, callbacks) {
                assert.ok(target.equals(linker.base.add(boundary.load.offset)));
                pendingLoaderListener = callbacks;
                return { detach() { loaderListener = null; pendingLoaderListener = null; operations.push('detach-loader'); } };
            },
            replaceFast(target) {
                operations.push('replace');
                const offset = Number(target.value - target.region.base.value);
                patched.set(target.value, { target, offset, byte: target.region.memory[offset] });
                target.region.memory[offset] ^= 0xff;
                if (fault.replaceAt === patched.size) throw new Error('failure after replacement');
                if (fault.trampoline) return target;
                if (fault.duplicateTrampoline && firstTrampoline) return firstTrampoline;
                const code = region(Buffer.alloc(64));
                if (fault.cloakedTrampoline || fault.unownedTrampoline) code.hidden = true;
                if (fault.cloakedTrampoline) code.cloaked = true;
                if (fault.kernelNoX || fault.trampolineNeedsFlush) code.protection = 'rw-';
                if (fault.trampolineRWX) code.protection = 'rwx';
                if (fault.trampolineNeedsFlush) code.needsFlush = true;
                if (!firstTrampoline) firstTrampoline = code.base;
                return fault.misalignedTrampoline ? code.base.add(1) : code.base;
            },
            revert(target) {
                operations.push('revert');
                if (fault.revert) throw new Error('restore failed');
                const patch = patched.get(target.value);
                if (patch) { target.region.memory[patch.offset] = patch.byte; patched.delete(target.value); }
            },
            flush() {
                operations.push('flush'); if (pendingLoaderListener) loaderListener = pendingLoaderListener;
                for (const item of regions) if (item.needsFlush) { item.protection = 'r-x'; item.needsFlush = false; }
            }
        },
        Script: { pin() { pins++; }, unpin() { pins--; assert.ok(pins >= 0); } },
        setTimeout(fn, delay) { timer = fn; timerDelay = delay; return 1; }, clearTimeout() { timer = null; }
    };
    return { api, loadOem, withLoad, modules, patched, operations,
        setFile(filePath, contents) { images.set(filePath, Buffer.from(contents)); },
        removeFile(filePath) { images.delete(filePath); },
        onEligibilityRead(hook) { eligibilityReadHook = hook; },
        changeThread(thread) { currentThread = thread; },
        loadDependency(index) {
            const object = loaded(boundary.dependencies[index]);
            if (fault.dependencyMemory) object.memory[boundary.dependencies[index].executable[0].offset + 40] ^= 1;
            if (callback) callback.onAdded(object);
            return object;
        },
        unknownModule() { if (callback) callback.onAdded({ name: 'unreviewed.so', path: '/vendor/unreviewed.so' }); },
        options: { enabled: true, bridgePath, bridgeSize: bridgeBytes.length, bridgeSha256: checksum(bridgeBytes), principalUuid: 'main', expectedPid: 1234 },
        metrics: () => ({ pins, opened, closed, timerDelay, eligibilityReads }),
        timeout() { if (timer) timer(); },
        unload(object) { if (callback) callback.onRemoved(object); }
    };
}

let passed = 0;
function test(name, action) { action(); passed++; process.stdout.write(name + ': passed\n'); }
function complete(env) { env.withLoad(() => [0, 1, 2].forEach(index => env.loadOem(index))); }

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
test('module notifications never reenter the unfinished linker', () => {
    const env = runtime(); const installer = createColdOemInterceptor(env.api);
    installer.arm(profile, env.options);
    env.withLoad(() => {
        [0, 1, 2].forEach(index => env.loadOem(index));
        assert.equal(installer.status().phase, 'armed');
        assert.equal(env.patched.size, 0);
        assert.ok(!env.operations.includes('pin-modules'));
    });
    assert.equal(installer.status().phase, 'installed-pinned');
    assert.equal(env.operations.filter(operation => operation === 'pin-modules').length, 1);
});
test('nested completed load waits for outermost loader return', () => {
    const env = runtime(); const installer = createColdOemInterceptor(env.api);
    installer.arm(profile, env.options);
    env.withLoad(() => {
        env.withLoad(() => [0, 1, 2].forEach(index => env.loadOem(index)), { wrapper: 1 });
        assert.equal(installer.status().phase, 'armed');
        assert.equal(env.patched.size, 0);
    });
    assert.equal(installer.status().phase, 'installed-pinned');
});
test('a failed nested load cannot be hidden by outer success', () => {
    const env = runtime(); const installer = createColdOemInterceptor(env.api);
    installer.arm(profile, env.options);
    env.withLoad(() => {
        env.withLoad(() => [0, 1, 2].forEach(index => env.loadOem(index)), { failed: true });
    });
    assert.equal(installer.status().phase, 'failed');
    assert.ok(!env.operations.includes('pin-modules'));
});
test('native NOLOAD reentry cannot trigger a second installation', () => {
    const env = runtime({ reentrantPrepare: true }); const installer = createColdOemInterceptor(env.api);
    installer.arm(profile, env.options); complete(env);
    assert.equal(installer.status().phase, 'installed-pinned');
    assert.equal(env.operations.filter(operation => operation === 'pin-modules').length, 1);
    assert.equal(env.operations.filter(operation => operation === 'replace').length, 9);
    assert.ok(env.operations.includes('nested-noload'));
});
for (const fault of ['cloakedTrampoline', 'trampolineNeedsFlush', 'trampolineRWX']) test('valid executable original accepted: ' + fault, () => {
    const env = runtime({ [fault]: true }); const installer = createColdOemInterceptor(env.api);
    installer.arm(profile, env.options); complete(env);
    assert.equal(installer.status().phase, 'installed-pinned');
    assert.equal(env.patched.size, 9);
});
for (const fault of [
    { cloakedTrampoline: true, kernelNoX: true }, { unownedTrampoline: true },
    { duplicateTrampoline: true }, { misalignedTrampoline: true }, { protectionQuery: true }
]) test('unsafe original refuses bootstrap and rolls back: ' + JSON.stringify(fault), () => {
    const env = runtime(fault); const installer = createColdOemInterceptor(env.api);
    installer.arm(profile, env.options); complete(env);
    assert.equal(installer.status().phase, 'failed');
    assert.equal(env.patched.size, 0);
    assert.equal(env.metrics().pins, 0);
    assert.ok(!env.operations.includes('bootstrap'));
});
test('missing actual page-protection API refuses before arming', () => {
    const env = runtime(); delete env.api.Memory.queryProtection;
    assert.throws(() => createColdOemInterceptor(env.api).arm(profile, env.options), /Unsupported Frida runtime/);
    assert.equal(env.metrics().pins, 0);
});
test('failed loader return refuses native preparation', () => {
    const env = runtime(); const installer = createColdOemInterceptor(env.api);
    installer.arm(profile, env.options);
    env.withLoad(() => [0, 1, 2].forEach(index => env.loadOem(index)), { failed: true });
    assert.equal(installer.status().phase, 'failed');
    assert.ok(!env.operations.includes('pin-modules'));
});
test('separate incomplete load groups cannot authorize a warm installation', () => {
    const env = runtime(); const installer = createColdOemInterceptor(env.api);
    installer.arm(profile, env.options);
    env.withLoad(() => env.loadOem(0));
    complete(env);
    assert.equal(installer.status().phase, 'failed');
    assert.equal(env.patched.size, 0);
});
test('unobserved loader notification fails closed', () => {
    const env = runtime(); const installer = createColdOemInterceptor(env.api);
    installer.arm(profile, env.options); env.loadOem(0);
    assert.equal(installer.status().phase, 'failed');
    assert.match(installer.status().failure, /outside reviewed loader/);
    assert.equal(env.metrics().pins, 0);
});
test('unreviewed caller cannot impersonate locked load boundary', () => {
    const env = runtime(); const installer = createColdOemInterceptor(env.api);
    installer.arm(profile, env.options);
    env.withLoad(() => [0, 1, 2].forEach(index => env.loadOem(index)), { unreviewedCaller: true });
    assert.equal(installer.status().phase, 'failed');
    assert.equal(env.patched.size, 0);
});
test('new unreviewed dependency refuses the whole group', () => {
    const env = runtime(); const installer = createColdOemInterceptor(env.api);
    installer.arm(profile, env.options);
    env.withLoad(() => { env.unknownModule(); [0, 1, 2].forEach(index => env.loadOem(index)); });
    assert.equal(installer.status().phase, 'failed');
    assert.match(installer.status().failure, /Unreviewed cold-load dependency/);
    assert.equal(env.patched.size, 0);
});
test('hash-pinned reviewed dependency closure is admitted', () => {
    const env = runtime(); const installer = createColdOemInterceptor(env.api);
    installer.arm(profile, env.options);
    env.withLoad(() => {
        boundary.dependencies.forEach((_, index) => env.loadDependency(index));
        [0, 1, 2].forEach(index => env.loadOem(index));
    });
    assert.equal(installer.status().phase, 'installed-pinned');
});
for (const fault of ['dependencyFile', 'dependencyMemory']) test('changed ' + fault + ' rejects before native pinning', () => {
    const env = runtime({ [fault]: true }); const installer = createColdOemInterceptor(env.api);
    installer.arm(profile, env.options);
    env.withLoad(() => { env.loadDependency(0); [0, 1, 2].forEach(index => env.loadOem(index)); });
    assert.equal(installer.status().phase, 'failed');
    assert.ok(!env.operations.includes('pin-modules'));
    assert.equal(env.metrics().pins, 0);
});
test('different thread cannot publish into another loader frame', () => {
    const env = runtime(); const installer = createColdOemInterceptor(env.api);
    installer.arm(profile, env.options);
    env.withLoad(() => { env.changeThread(88); env.loadOem(0); env.changeThread(77); });
    assert.equal(installer.status().phase, 'failed');
    assert.equal(env.patched.size, 0);
});
for (const fault of ['linkerMemory', 'boundaryProfile']) test('unreviewed ' + fault + ' refuses loader instrumentation', () => {
    const env = runtime({ [fault]: true }); const installer = createColdOemInterceptor(env.api);
    assert.equal(installer.arm(profile, env.options).phase, 'failed');
    assert.equal(env.patched.size, 0);
    assert.equal(env.metrics().pins, 0);
});
for (const fault of [{ replaceAt: 4 }, { trampoline: true }, { bootstrap: true }, { prepare: true }]) test('cold failure rolls back ' + JSON.stringify(fault), () => {
    const env = runtime(fault); const installer = createColdOemInterceptor(env.api);
    installer.arm(profile, env.options); complete(env);
    assert.equal(installer.status().phase, 'failed'); assert.equal(env.patched.size, 0); assert.equal(env.metrics().pins, 0);
    assert.equal(installer.inspect(profile).verified, 13);
});
test('native pinning failure records linker detail before replacements', () => {
    const env = runtime({ prepare: true }); const installer = createColdOemInterceptor(env.api);
    installer.arm(profile, env.options); complete(env);
    const status = installer.status();
    assert.equal(status.phase, 'failed');
    assert.match(status.failure, /Native module pinning failed \(-1\): test module is unavailable in caller namespace/);
    assert.equal(env.patched.size, 0);
    assert.equal(env.metrics().pins, 0);
});
test('legacy bridge keeps original pinning path', () => {
    const env = runtime({ legacyNamespaceApi: true }); const installer = createColdOemInterceptor(env.api);
    installer.arm(profile, env.options); complete(env);
    assert.equal(installer.status().phase, 'installed-pinned');
});
test('unbounded cold observer waits are rejected before loading', () => {
    for (const timeout of [0, 4999, 300001, NaN, '300000']) {
        const env = runtime(); env.options.coldLoadTimeoutMs = timeout;
        assert.throws(() => createColdOemInterceptor(env.api).arm(profile, env.options), /timeout out of bounds/);
        assert.equal(env.patched.size, 0);
        assert.equal(env.metrics().pins, 0);
    }
});
test('first connection wait remains explicitly bounded', () => {
    const env = runtime(); env.options.coldLoadTimeoutMs = 300000;
    const installer = createColdOemInterceptor(env.api);
    assert.equal(installer.arm(profile, env.options).phase, 'armed');
    assert.equal(env.metrics().timerDelay, 300000);
    installer.cancel();
    assert.equal(env.metrics().pins, 0);
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

const manualPassed = passed;
const automaticBootId = '00112233-4455-6677-8899-aabbccddeeff';
function withWallClock(action) {
    const original = Date.now;
    let wallMs = 1800000000000;
    Date.now = () => wallMs;
    try { action({ now: () => wallMs, advance(milliseconds) { wallMs += milliseconds; } }); }
    finally { Date.now = original; }
}
function processStat(start = 8765, comm = 'com.ts.carplay') {
    return '1234 (' + comm + ') ' + ['S', ...Array(18).fill('0'), String(start), '0'].join(' ') + '\n';
}
function automatic(env, clock, overrides = {}) {
    let packet = {
        eligible: true, bootId: automaticBootId, targetPid: 1234, targetStartTime: 8765,
        wallMs: clock.now(), ticket: '12345678-1234-1234-1234-123456789abc', ...overrides
    };
    env.options.eligibilityPath = eligibilityPath;
    env.setFile(bootIdPath, automaticBootId + '\n');
    env.setFile(processStatPath, processStat());
    const update = patch => {
        packet = { ...packet, ...patch };
        env.setFile(eligibilityPath, JSON.stringify(packet));
    };
    update({});
    return { update };
}
function assertRejectedBeforePreparation(env, installer) {
    complete(env);
    assert.equal(installer.status().phase, 'failed');
    assert.equal(installer.status().pinned, false);
    assert.equal(env.patched.size, 0);
    assert.ok(!env.operations.includes('pin-modules'));
    assert.ok(!env.operations.includes('replace'));
    assert.ok(!env.operations.includes('bootstrap'));
    assert.equal(env.metrics().opened, env.metrics().closed);
}

test('manual mode never consults automatic eligibility files', () => {
    const env = runtime(); const installer = createColdOemInterceptor(env.api);
    installer.arm(profile, env.options); complete(env);
    assert.equal(installer.status().phase, 'installed-pinned');
    assert.equal(env.metrics().eligibilityReads, 0);
});
test('automatic mode validates live boot PID starttime and ticket at all three boundaries', () => withWallClock(clock => {
    const env = runtime(); automatic(env, clock);
    const installer = createColdOemInterceptor(env.api);
    assert.equal(installer.arm(profile, env.options).phase, 'armed');
    assert.equal(env.metrics().eligibilityReads, 0, 'arming is an inert observer, not native preparation');
    complete(env);
    assert.equal(installer.status().phase, 'installed-pinned');
    assert.equal(env.metrics().eligibilityReads, 3);
    assert.equal(env.patched.size, 9);
}));
for (const age of [0, 3000]) test('automatic eligibility accepts freshness boundary ' + age, () => withWallClock(clock => {
    const env = runtime(); automatic(env, clock, { wallMs: clock.now() - age });
    const installer = createColdOemInterceptor(env.api);
    installer.arm(profile, env.options); complete(env);
    assert.equal(installer.status().phase, 'installed-pinned');
}));
for (const input of [
    { eligible: false }, { eligible: null }, { eligible: 'true' },
    { bootId: null }, { bootId: 'ffeeddcc-bbaa-9988-7766-554433221100' },
    { targetPid: 9999 }, { targetPid: '1234' }, { targetPid: null },
    { targetStartTime: 8766 }, { targetStartTime: null },
    { wallMs: null }, { wallMs: '1800000000000' }, { wallMs: 1800000000000.5 },
    { ticket: null }, { ticket: '' }, { ticket: 'short' }, { ticket: 'g'.repeat(32) },
    { ticket: 'a'.repeat(65) }, { ticket: 'A'.repeat(32) }, { ticket: 1000000000000000 }
]) test('automatic mode rejects invalid or unknown input ' + JSON.stringify(input), () => withWallClock(clock => {
    const env = runtime(); automatic(env, clock, input);
    const installer = createColdOemInterceptor(env.api);
    installer.arm(profile, env.options);
    assertRejectedBeforePreparation(env, installer);
}));
for (const age of [-1, 3001, 60000]) test('automatic mode refuses future or stale lease age ' + age, () => withWallClock(clock => {
    const env = runtime(); automatic(env, clock, { wallMs: clock.now() - age });
    const installer = createColdOemInterceptor(env.api);
    installer.arm(profile, env.options); assertRejectedBeforePreparation(env, installer);
}));
test('automatic eligibility expiration between arm and native load cancels cleanly', () => withWallClock(clock => {
    const env = runtime(); automatic(env, clock);
    const installer = createColdOemInterceptor(env.api); installer.arm(profile, env.options);
    clock.advance(3001);
    assertRejectedBeforePreparation(env, installer);
}));
test('a new fresh heartbeat between arm and native load is observed', () => withWallClock(clock => {
    const env = runtime(); const safety = automatic(env, clock);
    const installer = createColdOemInterceptor(env.api); installer.arm(profile, env.options);
    clock.advance(6000); safety.update({ wallMs: clock.now() });
    complete(env); assert.equal(installer.status().phase, 'installed-pinned');
}));
test('safety loss between arm and native load blocks before acquiring module ownership', () => withWallClock(clock => {
    const env = runtime(); const safety = automatic(env, clock);
    const installer = createColdOemInterceptor(env.api); installer.arm(profile, env.options);
    safety.update({ eligible: false }); assertRejectedBeforePreparation(env, installer);
}));
test('boot and PID-reuse epochs cannot reuse an armed automatic observer', () => withWallClock(clock => {
    for (const replacement of [
        [bootIdPath, 'ffeeddcc-bbaa-9988-7766-554433221100\n'],
        [processStatPath, processStat(8766)]
    ]) {
        const env = runtime(); automatic(env, clock);
        const installer = createColdOemInterceptor(env.api); installer.arm(profile, env.options);
        env.setFile(...replacement); assertRejectedBeforePreparation(env, installer);
    }
}));
test('process comm parentheses do not obscure the authoritative starttime field', () => withWallClock(clock => {
    const env = runtime(); automatic(env, clock);
    env.setFile(processStatPath, processStat(8765, 'worker (carplay) )'));
    const installer = createColdOemInterceptor(env.api); installer.arm(profile, env.options);
    complete(env); assert.equal(installer.status().phase, 'installed-pinned');
}));
for (const missing of [eligibilityPath, bootIdPath, processStatPath]) test('missing automatic identity source fails closed: ' + missing, () => withWallClock(clock => {
    const env = runtime(); automatic(env, clock); env.removeFile(missing);
    const installer = createColdOemInterceptor(env.api); installer.arm(profile, env.options);
    assertRejectedBeforePreparation(env, installer);
}));
for (const malformed of ['', 'not JSON', 'null', '[]', '{}', ' '.repeat(4097)]) test('malformed automatic safety document fails closed: ' + JSON.stringify(malformed.slice(0, 30)), () => withWallClock(clock => {
    const env = runtime(); automatic(env, clock); env.setFile(eligibilityPath, malformed);
    const installer = createColdOemInterceptor(env.api); installer.arm(profile, env.options);
    assertRejectedBeforePreparation(env, installer);
}));
for (const pathValue of ['/tmp/safety.json', eligibilityPath + '.old', null]) test('automatic mode cannot select an unreviewed input path ' + pathValue, () => {
    const env = runtime(); env.options.eligibilityPath = pathValue;
    assert.throws(() => createColdOemInterceptor(env.api).arm(profile, env.options), /Unreviewed automatic eligibility source/);
    assert.equal(env.metrics().opened, 0);
    assert.equal(env.metrics().pins, 0);
});
for (const check of [2, 3]) test('eligibility revocation at check ' + check + ' restores every attempted OEM entry', () => withWallClock(clock => {
    const env = runtime(); const safety = automatic(env, clock);
    env.onEligibilityRead(count => { if (count === check) safety.update({ eligible: false }); });
    const installer = createColdOemInterceptor(env.api); installer.arm(profile, env.options); complete(env);
    assert.equal(installer.status().phase, 'failed');
    assert.equal(env.metrics().pins, 0); assert.equal(env.patched.size, 0);
    assert.equal(env.operations.filter(op => op === 'pin-modules').length, 1);
    assert.equal(env.operations.filter(op => op === 'release-modules').length, 1);
    assert.equal(env.operations.filter(op => op === 'replace').length, check === 3 ? 9 : 0);
    assert.equal(env.operations.filter(op => op === 'revert').length, check === 3 ? 9 : 0);
    assert.ok(!env.operations.includes('bootstrap'));
    assert.equal(installer.inspect(profile).verified, 13);
}));
for (const check of [2, 3]) test('new valid attempt ticket at check ' + check + ' cannot claim another attempt', () => withWallClock(clock => {
    const env = runtime(); const safety = automatic(env, clock);
    env.onEligibilityRead(count => { if (count === check) safety.update({ ticket: 'abcdef12-3456-7890-abcd-ef1234567890' }); });
    const installer = createColdOemInterceptor(env.api); installer.arm(profile, env.options); complete(env);
    assert.equal(installer.status().phase, 'failed');
    assert.equal(env.metrics().pins, 0); assert.equal(env.patched.size, 0);
    assert.ok(!env.operations.includes('bootstrap'));
}));
test('uncertain rollback after late eligibility loss retains pinned ownership', () => withWallClock(clock => {
    const env = runtime({ revert: true }); const safety = automatic(env, clock);
    env.onEligibilityRead(count => { if (count === 3) safety.update({ eligible: false }); });
    const installer = createColdOemInterceptor(env.api); installer.arm(profile, env.options); complete(env);
    assert.equal(installer.status().phase, 'failed-pinned');
    assert.equal(env.metrics().pins, 1); assert.equal(env.patched.size, 9);
    assert.ok(!env.operations.includes('release-modules'));
    assert.ok(!env.operations.includes('bootstrap'));
}));
process.stdout.write(passed + ' cold installer model scenarios passed (' + manualPassed + ' original manual scenarios); no OEM process execution\n');
