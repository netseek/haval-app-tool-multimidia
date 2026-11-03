'use strict';

let active = false;
let timer = null;
let listeners = [];
let scopes = new Map();
let streams = new Map();
let records = 0;
let maximumRecords = 0;
let sequence = 0;
const expectedProfileDigest = '4e032c87870602a0810530e583c677d201d17cc4b2a15c82477af94a5a8037b6';

function canonical(value, depth = 0) {
    if (depth > 8) throw new Error('Profile nesting limit');
    if (Array.isArray(value)) return '[' + value.map(item => canonical(item, depth + 1)).join(',') + ']';
    if (value && typeof value === 'object') {
        return '{' + Object.keys(value).sort().map(key => JSON.stringify(key) + ':' + canonical(value[key], depth + 1)).join(',') + '}';
    }
    return JSON.stringify(value);
}

function stop() {
    active = false;
    if (timer !== null) {
        clearTimeout(timer);
        timer = null;
    }
    for (const listener of listeners) {
        listener.detach();
    }
    listeners = [];
    scopes.clear();
    streams.clear();
    return { records: records, stopped: true };
}

function emit(event) {
    if (!active) return;
    if (records >= maximumRecords) {
        active = false;
        setImmediate(stop);
        return;
    }
    records += 1;
    send(Object.assign({ eventSequence: records, observedAtMs: Date.now() }, event));
}

function hexAt(address, length) {
    return Array.from(new Uint8Array(address.readByteArray(length)))
        .map(value => value.toString(16).padStart(2, '0')).join('');
}

