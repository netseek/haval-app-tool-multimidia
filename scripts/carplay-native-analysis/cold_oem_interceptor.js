'use strict';

function createColdOemInterceptor(api) {
    const { Process, Module, File, Memory, Checksum, NativeFunction, Interceptor, Script } = api;
    const profileDigest = '31b878ba35c1a7fec07d7e854264837ef3702962a1e059fa5d020afa0d7c4d0f';
    const video = ['initialize', 'finalize', 'dimensions', 'start', 'stop', 'avcc', 'data'];
    const labels = video.concat(['session', 'displays']);
    let phase = 'idle';
    let observer = null;
    let timer = null;
    let pinned = false;
    let changed = [];
    let failure = null;

    function canonical(value, depth = 0) {
        if (depth > 10) throw new Error('Profile nesting');
        if (Array.isArray(value)) return '[' + value.map(item => canonical(item, depth + 1)).join(',') + ']';
        if (value && typeof value === 'object') return '{' + Object.keys(value).sort()
            .map(key => JSON.stringify(key) + ':' + canonical(value[key], depth + 1)).join(',') + '}';
        return JSON.stringify(value);
    }
    function reviewed(profile) {
        if (Process.arch !== 'arm64' || Process.pointerSize !== 8 || profile.version !== 3 ||
            profile.kind !== 'oem-cold-interception-v1' || !Array.isArray(profile.modules) || profile.modules.length !== 3) {
            throw new Error('Unsupported interception profile');
        }
        const encoded = canonical(profile);
        if (encoded.length > 16384 || Checksum.compute('sha256', encoded) !== profileDigest) throw new Error('Unreviewed profile');
        return JSON.parse(encoded);
    }
    function fileBytes(path, size, digest) {
        if (!Number.isSafeInteger(size) || size < 64 || size > 67108864 || !/^[a-f0-9]{64}$/.test(digest)) {
            throw new Error('Invalid image bounds');
        }
        const file = new File(path, 'rb');
        try {
            file.seek(0, File.SEEK_END);
            if (file.tell() !== size) throw new Error('Image size mismatch');
            file.seek(0, File.SEEK_SET);
            const bytes = file.readBytes(size);
            if (bytes.byteLength !== size || Checksum.compute('sha256', bytes) !== digest) throw new Error('File hash mismatch');
            return bytes;
        } finally { file.close(); }
    }
    function readable(module, offset, size, executable) {
        if (!Number.isSafeInteger(offset) || !Number.isSafeInteger(size) || offset < 0 || size < 1 ||
            offset + size > module.size) throw new Error('Mapped image bounds');
        const start = module.base.add(offset);
        const end = start.add(size);
        let position = start;
        while (position.compare(end) < 0) {
            const range = Process.findRangeByAddress(position);
            if (!range || !range.protection.includes('r') || range.protection.includes('w') ||
                (executable && !range.protection.includes('x')) || !range.file || range.file.path !== module.path) {
                throw new Error('Unexpected mapped image protection/provenance');
            }
            const next = range.base.add(range.size);
            if (next.compare(position) <= 0) throw new Error('Invalid mapped range');
            position = next;
        }
        return start;
    }
    function unique(name, path) {
        const modules = Process.enumerateModules().filter(module => module.name === name);
        if (modules.length !== 1 || modules[0].path !== path) throw new Error('Missing/duplicate/wrong-path image');
        return modules[0];
    }
    function attest(profile) {
        const addresses = {};
        for (const expected of profile.modules) {
            const module = unique(expected.name, expected.path);
            fileBytes(expected.path, expected.fileSize, expected.sha256);
            const note = readable(module, expected.note.offset, expected.note.hex.length / 2, false);
            const observed = Array.from(new Uint8Array(note.readByteArray(expected.note.hex.length / 2)))
                .map(value => value.toString(16).padStart(2, '0')).join('');
            if (observed !== expected.note.hex) throw new Error('Mapped build identity mismatch');
            for (const segment of expected.executable) {
                const address = readable(module, segment.offset, segment.size, true);
                if (Checksum.compute('sha256', address.readByteArray(segment.size)) !== segment.sha256) {
                    throw new Error('Executable image changed');
                }
            }
            for (const [label, target] of Object.entries(expected.functions)) {
                const address = readable(module, target.offset, target.size, true);
                if (addresses[label] || Checksum.compute('sha256', address.readByteArray(target.size)) !== target.sha256) {
                    throw new Error('Function body changed');
                }
                addresses[label] = address;
            }
        }
        return addresses;
    }
    function bridgeImage(module, bytes) {
        const view = new DataView(bytes);
        if (view.getUint32(0, true) !== 0x464c457f || view.getUint8(4) !== 2 || view.getUint8(5) !== 1 ||
            view.getUint16(16, true) !== 3 || view.getUint16(18, true) !== 183 || view.getUint16(54, true) !== 56) {
            throw new Error('Unsupported bridge ELF');
        }
        const offset = Number(view.getBigUint64(32, true));
        const count = view.getUint16(56, true);
        if (!Number.isSafeInteger(offset) || offset < 64 || count < 1 || count > 128 || offset + count * 56 > bytes.byteLength) {
            throw new Error('Bridge program headers');
        }
        let executable = 0;
        for (let index = 0; index < count; index++) {
            const header = offset + index * 56;
            if (view.getUint32(header, true) !== 1 || !(view.getUint32(header + 4, true) & 1)) continue;
            const flags = view.getUint32(header + 4, true);
            const fileOffset = Number(view.getBigUint64(header + 8, true));
            const virtualOffset = Number(view.getBigUint64(header + 16, true));
            const size = Number(view.getBigUint64(header + 32, true));
            if (flags !== 5 || !Number.isSafeInteger(fileOffset) || !Number.isSafeInteger(size) || size < 1 ||
                fileOffset + size > bytes.byteLength) throw new Error('Unsupported bridge segment');
            const address = readable(module, virtualOffset, size, true);
            if (Checksum.compute('sha256', bytes.slice(fileOffset, fileOffset + size)) !==
                Checksum.compute('sha256', address.readByteArray(size))) throw new Error('Bridge memory changed');
            executable++;
        }
        if (!executable) throw new Error('Bridge executable image missing');
    }
    function releaseObserver() {
        if (timer !== null) { api.clearTimeout(timer); timer = null; }
        if (observer) { observer.detach(); observer = null; }
    }
    function unpin() { if (pinned) { Script.unpin(); pinned = false; } }
    function status() { return { phase, replacements: changed.length, pinned, failure }; }

    function arm(input, options) {
        if (!options || options.enabled !== true) return { phase: 'disabled' };
        if (phase !== 'idle') throw new Error('Cold installer already consumed');
        options = Object.freeze({ enabled: true, principalUuid: options.principalUuid, bridgePath: options.bridgePath,
            bridgeSize: options.bridgeSize, bridgeSha256: options.bridgeSha256, expectedPid: options.expectedPid });
        const profile = reviewed(input);
        if (!Number.isSafeInteger(options.expectedPid) || options.expectedPid <= 0 || Process.id !== options.expectedPid) {
            throw new Error('Wrong target process ID');
        }
        const command = new File('/proc/self/cmdline', 'rb');
        try {
            const bytes = new Uint8Array(command.readBytes(256));
            const end = bytes.indexOf(0);
            if (end < 1 || end > 64 || String.fromCharCode(...bytes.slice(0, end)) !== 'com.ts.carplay') {
                throw new Error('Wrong OEM process identity');
            }
        } finally { command.close(); }
        if (typeof options.principalUuid !== 'string' || typeof options.bridgePath !== 'string' ||
            !/^[A-Za-z0-9._:-]{1,128}$/.test(options.principalUuid || '') ||
            !/^\/data\/local\/tmp\/impulse-carplay\/[A-Za-z0-9._/-]+\/libcarplay_video_bridge\.so$/.test(options.bridgePath || '') ||
            options.bridgePath.split('/').includes('..')) throw new Error('Invalid explicit bridge settings');
        if (typeof Process.attachModuleObserver !== 'function' || typeof Interceptor.replaceFast !== 'function' ||
            typeof Script.pin !== 'function' || typeof Script.unpin !== 'function') throw new Error('Unsupported Frida runtime');
        const absent = () => profile.modules.every(expected => !Process.enumerateModules().some(module => module.name === expected.name));
        if (!absent()) throw new Error('Existing OEM modules: warm interception forbidden');
        if (Process.enumerateModules().some(module => module.name === 'libcarplay_video_bridge.so')) throw new Error('Bridge already loaded');
        const bytes = fileBytes(options.bridgePath, options.bridgeSize, options.bridgeSha256);
        const bridge = Module.load(options.bridgePath);
        unique(bridge.name, options.bridgePath);
        bridgeImage(bridge, bytes);
        fileBytes(options.bridgePath, options.bridgeSize, options.bridgeSha256);
        if (!absent()) throw new Error('OEM load raced bridge preparation');
        const exported = name => {
            const address = bridge.getExportByName(name);
            if (address.compare(bridge.base) < 0 || address.compare(bridge.base.add(bridge.size)) >= 0) throw new Error('Foreign bridge export');
            const range = Process.findRangeByAddress(address);
            if (!range || !range.protection.includes('x')) throw new Error('Non-executable bridge export');
            return address;
        };
        const nativeOptions = { scheduling: 'exclusive', exceptions: 'propagate' };
        const abi = new NativeFunction(exported('impulse_carplay_initial_bootstrap_abi'), 'uint32', [], nativeOptions);
        const ready = new NativeFunction(exported('impulse_carplay_initial_bootstrap_state'), 'uint32', [], nativeOptions);
        const prepare = new NativeFunction(exported('impulse_carplay_prepare_initial_entries'), 'int', [], nativeOptions);
        const release = new NativeFunction(exported('impulse_carplay_release_initial_preparation'), 'int', [], nativeOptions);
        const bootstrap = new NativeFunction(exported('impulse_carplay_bootstrap_initial_entries'), 'int',
            ['pointer', 'pointer', 'pointer', 'pointer', 'pointer', 'pointer', 'pointer', 'pointer', 'uint64'], nativeOptions);
        if (abi() !== 1 || ready() !== 0) throw new Error('Bridge ABI/state mismatch');
        const replacement = Object.fromEntries(labels.map(label => [label, exported('impulse_carplay_entry_' + label)]));
        const table = Memory.alloc(56);
        const uuid = Memory.allocUtf8String(options.principalUuid);
        let registering = true;
        let prepared = false;
        const seen = new Set();
        function fail(error, uncertain = false) {
            failure = String(error.message || error);
            releaseObserver();
            if (uncertain) { phase = 'failed-pinned'; return; }
            let restored = true;
            for (const target of changed.slice().reverse()) {
                try { Interceptor.revert(target); } catch (_) { restored = false; }
            }
            try { Interceptor.flush(); } catch (_) { restored = false; }
            if (restored && changed.length) {
                try { attest(profile); } catch (_) { restored = false; }
            }
            if (restored && prepared) {
                try { if (release() !== 0) restored = false; else prepared = false; } catch (_) { restored = false; }
            }
            if (restored) { changed = []; phase = 'failed'; unpin(); }
            else { phase = 'failed-pinned'; }
        }
        function install() {
            phase = 'installing';
            let attempted = false;
            try {
                attest(profile);
                if (ready() !== 0) throw new Error('Native bridge already pinned');
                if (prepare() !== 0) throw new Error('Native module pinning failed');
                prepared = true;
                const addresses = attest(profile);
                const originals = {};
                for (const label of labels) {
                    const target = addresses[label];
                    changed.push(target);
                    const original = Interceptor.replaceFast(target, replacement[label]);
                    const range = Process.findRangeByAddress(original);
                    if (original.isNull() || original.equals(target) || !range || !range.protection.includes('x') ||
                        Object.values(originals).some(value => value.equals(original))) throw new Error('Invalid original trampoline');
                    originals[label] = original;
                }
                video.forEach((label, index) => table.add(index * 8).writePointer(originals[label]));
                Interceptor.flush();
                attempted = true;
                const result = bootstrap(table, originals.session, originals.displays, addresses.getLength,
                    addresses.copyString, addresses.retain, addresses.release, uuid, options.principalUuid.length);
                if (result !== 0 || ready() !== 1) throw new Error('Native bootstrap failed');
                phase = 'installed-pinned';
                releaseObserver();
            } catch (error) {
                let uncertain = false;
                if (attempted) { try { uncertain = ready() !== 0; } catch (_) { uncertain = true; } }
                fail(error, uncertain);
            }
        }
        Script.pin(); pinned = true; phase = 'armed';
        try {
            observer = Process.attachModuleObserver({
                onAdded(module) {
                    if (phase !== 'armed' || !profile.modules.some(expected => expected.name === module.name)) return;
                    if (registering) { fail(new Error('OEM present during observer registration')); return; }
                    seen.add(module.name);
                    if (seen.size === profile.modules.length) install();
                },
                onRemoved(module) {
                    if (phase === 'armed' && profile.modules.some(expected => expected.name === module.name)) {
                        fail(new Error('OEM image unloaded during cold preparation'));
                    }
                }
            });
            registering = false;
            if (phase !== 'armed') { releaseObserver(); return status(); }
            timer = api.setTimeout(() => { if (phase === 'armed') fail(new Error('Cold load timeout')); }, 60000);
            return status();
        } catch (error) { registering = false; fail(error); return status(); }
    }
    return {
        arm,
        inspect(input) { const profile = reviewed(input); return { verified: Object.keys(attest(profile)).length, profileDigest }; },
        status,
        cancel() {
            if (phase !== 'armed') throw new Error('Only unmodified cold preparation can be cancelled');
            releaseObserver(); phase = 'cancelled'; unpin(); return status();
        }
    };
}

if (typeof module !== 'undefined' && module.exports) module.exports = { createColdOemInterceptor };
else rpc.exports = createColdOemInterceptor({ Process, Module, File, Memory, Checksum, NativeFunction,
    Interceptor, Script, setTimeout, clearTimeout });
