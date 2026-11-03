'use strict';

const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { createImpulseResidencyPeer } = require('./impulse_residency_peer');

const BOOT = '12345678-abcd-4abc-8def-123456789abc';
const INSTANCE = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee';
const NONCE = '11111111-2222-4333-8444-555555555555';
const NEXT_NONCE = '66666666-7777-4888-8999-aaaaaaaaaaaa';
const REVISION = '74138-123456789abc-abcdef123456';
let cases = 0;

function environment(role = 'native') {
    let now = 10_000, phase = 'armed', poll = null, renameFails = false, writes = 0;
    const files = new Map(), flushed = new Set(), closed = new Set();
    const config = {
        schema: 1, role, bootId: BOOT, targetPid: 42, targetStartTime: 123,
        revision: REVISION, ticket: 'bbbbbbbb-cccc-4ddd-8eee-ffffffffffff', instance: INSTANCE,
        scriptSha256: crypto.createHash('sha256').update('reviewed script').digest('hex'),
        runtimeDirectory: '/data/local/tmp/impulse-carplay/runtime/' + BOOT + '-42-123',
        payloadDirectory: '/data/local/tmp/impulse-carplay/versions/' + REVISION
    };
    const scriptPath = role === 'native' ? config.runtimeDirectory + '/cold-launcher.js' :
        config.payloadDirectory + '/endpoint.js';
    files.set('/proc/sys/kernel/random/boot_id', BOOT + '\n');
    files.set('/proc/self/stat', '42 (com.ts.carplay (worker)) ' + ['S', ...Array(18).fill('0'), '123', '0'].join(' '));
    files.set('/proc/self/cmdline', 'com.ts.carplay\0');
    files.set(scriptPath, 'reviewed script');
    class File {
        constructor(path, mode) {
            this.path = path;
            if (mode === 'w') { files.set(path, ''); flushed.delete(path); closed.delete(path); }
            else if (path !== '/proc/uptime' && !files.has(path)) throw Error('missing file ' + path);
        }
        readText(limit) {
            assert.ok(Number.isInteger(limit) && limit > 0, 'proc reads must have explicit bounds');
            const text = this.path === '/proc/uptime' ? (now / 1000).toFixed(3) + ' 0.00\n' : files.get(this.path);
            return text.slice(0, limit);
        }
        readBytes(limit) { return Uint8Array.from(Buffer.from(files.get(this.path)).subarray(0, limit)).buffer; }
        write(text) { files.set(this.path, files.get(this.path) + text); }
        flush() { flushed.add(this.path); }
        close() { closed.add(this.path); }
    }
    const api = {
        File, Process: { id: 42 }, Date: { now: () => 1_700_000_000_000 + now },
        Checksum: { compute: (algorithm, bytes) => crypto.createHash(algorithm).update(Buffer.from(bytes)).digest('hex') },
        Module: { getGlobalExportByName(name) { assert.equal(name, 'rename'); return name; } },
        Memory: { allocUtf8String: text => text },
        NativeFunction: function () {
            return (source, destination) => {
                assert.ok(flushed.has(source) && closed.has(source), 'flush and close before atomic replace');
                if (renameFails) return -1;
                files.set(destination, files.get(source)); files.delete(source); writes++;
                return 0;
            };
        },
        setInterval(callback, delay) { assert.equal(delay, 250); poll = callback; return 1; },
        clearInterval() { poll = null; }
    };
    const peer = createImpulseResidencyPeer(role, config.payloadDirectory + '/libcarplay_video_bridge.so',
        () => role === 'native' ? { phase, replacements: phase === 'armed' ? 0 : 9, pinned: true, failure: null } :
            { armed: true, requested: false, binding: false, offered: false, generation: 0 }, api);
    const responsePath = config.runtimeDirectory + '/' + role + '-residency-response.json';
    const challengePath = config.runtimeDirectory + '/' + role + '-residency-challenge.json';
    return {
        api, config, peer, files, scriptPath, responsePath, challengePath,
        tick(time = now) { now = time; if (poll) poll(); },
        challenge(nonce = NONCE, issuedElapsedMs = now, changes = {}) {
            files.set(challengePath, JSON.stringify({ schema: 1, instance: INSTANCE, nonce, issuedElapsedMs, ...changes }));
        },
        response() { return files.has(responsePath) ? JSON.parse(files.get(responsePath)) : null; },
        get poll() { return poll; }, get writes() { return writes; },
        set phase(value) { phase = value; }, set renameFails(value) { renameFails = value; }
    };
}

