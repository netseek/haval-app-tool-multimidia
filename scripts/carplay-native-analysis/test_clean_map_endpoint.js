'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {validateCallbacks} = require('./test_java_callback_signatures');
const source = fs.readFileSync(path.join(__dirname, 'carplay_clean_map_endpoint.js'), 'utf8');
const bridgePath = '/data/local/tmp/impulse-carplay/passthrough-v1/libcarplay_video_bridge.so';
const request = 'br.com.redesurftank.havalshisuku.CARPLAY_CLEAN_MAP_REQUEST';
const revoke = 'br.com.redesurftank.havalshisuku.CARPLAY_CLEAN_MAP_REVOKE';

function environment(fault = {}) {
    let receiver = null;
    let serial = 0;
    let epoch = 42;
    let arrayCalls = 0;
    const timers = new Map();
    const connections = [];
    const endpoints = [];
    const offers = [];
    const messages = [];
    const unbound = [];
    const app = {
        getPackageManager() { return { getApplicationInfo() { return {sourceDir: {value: '/test/impulse.apk'}, uid: {value: 10052}}; } }; },
        getCodeCacheDir() { return {getAbsolutePath() { return '/test/cache'; }}; },
        getClassLoader() { return {}; },
        registerReceiver(value) { receiver = value; },
        bindService(intent, connection) {
            connections.push(connection);
            if (fault.bindThrows) throw new Error('bind failed');
            return !fault.bindRejected;
        },
        unbindService(connection) { unbound.push(connection); }
    };
    const endpointClass = {
        class: {$h: {}},
        $new(uid, token) {
            assert.equal(uid, 10052);
            const value = { token: token.slice(), closed: false, closeCount: 0,
                close() { this.closed = true; this.closeCount++; this.token.fill(0); } };
            endpoints.push(value);
            return value;
        }
    };
    const stub = {asInterface() {
        return fault.nullBinder ? null : { offerEndpoint(endpoint, token, generation) {
            assert.equal(token.length, 32);
            assert.ok(token.every(value => value === 7));
            offers.push({endpoint, token: token.slice(), generation});
        } };
    } };
    const Java = {
        perform(action) { action(); },
        array(type, values) { assert.equal(type, 'byte'); arrayCalls++; return values.slice(); },
        vm: {getEnv() { return {handle: {}}; }},
        ClassFactory: {get() { return {use(name) {
            if (name.endsWith('CarPlaySurfaceEndpoint')) return endpointClass;
            if (name.endsWith('ICarPlayClusterBridge$Stub')) return stub;
            throw new Error('Unexpected APK class ' + name);
        }}; }},
        registerClass(definition) {
            const methods = validateCallbacks(definition);
            return {$new() { return { javaId: ++serial, methods }; }};
        },
        use(name) {
            if (name === 'android.app.ActivityThread') return {currentApplication() { return app; }};
            if (name === 'dalvik.system.DexClassLoader') return {$new() { return {}; }};
            if (name === 'android.content.IntentFilter') return {$new() { return {addAction() {}}; }};
            if (name === 'android.content.Intent') return {$new() { return {setClassName() {}}; }};
            if (name === 'java.security.SecureRandom') return {$new() { return {nextBytes(bytes) { bytes.fill(7); }}; }};
            if (name === '[B') return {$new(size) { return Array(size).fill(0); }};
            if (name === 'android.content.ServiceConnection' || name === 'android.content.BroadcastReceiver') return {};
            throw new Error('Unexpected Java class ' + name);
        }
    };
    const context = {rpc: {exports: {}}, send(value) { messages.push(value); }, Java,
        Process: {getModuleByName() { return {path: bridgePath, getExportByName(name) { return name; }}; }},
        NativeFunction: function(name) { return () => name.endsWith('principal_epoch') ? epoch : 0; },
        setTimeout(callback) { const id = ++serial; timers.set(id, callback); return id; },
        clearTimeout(id) { timers.delete(id); }
    };
    if (fault.noJava) delete context.Java;
    vm.runInNewContext(source, context);
    return {
        api: context.rpc.exports, connections, endpoints, offers, messages, unbound, timers,
        setEpoch(value) { epoch = value; },
        arrayCalls() { return arrayCalls; },
        broadcast(action) { receiver.methods.onReceive(null, {getAction() { return action; }}); },
        connect(connection = connections.at(-1)) {
            // Frida may supply another JS wrapper for the same Java object.
            connection.methods.onServiceConnected.call({javaId: connection.javaId}, null, {});
        },
        disconnect(connection, method = 'onServiceDisconnected') {
            connection.methods[method].call({javaId: connection.javaId}, null);
        }
    };
}