function start(profile, verifiedFileHashes, options) {
    if (active || listeners.length) throw new Error('Observer already active');
    if (Process.arch !== 'arm64' || Process.pointerSize !== 8 || profile.version !== 1 ||
        profile.architecture !== 'arm64' || !Array.isArray(profile.modules) || profile.modules.length !== 3) {
        throw new Error('Unsupported observer profile');
    }
    const profileBytes = canonical(profile);
    if (profileBytes.length > 8192 || Checksum.compute('sha256', profileBytes) !== expectedProfileDigest) {
        throw new Error('Unreviewed symbol profile');
    }
    const duration = options && options.durationMs !== undefined ? options.durationMs : 60000;
    const budget = options && options.maximumRecords !== undefined ? options.maximumRecords : 1000;
    if (!Number.isInteger(duration) || duration < 1000 || duration > 300000 ||
        !Number.isInteger(budget) || budget < 1 || budget > 3000) throw new Error('Invalid observer bounds');
    const addresses = {};
    const names = new Set();
    for (const expected of profile.modules) {
        if (names.has(expected.name) || !verifiedFileHashes || verifiedFileHashes[expected.name] !== expected.sha256) {
            throw new Error('Fresh file-hash attestation required');
        }
        names.add(expected.name);
        const module = Process.getModuleByName(expected.name);
        if (module.path !== expected.path || expected.note.hex.length > 256 || expected.note.hex.length % 2) {
            throw new Error('Module provenance mismatch');
        }
        if (hexAt(module.base.add(expected.note.offset), expected.note.hex.length / 2) !== expected.note.hex) {
            throw new Error('Module build identity mismatch');
        }
        for (const [label, target] of Object.entries(expected.functions)) {
            if (addresses[label] || target.signature.length !== 32 || !Number.isSafeInteger(target.offset) ||
                target.offset < 0 || target.offset + 16 > module.size) throw new Error('Invalid symbol profile');
            const address = module.base.add(target.offset);
            if (hexAt(address, 16) !== target.signature) throw new Error('Code signature mismatch');
            addresses[label] = address;
        }
    }
    for (const label of ['session', 'initialize', 'start', 'data', 'finalize', 'getLength', 'copyString']) {
        if (!addresses[label]) throw new Error('Incomplete symbol profile');
    }
    const length = new NativeFunction(addresses.getLength, 'int', ['pointer']);
    const copy = new NativeFunction(addresses.copyString, 'uint8', ['pointer', 'pointer', 'int', 'uint32']);
    function uuidDigest(value) {
        if (value.isNull()) return null;
        const size = length(value);
        if (size < 1 || size > 128) return null;
        const buffer = Memory.alloc(129);
        if (!copy(value, buffer, 129, 0x08000100)) return null;
        const text = buffer.readUtf8String(size);
        if (text.length !== size || !/^[A-Za-z0-9._:-]+$/.test(text)) return null;
        return Checksum.compute('sha256', buffer.readByteArray(size));
    }
    function attach(address, callbacks) { listeners.push(Interceptor.attach(address, callbacks)); }
    try {
        records = 0;
        sequence = 0;
        attach(addresses.session, {
            onEnter(args) {
                if (!active) return;
                this.thread = this.threadId;
                let stack = scopes.get(this.thread);
                if (!stack) {
                    if (scopes.size >= 32) { emit({ event: 'scope_limit' }); setImmediate(stop); return; }
                    stack = [];
                    scopes.set(this.thread, stack);
                }
                if (stack.length >= 16) { emit({ event: 'nesting_limit' }); setImmediate(stop); return; }
                this.scope = { sessionSequence: ++sequence, type: args[2].toUInt32(), uuidDigest: null };
                try { this.scope.uuidDigest = uuidDigest(args[1]); }
                catch (error) { emit({ event: 'identity_read_failed' }); }
                stack.push(this.scope);
                emit(Object.assign({ event: 'session_enter', thread: this.thread }, this.scope));
            },
            onLeave(result) {
                if (!active || !this.scope) return;
                const stack = scopes.get(this.thread);
                if (!stack || stack[stack.length - 1] !== this.scope) {
                    emit({ event: 'scope_order_failed' }); setImmediate(stop); return;
                }
                stack.pop();
                if (!stack.length) scopes.delete(this.thread);
                emit({ event: 'session_leave', sessionSequence: this.scope.sessionSequence, result: result.toInt32() });
            }
        });
        attach(addresses.initialize, {
            onEnter(args) {
                if (!active) return;
                const key = args[0].toString();
                const stack = scopes.get(this.threadId);
                const scope = stack && stack[stack.length - 1];
                if (streams.size >= 32 || streams.has(key)) {
                    emit({ event: 'stream_limit_or_reuse' }); setImmediate(stop); return;
                }
                const state = { streamSequence: ++sequence, sessionSequence: scope ? scope.sessionSequence : null, frames: 0 };
                streams.set(key, state);
                emit(Object.assign({ event: 'initialize', thread: this.threadId }, state));
            }
        });
        attach(addresses.start, {
            onEnter(args) {
                if (!active) return;
                const stream = streams.get(args[0].toString());
                let digest = null;
                try { digest = uuidDigest(args[2]); } catch (error) { emit({ event: 'identity_read_failed' }); }
                emit({ event: 'stream_start', streamSequence: stream ? stream.streamSequence : null,
                    type: args[1].toUInt32(), uuidDigest: digest, thread: this.threadId });
            }
        });
        attach(addresses.data, {
            onEnter(args) {
                if (!active) return;
                const stream = streams.get(args[0].toString());
                if (!stream) return;
                stream.frames += 1;
                if (stream.frames === 1 || stream.frames % 30 === 0) {
                    emit({ event: 'frame_sample', streamSequence: stream.streamSequence, frames: stream.frames,
                        bytesHex: args[2].toString(), displayTicksHex: args[3].toString(), thread: this.threadId });
                }
            }
        });
        attach(addresses.finalize, {
            onEnter(args) {
                if (!active) return;
                const key = args[0].toString();
                const stream = streams.get(key);
                emit({ event: 'finalize', streamSequence: stream ? stream.streamSequence : null,
                    frames: stream ? stream.frames : 0 });
                streams.delete(key);
            }
        });
        active = true;
        maximumRecords = budget;
        timer = setTimeout(stop, duration);
        emit({ event: 'observer_started', durationMs: duration, maximumRecords: budget });
        return { active: true, replacesCallbacks: false, capturesPixels: false };
    } catch (error) {
        stop();
        throw error;
    }
}

rpc.exports = { start: start, stop: stop, status: () => ({ active: active, records: records }) };