function test(name, callback) { callback(); cases++; process.stdout.write('ok ' + name + '\n'); }

test('two fresh samples bind exact identity and observe phase updates', () => {
    const env = environment();
    env.peer.init(env.config); env.challenge(); env.tick();
    assert.equal(env.response(), null);
    env.phase = 'installed-pinned'; env.tick(10_250);
    const response = env.response();
    for (const [key, value] of Object.entries(env.config)) assert.equal(response[key], value, key);
    assert.equal(response.disposing, false); assert.equal(response.nonce, NONCE);
    assert.equal(response.samples.length, 2);
    assert.deepEqual(response.samples.map(sample => sample.sequence), [1, 2]);
    assert.deepEqual(response.samples.map(sample => sample.elapsedMs), [10_000, 10_250]);
    assert.equal(response.samples[0].state.phase, 'armed');
    assert.equal(response.samples[1].state.phase, 'installed-pinned');
    assert.equal(env.writes, 1);
});

test('completed nonce is not rewritten and the next challenge advances sequence', () => {
    const env = environment(); env.peer.init(env.config); env.challenge(); env.tick(); env.tick(10_250);
    env.tick(10_500); assert.equal(env.writes, 1);
    env.challenge(NEXT_NONCE, 10_500); env.tick(10_500); env.tick(10_750);
    assert.equal(env.writes, 2); assert.equal(env.response().nonce, NEXT_NONCE);
    assert.deepEqual(env.response().samples.map(sample => sample.sequence), [3, 4]);
    env.challenge(NONCE, 10_000); env.tick(11_000); env.tick(11_250);
    assert.equal(env.writes, 2, 'an older challenge cannot be replayed after a newer response');
});

test('normal detach disposal revokes even a retained callback and cannot reinitialize', () => {
    const env = environment(); env.peer.init(env.config); env.challenge(); env.tick(); env.tick(10_250);
    const retained = env.poll;
    env.peer.dispose('unload');
    assert.equal(env.poll, null); assert.equal(env.response().disposing, true);
    assert.deepEqual(env.response().samples, []);
    const writes = env.writes;
    env.challenge(NEXT_NONCE, 10_250); retained();
    assert.equal(env.writes, writes);
    assert.throws(() => env.peer.init(env.config), /disposed/);
});

test('disposal before async arm completion irreversibly rejects later init', () => {
    const env = environment('java'); env.peer.dispose('unload');
    assert.throws(() => env.peer.init(env.config), /disposed/);
    assert.equal(env.poll, null); assert.equal(env.response(), null);
});

test('idempotent init accepts the same config and rejects another instance', () => {
    const env = environment(); env.peer.init(env.config); const callback = env.poll;
    env.peer.init({ ...env.config }); assert.equal(env.poll, callback);
    assert.throws(() => env.peer.init({ ...env.config, instance: NONCE }), /another instance/);
});

test('untrusted metadata, traversal and role mismatches never enable polling', () => {
    const variants = [
        { schema: 2 }, { role: 'java' }, { bootId: NONCE }, { targetPid: 43 }, { targetStartTime: 124 },
        { targetStartTime: Number.MAX_SAFE_INTEGER + 1 }, { revision: '../other' },
        { ticket: 'not a UUID' }, { ticket: [NONCE] }, { instance: 'not a UUID' }, { scriptSha256: 'wrong' },
        { runtimeDirectory: '/tmp/response' }, { payloadDirectory: '/tmp/payload' }
    ];
    for (const changes of variants) {
        const env = environment();
        assert.throws(() => env.peer.init({ ...env.config, ...changes }), /Residency:/);
        assert.equal(env.poll, null); assert.equal(env.response(), null);
    }
});

