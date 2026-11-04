'use strict';

// Liveness is proved by a new challenge after the exact -e client has exited.
// dispose() revokes proof even if Script.pin() keeps an ordinary unload pending.
function createImpulseResidencyPeer(role, bridgePath, readState, api = globalThis) {
    const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
    const isUuid = value => typeof value === 'string' && uuid.test(value);
    let config = null;
    let configKey = null;
    let timer = null;
    let disposing = false;
    let sequence = 0;
    let lastElapsed = -1;
    let pending = null;
    let completedNonce = null;
    let completedIssuedElapsedMs = -1;
    let rename = null;

    function requireValue(condition, message) {
        if (!condition) throw new Error('Residency: ' + message);
    }
    function readText(path, limit) {
        const file = new api.File(path, 'r');
        try {
            const text = file.readText(limit + 1);
            requireValue(typeof text === 'string' && text.length <= limit, 'input exceeds bound');
            return text;
        } finally { file.close(); }
    }
    function readIdentity() {
        const boot = readText('/proc/sys/kernel/random/boot_id', 128).trim();
        const stat = readText('/proc/self/stat', 4096).trim();
        const end = stat.lastIndexOf(') ');
        const fields = end >= 0 ? stat.slice(end + 2).split(/\s+/) : [];
        const start = fields[19];
        requireValue(isUuid(boot) && Number.isSafeInteger(api.Process.id) && api.Process.id > 0 &&
            stat.slice(0, stat.indexOf(' (')) === String(api.Process.id) &&
            /^[1-9][0-9]*$/.test(start || '') && !['Z', 'X', 'x'].includes(fields[0]), 'invalid OEM identity');
        const command = new api.File('/proc/self/cmdline', 'rb');
        try {
            const bytes = new Uint8Array(command.readBytes(256));
            const end = bytes.indexOf(0);
            requireValue(end > 0 && String.fromCharCode(...bytes.slice(0, end)) === 'com.ts.carplay',
                'unexpected OEM command');
        } finally { command.close(); }
        return { boot, pid: api.Process.id, start };
    }
    function elapsedMs() {
        const uptime = readText('/proc/uptime', 128);
        const match = /^(\d+(?:\.\d+)?)\s/.exec(uptime);
        const value = match ? Math.floor(Number(match[1]) * 1000) : NaN;
        requireValue(Number.isSafeInteger(value) && value >= 0, 'invalid monotonic time');
        return value;
    }
    function assertIdentity(identity) {
        requireValue(identity.boot === config.bootId && identity.pid === config.targetPid &&
            identity.start === String(config.targetStartTime), 'OEM identity changed');
    }
    function responsePath() { return config.runtimeDirectory + '/' + role + '-residency-response.json'; }
    function writeAtomic(value) {
        const destination = responsePath();
        const temporary = destination + '.tmp-' + config.instance;
        const file = new api.File(temporary, 'w');
        try { file.write(JSON.stringify(value) + '\n'); file.flush(); } finally { file.close(); }
        if (rename === null) {
            rename = new api.NativeFunction(api.Module.getGlobalExportByName('rename'), 'int', ['pointer', 'pointer']);
        }
        requireValue(rename(api.Memory.allocUtf8String(temporary), api.Memory.allocUtf8String(destination)) === 0,
            'atomic response replacement failed');
    }
    function dispose(reason) {
        if (disposing) return;
        disposing = true;
        if (timer !== null) { api.clearInterval(timer); timer = null; }
        pending = null;
        if (config === null) return;
        const tombstone = { ...config, nonce: completedNonce, disposing: true, samples: [],
            reason: String(reason).slice(0, 128) };
        try { writeAtomic(tombstone); } catch (_) {
            // If rename fails, invalidate the old response directly rather than leave an
            // apparently live proof. Readers reject truncated/partial JSON as well.
            try {
                const file = new api.File(responsePath(), 'w');
                try { file.write(JSON.stringify(tombstone) + '\n'); file.flush(); } finally { file.close(); }
            } catch (_) {}
        }
    }
    function poll() {
        if (disposing || config === null) return;
        let now;
        try {
            assertIdentity(readIdentity());
            now = elapsedMs();
            requireValue(now >= lastElapsed, 'monotonic clock regressed');
            lastElapsed = now;
        } catch (error) { dispose(error); return; }
        let challenge;
        try {
            challenge = JSON.parse(readText(config.runtimeDirectory + '/' + role + '-residency-challenge.json', 2048));
        } catch (_) { pending = null; return; }
        if (!challenge || challenge.schema !== 1 || challenge.instance !== config.instance ||
            !isUuid(challenge.nonce) || !Number.isSafeInteger(challenge.issuedElapsedMs) ||
            challenge.issuedElapsedMs < 0 || now < challenge.issuedElapsedMs ||
            now - challenge.issuedElapsedMs > 3000 || challenge.nonce === completedNonce ||
            challenge.issuedElapsedMs <= completedIssuedElapsedMs) {
            pending = null;
            return;
        }
        if (pending === null || pending.nonce !== challenge.nonce || pending.issued !== challenge.issuedElapsedMs) {
            pending = { nonce: challenge.nonce, issued: challenge.issuedElapsedMs, samples: [] };
        }
        const samples = pending.samples;
        if (samples.length && now - samples[0].elapsedMs < 100) return;
        if (samples.length < 2) {
            let state;
            try {
                const encoded = JSON.stringify(readState());
                requireValue(typeof encoded === 'string' && encoded.length <= 8192, 'invalid state snapshot');
                state = JSON.parse(encoded);
            } catch (error) { dispose(error); return; }
            const wallMs = api.Date.now();
            if (!Number.isSafeInteger(wallMs) || wallMs < 0) { dispose('invalid wall time'); return; }
            samples.push({ sequence: ++sequence, elapsedMs: now, wallMs, state });
        }
        if (disposing || samples.length !== 2) return;
        try {
            writeAtomic({ ...config, nonce: challenge.nonce, disposing: false, samples });
            completedNonce = challenge.nonce;
            completedIssuedElapsedMs = challenge.issuedElapsedMs;
            pending = null;
        } catch (_) {
            // Retry from two fresh samples, never reuse a pair whose publication failed.
            pending = null;
        }
    }
    function init(input) {
        requireValue(!disposing, 'peer disposed');
        requireValue(input && typeof input === 'object' && JSON.stringify(input).length <= 4096, 'invalid config');
        const candidate = JSON.parse(JSON.stringify(input));
        const key = JSON.stringify(Object.keys(candidate).sort().map(name => [name, candidate[name]]));
        if (config !== null) {
            requireValue(key === configKey, 'peer already initialized for another instance');
            return;
        }
        requireValue((role === 'native' || role === 'java') && candidate.schema === 1 && candidate.role === role,
            'unexpected role or protocol');
        requireValue(isUuid(candidate.bootId) && isUuid(candidate.ticket) &&
            isUuid(candidate.instance) && Number.isSafeInteger(candidate.targetPid) &&
            candidate.targetPid > 0 && candidate.targetPid <= 2147483647 &&
            ((Number.isSafeInteger(candidate.targetStartTime) && candidate.targetStartTime > 0) ||
                (typeof candidate.targetStartTime === 'string' && /^[1-9][0-9]{0,15}$/.test(candidate.targetStartTime))),
            'invalid target metadata');
        requireValue(typeof candidate.revision === 'string' && /^[1-9][0-9]{0,9}-[a-f0-9]{12}-[a-f0-9]{12}$/.test(candidate.revision) &&
            typeof candidate.scriptSha256 === 'string' && /^[a-f0-9]{64}$/.test(candidate.scriptSha256), 'invalid payload identity');
        requireValue(candidate.payloadDirectory === '/data/local/tmp/impulse-carplay/versions/' + candidate.revision &&
            bridgePath === candidate.payloadDirectory + '/libcarplay_video_bridge.so' &&
            candidate.runtimeDirectory === '/data/local/tmp/impulse-carplay/runtime/' + candidate.bootId + '-' +
                candidate.targetPid + '-' + candidate.targetStartTime, 'unexpected runtime or payload path');
        const actual = readIdentity();
        requireValue(actual.boot === candidate.bootId && actual.pid === candidate.targetPid &&
            actual.start === String(candidate.targetStartTime), 'target does not match this process');
        const scriptPath = role === 'native' ? candidate.runtimeDirectory + '/cold-launcher.js' :
            candidate.payloadDirectory + '/endpoint.js';
        const file = new api.File(scriptPath, 'rb');
        try {
            const bytes = file.readBytes(2_097_153);
            requireValue(bytes.byteLength > 0 && bytes.byteLength <= 2_097_152 &&
                api.Checksum.compute('sha256', bytes) === candidate.scriptSha256, 'script identity mismatch');
        } finally { file.close(); }
        requireValue(!disposing, 'peer disposed during initialization');
        config = Object.freeze(candidate);
        configKey = key;
        timer = api.setInterval(poll, 250);
        poll();
    }
    return { init, dispose };
}

if (typeof module !== 'undefined' && module.exports) module.exports = { createImpulseResidencyPeer };
