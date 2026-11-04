'use strict';

function runAndroidIsolatedSmoke(settings) {
    const requireCheck = (value, message) => { if (!value) throw new Error(message); };
    requireCheck(Process.arch === 'arm64' && Process.pointerSize === 8 && Process.id === settings.expectedPid,
        'Wrong isolated target PID/architecture');
    const command = new File('/proc/self/cmdline', 'rb');
    let name;
    try {
        const bytes = new Uint8Array(command.readBytes(128));
        const end = bytes.indexOf(0);
        requireCheck(end > 0 && end < 64, 'Invalid isolated command');
        name = String.fromCharCode(...bytes.slice(0, end));
    } finally { command.close(); }
    requireCheck(name === '/system/bin/sleep' || name === 'sleep', 'Only a disposable sleep process is permitted');
    const oemPresent = () => Process.enumerateModules().some(module =>
        ['libcarplaystack.so', 'libAirPlay_ts.so', 'libCoreUtils_ts.so'].includes(module.name));
    requireCheck(!oemPresent(), 'OEM modules must not be present in smoke process');
    requireCheck(/^\/data\/local\/tmp\/impulse-validation\/[A-Za-z0-9_-]+\/libcarplay_video_bridge\.so$/.test(settings.bridgePath),
        'Unexpected isolated staging path');
    const payload = File.readAllBytes(settings.bridgePath);
    requireCheck(payload.byteLength === settings.bridgeSize && Checksum.compute('sha256', payload) === settings.bridgeSha256,
        'Isolated bridge file mismatch');
    const bridge = Module.load(settings.bridgePath);
    requireCheck(bridge.path === settings.bridgePath, 'Unexpected loaded bridge');
    const native = (symbol, result, argumentsList) => new NativeFunction(bridge.getExportByName(symbol), result, argumentsList);
    const abi = native('impulse_carplay_initial_bootstrap_abi', 'uint32', []);
    const state = native('impulse_carplay_initial_bootstrap_state', 'uint32', []);
    const prepare = native('impulse_carplay_prepare_initial_entries', 'int', []);
    const release = native('impulse_carplay_release_initial_preparation', 'int', []);
    requireCheck(abi() === 1 && state() === 0, 'Bridge ABI/state invalid');
    requireCheck(prepare() !== 0 && release() === 0 && state() === 0 && !oemPresent(),
        'Missing OEM images must fail closed without loading them');
    const bootstrap = native('impulse_carplay_bootstrap_initial_entries', 'int',
        ['pointer', 'pointer', 'pointer', 'pointer', 'pointer', 'pointer', 'pointer', 'pointer', 'uint64']);
    requireCheck(bootstrap(ptr(0), ptr(0), ptr(0), ptr(0), ptr(0), ptr(0), ptr(0), ptr(0), 0) !== 0,
        'Invalid bootstrap should reject');
    let observedModules = 0;
    const observer = Process.attachModuleObserver({ onAdded() { observedModules++; } });
    observer.detach();
    requireCheck(observedModules > 0, 'Module observer unavailable');
    const code = Memory.alloc(Process.pageSize);
    Memory.patchCode(code, 128, writable => {
        const writer = new Arm64Writer(writable, { pc: code });
        writer.putLdrRegU64('x0', 1);
        for (let index = 0; index < 8; index++) writer.putNop();
        writer.putRet();
        writer.flush();
    });
    requireCheck(Memory.protect(code, Process.pageSize, 'r-x'), 'Executable test memory denied');
    const invoke = new NativeFunction(code, 'int', []);
    requireCheck(invoke() === 1, 'Synthetic ARM64 function failed');
    const replacement = new NativeCallback(() => 2, 'int', []);
    let attempted = false;
    try {
        attempted = true;
        const original = Interceptor.replaceFast(code, replacement);
        Interceptor.flush();
        const invokeOriginal = new NativeFunction(original, 'int', []);
        requireCheck(invoke() === 2 && invokeOriginal() === 1, 'ARM64 fast replacement/trampoline failed');
    } finally {
        if (attempted) { Interceptor.revert(code); Interceptor.flush(); }
    }
    requireCheck(invoke() === 1 && state() === 0 && !oemPresent(), 'Isolated restoration failed');
    return { passed: true, pid: Process.id, fridaVersion: Frida.version, architecture: Process.arch,
        bridgeAbi: abi(), bridgeSha256: settings.bridgeSha256, moduleObserver: true,
        missingOemRejected: true, invalidBootstrapRejected: true, arm64ReplacementAndRevert: true,
        nativeBootstrapInstalled: false, oemModulesLoaded: false };
}