test('exact loaded role script checksum and size are required', () => {
    for (const bytes of ['changed script', 'x'.repeat(2_097_153)]) {
        const env = environment(); env.files.set(env.scriptPath, bytes);
        assert.throws(() => env.peer.init(env.config), /script identity mismatch/);
        assert.equal(env.poll, null);
    }
});

test('zombie, wrong argv and malformed kernel identities fail closed', () => {
    for (const [path, text] of [
        ['/proc/self/stat', 'truncated'], ['/proc/self/stat', '42 (com.ts.carplay) Z ' + Array(18).fill('0').join(' ') + ' 123'],
        ['/proc/sys/kernel/random/boot_id', BOOT + 'x'.repeat(200)],
        ['/proc/self/cmdline', 'other-process\0'], ['/proc/self/cmdline', 'com.ts.carplay']
    ]) {
        const env = environment(); env.files.set(path, text);
        assert.throws(() => env.peer.init(env.config), /Residency:/);
        assert.equal(env.poll, null);
    }
});

test('freshness, instance, nonce and challenge schema are all required', () => {
    for (const changes of [{ schema: 2 }, { instance: NEXT_NONCE }, { nonce: 'old' },
        { issuedElapsedMs: 10_001 }, { issuedElapsedMs: 6_999 }, { issuedElapsedMs: -1 }]) {
        const env = environment(); env.peer.init(env.config); env.challenge(NONCE, 10_000, changes);
        env.tick(); assert.equal(env.response(), null);
    }
});

test('minimum sample gap is enforced even when the callback fires early', () => {
    const env = environment(); env.peer.init(env.config); env.challenge(); env.tick(); env.tick(10_050);
    assert.equal(env.response(), null); env.tick(10_100);
    assert.equal(env.response().samples[1].elapsedMs - env.response().samples[0].elapsedMs, 100);
});

test('new challenge cannot reuse a first sample from malformed or interrupted input', () => {
    const env = environment(); env.peer.init(env.config); env.challenge(); env.tick();
    env.files.set(env.challengePath, '{partial'); env.tick(10_250);
    env.challenge(NEXT_NONCE, 10_250); env.tick(10_250); assert.equal(env.response(), null);
    env.tick(10_500);
    assert.equal(env.response().nonce, NEXT_NONCE);
    assert.deepEqual(env.response().samples.map(sample => sample.elapsedMs), [10_250, 10_500]);
});

test('atomic rename failure never exposes a half-written valid response', () => {
    const env = environment(); env.peer.init(env.config); env.challenge(); env.tick();
    env.renameFails = true; env.tick(10_250); assert.equal(env.response(), null);
    env.renameFails = false; env.tick(10_500); assert.equal(env.response(), null);
    env.tick(10_750); assert.equal(env.response().nonce, NONCE);
    assert.deepEqual(env.response().samples.map(sample => sample.elapsedMs), [10_500, 10_750]);
});

test('monotonic regression revokes permanently rather than publishing a stale pair', () => {
    const env = environment(); env.peer.init(env.config); env.challenge(); env.tick(); env.tick(9_999);
    assert.equal(env.response().disposing, true); assert.equal(env.poll, null);
});

test('PID reuse or new boot invalidates a previously running peer', () => {
    for (const path of ['/proc/self/stat', '/proc/sys/kernel/random/boot_id']) {
        const env = environment(); env.peer.init(env.config);
        env.files.set(path, env.files.get(path).replace(path.endsWith('stat') ? '123' : BOOT,
            path.endsWith('stat') ? '124' : NONCE));
        env.tick(); assert.equal(env.response().disposing, true); assert.equal(env.poll, null);
    }
});

test('Java responses retain armed callback state and numeric start time', () => {
    const env = environment('java'); env.peer.init(env.config); env.challenge(); env.tick(); env.tick(10_250);
    assert.equal(typeof env.response().targetStartTime, 'number');
    assert.equal(env.response().samples[1].state.armed, true);
    assert.equal(env.response().role, 'java');
});

process.stdout.write(cases + ' residency scenarios passed\n');
