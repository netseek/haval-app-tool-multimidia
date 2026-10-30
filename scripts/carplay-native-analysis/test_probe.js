'use strict';

const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const profile = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const source = fs.readFileSync(path.join(__dirname, 'oem_ingress_probe.js'), 'utf8');
const attestation = Object.fromEntries(profile.modules.map(module => [module.name, module.sha256]));

function runtime(fault = null) {
    const addresses = new Map();
    const modules = new Map();
    const hooks = new Map();
    const handles = [];
    const messages = [];
    const scheduled = [];
    class Value {
        constructor(value) { this.value = BigInt(value); }
        toString() { return '0x' + this.value.toString(16); }
        toUInt32() { return Number(this.value & 0xffffffffn); }
        toInt32() { return Number(BigInt.asIntN(32, this.value)); }
        isNull() { return this.value === 0n; }
    }
    class MemoryBuffer {
        constructor(size) { this.bytes = Buffer.alloc(size); }
        readByteArray(size) { return new Uint8Array(this.bytes.subarray(0, size)).buffer; }
        readUtf8String(size) { return this.bytes.toString('utf8', 0, size); }
    }
    class Address extends Value {
        constructor(value, module) { super(value); this.module = module; }
        add(offset) { return new Address(this.value + BigInt(offset), this.module); }
        readByteArray(size) {
            const offset = Number(this.value - this.module.base.value);
            let hex = null;
            if (offset === this.module.profile.note.offset) hex = this.module.profile.note.hex;
            for (const entry of Object.values(this.module.profile.functions)) {
                if (entry.offset === offset) hex = entry.signature;
            }
            assert.ok(hex, 'Unexpected target read');
            const bytes = Buffer.from(hex, 'hex').subarray(0, size);
            if (fault === 'signature') bytes[0] ^= 1;
            return new Uint8Array(bytes).buffer;
        }
    }
    profile.modules.forEach((entry, index) => {
        const module = { path: entry.path, profile: entry, size: 0x400000 };
        module.base = new Address((index + 1) * 0x1000000, module);
        modules.set(entry.name, module);
        for (const [label, target] of Object.entries(entry.functions)) {
            addresses.set(module.base.add(target.offset).toString(), label);
        }
    });
    const context = {
        rpc: { exports: {} },
        Process: { arch: 'arm64', pointerSize: 8, getModuleByName: name => modules.get(name) },
        Memory: { alloc: size => new MemoryBuffer(size) },
        Checksum: { compute: (algorithm, data) => crypto.createHash(algorithm).update(
            typeof data === 'string' ? data : Buffer.from(data)).digest('hex') },
        NativeFunction: function(address) {
            const label = addresses.get(address.toString());
            if (label === 'getLength') return value => value.text.length;
            if (label === 'copyString') return (value, output, size, encoding) => {
                assert.equal(encoding, 0x08000100);
                if (value.text.length + 1 > size) return 0;
                output.bytes.write(value.text, 'utf8');
                return 1;
            };
            throw new Error('Unexpected native function');
        },
        Interceptor: { attach: (address, callbacks) => {
            if (fault === 'attach' && handles.length === 2) throw new Error('Synthetic attach failure');
            hooks.set(addresses.get(address.toString()), callbacks);
            const handle = { detached: false, detach() { this.detached = true; } };
            handles.push(handle);
            return handle;
        } },
        send: message => messages.push(message),
        setTimeout: callback => ({ callback: callback }),
        clearTimeout: () => {},
        setImmediate: callback => scheduled.push(callback),
    };
    vm.runInNewContext(source, context, { timeout: 1000 });
    return { api: context.rpc.exports, hooks, handles, messages, scheduled,
        value: number => new Value(number), uuid: text => ({ text: text, isNull: () => false }) };
}

let machine = runtime();
assert.equal(machine.handles.length, 0);
assert.throws(() => machine.api.start(profile, {}, {}), /attestation/);
assert.equal(machine.handles.length, 0);
const changed = structuredClone(profile);
changed.modules[0].functions.session.offset += 4;
assert.throws(() => machine.api.start(changed, attestation, {}), /Unreviewed/);
assert.equal(machine.handles.length, 0);
machine = runtime('signature');
assert.throws(() => machine.api.start(profile, attestation, {}), /identity mismatch|signature mismatch/);
assert.equal(machine.handles.length, 0);
machine = runtime('attach');
assert.throws(() => machine.api.start(profile, attestation, {}), /Synthetic attach/);
assert.ok(machine.handles.every(handle => handle.detached));
assert.equal(machine.api.status().active, false);

machine = runtime();
assert.equal(machine.api.start(profile, attestation, {}).capturesPixels, false);
assert.equal(machine.handles.length, 5);
const session = { threadId: 7 };
const uuid = machine.uuid('synthetic-main');
machine.hooks.get('session').onEnter.call(session, [machine.value(1), uuid, machine.value(71), machine.value(0)]);
machine.hooks.get('initialize').onEnter.call({ threadId: 7 }, [machine.value(123)]);
machine.hooks.get('session').onLeave.call(session, machine.value(0));
machine.hooks.get('start').onEnter.call({ threadId: 7 }, [machine.value(123), machine.value(71), uuid]);
for (let index = 0; index < 30; index += 1) {
    machine.hooks.get('data').onEnter.call({ threadId: 9 }, [machine.value(123), null,
        machine.value(4096), machine.value(0xffffffffffffffffn)]);
}
const samples = machine.messages.filter(message => message.event === 'frame_sample');
assert.equal(samples.length, 2);
assert.equal(samples[1].displayTicksHex, '0xffffffffffffffff');
const entered = machine.messages.find(message => message.event === 'session_enter');
const initialized = machine.messages.find(message => message.event === 'initialize');
assert.equal(entered.sessionSequence, initialized.sessionSequence);
assert.ok(!JSON.stringify(machine.messages).includes('synthetic-main'));
machine.hooks.get('finalize').onEnter.call({}, [machine.value(123)]);
machine.api.stop();
assert.ok(machine.handles.every(handle => handle.detached));

machine = runtime();
machine.api.start(profile, attestation, { maximumRecords: 2 });
machine.hooks.get('session').onEnter.call({ threadId: 7 }, [machine.value(1), uuid, machine.value(71)]);
machine.hooks.get('initialize').onEnter.call({ threadId: 7 }, [machine.value(123)]);
for (const callback of machine.scheduled) callback();
assert.equal(machine.messages.length, 2);
assert.ok(machine.handles.every(handle => handle.detached));
console.log('Observer mocks: inert load, attestation, immutable profile, signatures, rollback, scope, 64-bit ticks, privacy and bounds passed');
