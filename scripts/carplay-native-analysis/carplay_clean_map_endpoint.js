'use strict';

// Persistent OEM-process control plane for the explicitly requested clean-map
// experiment. It carries no route/frame payloads and never starts CarPlay.

const state = {
    armed: false,
    requested: false,
    binding: false,
    app: null,
    endpoint: null,
    connection: null,
    receiver: null,
    token: null,
    bridge: null,
    endpointClass: null,
    bridgeStub: null,
    appUid: -1,
    generation: 0,
    timer: null
};

function requireValue(condition, message) {
    if (!condition) throw new Error(message);
}

function clearBytes(bytes) {
    if (!bytes) return;
    try {
        for (let index = 0; index < bytes.length; index++) bytes[index] = 0;
    } catch (_) {}
}

function retire() {
    state.requested = false;
    if (state.timer !== null) {
        clearTimeout(state.timer);
        state.timer = null;
    }
    if (state.endpoint !== null) {
        try { state.endpoint.close(); } catch (_) {}
        state.endpoint = null;
    }
    if (state.connection !== null && state.app !== null) {
        try { state.app.unbindService(state.connection); } catch (_) {}
    }
    state.connection = null;
    state.binding = false;
    clearBytes(state.token);
    state.token = null;
    state.generation = 0;
}

function scheduleAttempt() {
    if (!state.requested || state.timer !== null) return;
    state.timer = setTimeout(() => {
        state.timer = null;
        Java.perform(attemptOffer);
    }, 250);
}

function attemptOffer() {
    if (!state.requested || state.binding || state.endpoint !== null) return;
    const epoch = Number(state.bridge.epoch());
    if (!Number.isSafeInteger(epoch) || epoch <= 0) {
        scheduleAttempt();
        return;
    }
    const ByteArray = Java.use('[B');
    const SecureRandom = Java.use('java.security.SecureRandom');
    const Intent = Java.use('android.content.Intent');
    const ServiceConnection = Java.use('android.content.ServiceConnection');
    const token = ByteArray.$new(32);
    SecureRandom.$new().nextBytes(token);
    const endpoint = state.endpointClass.$new(state.appUid, token);
    const generation = epoch;
    const connection = Java.registerClass({
        name: 'com.ts.carplay.ImpulseCleanMapConnection' + String(Date.now()),
        implements: [ServiceConnection],
        methods: {
            onServiceConnected(name, binder) {
                try {
                    if (!state.requested || state.connection !== this) return;
                    const remote = state.bridgeStub.asInterface(binder);
                    requireValue(remote !== null, 'Impulse bridge returned null interface');
                    remote.offerEndpoint(endpoint, token, generation);
                    state.endpoint = endpoint;
                    state.generation = generation;
                    clearBytes(token);
                    state.token = null;
                    send({ type: 'clean-map-endpoint', state: 'offered', generation: generation });
                } catch (error) {
                    send({ type: 'clean-map-endpoint', state: 'offer-failed', error: String(error) });
                    retire();
                }
            },
            onServiceDisconnected(name) { retire(); },
            onBindingDied(name) { retire(); },
            onNullBinding(name) { retire(); }
        }
    }).$new();
    state.binding = true;
    state.connection = connection;
    state.token = token;
    const intent = Intent.$new();
    intent.setClassName('br.com.redesurftank.havalshisuku',
        'br.com.redesurftank.havalshisuku.services.CarPlayClusterBridgeService');
    if (!state.app.bindService(intent, connection, 1)) {
        retire();
        send({ type: 'clean-map-endpoint', state: 'bind-rejected' });
    }
}

rpc.exports = {
    arm(options) {
        if (state.armed) throw new Error('Clean-map endpoint already armed');
        requireValue(options && typeof options.bridgePath === 'string', 'Exact bridge path required');
        return new Promise((resolve, reject) => Java.perform(() => {
            try {
                const ActivityThread = Java.use('android.app.ActivityThread');
                const DexClassLoader = Java.use('dalvik.system.DexClassLoader');
                const IntentFilter = Java.use('android.content.IntentFilter');
                const BroadcastReceiver = Java.use('android.content.BroadcastReceiver');
                const app = ActivityThread.currentApplication();
                requireValue(app !== null, 'OEM Application unavailable');
                const packageManager = app.getPackageManager();
                const impulse = packageManager.getApplicationInfo('br.com.redesurftank.havalshisuku', 0);
                const loader = DexClassLoader.$new(impulse.sourceDir.value,
                    app.getCodeCacheDir().getAbsolutePath(), null, app.getClassLoader());
                const factory = Java.ClassFactory.get(loader);
                const Endpoint = factory.use(
                    'br.com.redesurftank.havalshisuku.experimental.CarPlaySurfaceEndpoint');
                const BridgeStub = factory.use(
                    'br.com.redesurftank.havalshisuku.services.ICarPlayClusterBridge$Stub');
                const module = Process.getModuleByName('libcarplay_video_bridge.so');
                requireValue(module.path === options.bridgePath, 'Unexpected bridge image');
                const register = new NativeFunction(
                    module.getExportByName('impulse_carplay_register_surface_endpoint'), 'int', ['pointer', 'pointer']);
                const epoch = new NativeFunction(
                    module.getExportByName('impulse_carplay_entry_principal_epoch'), 'uint64', []);
                const environment = Java.vm.getEnv();
                requireValue(register(environment.handle, Endpoint.class.$h) === 0,
                    'RegisterNatives failed');

                const Receiver = Java.registerClass({
                    name: 'com.ts.carplay.ImpulseCleanMapReceiver',
                    superClass: BroadcastReceiver,
                    methods: {
                        onReceive(context, intent) {
                            const action = String(intent.getAction());
                            if (action === 'br.com.redesurftank.havalshisuku.CARPLAY_CLEAN_MAP_REQUEST') {
                                state.requested = true;
                                attemptOffer();
                            } else if (action === 'br.com.redesurftank.havalshisuku.CARPLAY_CLEAN_MAP_REVOKE') {
                                retire();
                            }
                        }
                    }
                });
                const filter = IntentFilter.$new();
                filter.addAction('br.com.redesurftank.havalshisuku.CARPLAY_CLEAN_MAP_REQUEST');
                filter.addAction('br.com.redesurftank.havalshisuku.CARPLAY_CLEAN_MAP_REVOKE');
                const receiver = Receiver.$new();
                app.registerReceiver(receiver, filter);
                state.app = app;
                state.receiver = receiver;
                state.endpointClass = Endpoint;
                state.bridgeStub = BridgeStub;
                state.bridge = { epoch };
                state.appUid = impulse.uid.value;
                state.armed = true;
                resolve('armed');
            } catch (error) {
                reject(error);
            }
        }));
    },
    state() {
        return {
            armed: state.armed,
            requested: state.requested,
            binding: state.binding,
            offered: state.endpoint !== null,
            generation: state.generation
        };
    }
};