let passed = 0;
async function test(name, action) { await action(); passed++; process.stdout.write(name + ': passed\n'); }

(async () => {
    await test('matching callback works with a different Java wrapper', async () => {
        const env = environment(); assert.equal(await env.api.arm({bridgePath}), 'armed');
        env.broadcast(request); env.connect();
        assert.equal(env.offers.length, 1);
        assert.equal(env.offers[0].generation, 42);
        assert.equal(env.arrayCalls(), 1);
        assert.equal(env.api.state().offered, true);
        env.connect(); assert.equal(env.offers.length, 1, 'duplicate callback must not reoffer a cleared token');
    });
    await test('stop while binding closes the pending endpoint', async () => {
        const env = environment(); await env.api.arm({bridgePath});
        env.broadcast(request); const old = env.connections[0]; env.broadcast(revoke);
        assert.equal(env.endpoints[0].closed, true);
        assert.ok(env.endpoints[0].token.every(value => value === 0));
        env.connect(old); assert.equal(env.offers.length, 0);
        assert.equal(env.api.state().binding, false);
    });
    await test('old disconnect cannot retire a newer request', async () => {
        const env = environment(); await env.api.arm({bridgePath});
        env.broadcast(request); const old = env.connections[0]; env.broadcast(revoke);
        env.broadcast(request); env.disconnect(old); env.connect();
        assert.equal(env.offers.length, 1);
        assert.equal(env.endpoints[1].closed, false);
        env.broadcast(revoke); assert.equal(env.endpoints[1].closed, true);
    });
    for (const fault of [{bindRejected: true}, {bindThrows: true}, {nullBinder: true}]) {
        await test('binding failure retires its endpoint ' + JSON.stringify(fault), async () => {
            const env = environment(fault); await env.api.arm({bridgePath});
            env.broadcast(request);
            if (fault.nullBinder) env.connect();
            assert.equal(env.offers.length, 0);
            assert.equal(env.endpoints[0].closed, true);
            assert.equal(env.api.state().requested, false);
        });
    }
    await test('principal generation change rejects the pending offer', async () => {
        const env = environment(); await env.api.arm({bridgePath});
        env.broadcast(request); env.setEpoch(43); env.connect();
        assert.equal(env.offers.length, 0);
        assert.equal(env.endpoints[0].closed, true);
    });
    await test('stop cancels waiting for the first principal', async () => {
        const env = environment(); await env.api.arm({bridgePath}); env.setEpoch(0);
        env.broadcast(request); assert.equal(env.timers.size, 1); assert.equal(env.endpoints.length, 0);
        env.broadcast(revoke); assert.equal(env.timers.size, 0);
    });
    for (const method of ['onServiceDisconnected', 'onBindingDied', 'onNullBinding']) {
        await test('typed Android callback retires active request: ' + method, async () => {
            const env = environment(); await env.api.arm({bridgePath});
            env.broadcast(request); env.connect();
            assert.equal(env.api.state().offered, true);
            env.disconnect(env.connections[0], method);
            assert.equal(env.api.state().requested, false);
            assert.equal(env.api.state().offered, false);
            assert.equal(env.endpoints[0].closeCount, 1);
            assert.equal(env.unbound.length, 1);
            env.broadcast(revoke);
            assert.equal(env.endpoints[0].closeCount, 1, 'revoke after disconnect is idempotent');
        });
    }
    await test('missing Java bridge fails with an actionable error', async () => {
        const env = environment({noJava: true});
        await assert.rejects(async () => env.api.arm({bridgePath}), /Java bridge unavailable/);
    });
    process.stdout.write(passed + ' endpoint lifecycle scenarios passed with pinned registerClass/DEX signature checks; no Android/Binder execution\n');
})().catch(error => { process.stderr.write(String(error.stack) + '\n'); process.exitCode = 1; });
