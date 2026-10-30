package br.com.redesurftank.havalshisuku.navigation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.SystemClock
import br.com.redesurftank.havalshisuku.BuildConfig
import br.com.redesurftank.havalshisuku.diagnostics.ClusterPersistentEventLogger
import br.com.redesurftank.havalshisuku.experimental.CarPlayCleanMapCoordinator
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

object CarPlayNavigationCapture {
    data class Status(
        val running: Boolean = false,
        val connected: Boolean = false,
        val message: String = "Desligado",
        val session: String = "",
        val callbacks: Long = 0,
        val errors: Long = 0,
        val dropped: Long = 0,
        val lastCallbackMs: Long = 0,
        val routeState: Int? = null,
        val maneuver: Int? = null,
        val directory: String = "",
        val summary: CarPlayCaptureSummary = CarPlayCaptureSummary(),
        val startedAtMs: Long = 0,
        val stoppedAtMs: Long = 0
    )

    @Volatile var status = Status()
        private set
    private var current: Capture? = null

    @Synchronized fun start(context: Context) {
        if (current != null) return
        val capture = Capture(context.applicationContext, MAX_DURATION_MS, false)
        current = capture
        status = Status(running = true, message = "Preparando captura", session = capture.id)
        capture.begin()
    }

    @Synchronized fun startFiveMinuteTest(context: Context) {
        if (current != null) return
        val capture = Capture(context.applicationContext, FIVE_MINUTE_TEST_MS, true)
        current = capture
        status = Status(running = true, message = "Preparando teste CarPlay de 5 minutos", session = capture.id)
        capture.begin()
    }

    @Synchronized fun stop() {
        current?.requestStop()
    }

    @Synchronized fun mark(label: String) {
        current?.mark(label)
    }

    @Synchronized private fun update(capture: Capture, value: Status) {
        if (current === capture) status = value
    }

    @Synchronized private fun finished(capture: Capture, value: Status) {
        if (current === capture) {
            status = value
            current = null
        }
    }

    private class Capture(
        private val context: Context,
        private val maxDurationMs: Long,
        private val enableHudImmediately: Boolean
    ) {
        val id: String = UUID.randomUUID().toString()
        private val thread = HandlerThread("carplay-guidance-capture").apply { start() }
        private val worker = Handler(thread.looper)
        private val gate = CarPlayCaptureGate()
        private var files: CarPlayCaptureFiles? = null
        private var view = Status(running = true, session = id)
        private var binding: Binding? = null
        private var generation = 0L
        private var startedAtMs = 0L
        private var nextBindMs = 0L
        private var failures = 0
        private var ended = false
        private var clusterCaptureLease: AutoCloseable? = null
        private val durationTimeout = Runnable {
            if (gate.isOpen() && !ended) terminate(
                if (maxDurationMs == FIVE_MINUTE_TEST_MS) "Teste encerrado automaticamente após 5 minutos"
                else "Captura encerrada após 60 minutos"
            )
        }
        private val tick = object : Runnable {
            override fun run() {
                if (!gate.isOpen() || ended) return
                val now = SystemClock.elapsedRealtime()
                if (now - startedAtMs >= maxDurationMs) {
                    durationTimeout.run()
                    return
                }
                val active = binding
                if (active != null && active.binder?.isBinderAlive == false) {
                    lost(active, "binder_dead")
                } else if (active != null && active.binder == null && now - active.requestedAtMs >= 15_000L) {
                    lost(active, "bind_timeout")
                }
                if (binding == null && now >= nextBindMs) bind()
                if (ended) return
                view = view.copy(dropped = gate.dropped)
                record("heartbeat", captureCounts(now)
                    .put("binderConnected", view.connected)
                    .put("callbackAgeMs", if (view.lastCallbackMs == 0L) JSONObject.NULL else now - view.lastCallbackMs))
                publish()
                worker.postDelayed(this, 10_000L)
            }
        }

        fun begin() {
            worker.post {
                try {
                    val directory = File(context.createDeviceProtectedStorageContext().noBackupFilesDir, "carplay-navigation")
                    files = CarPlayCaptureFiles(directory)
                    view = view.copy(directory = directory.absolutePath)
                    startedAtMs = SystemClock.elapsedRealtime()
                    view = view.copy(startedAtMs = startedAtMs)
                    if (enableHudImmediately) {
                        clusterCaptureLease = ClusterPersistentEventLogger.acquireCaptureLease("carplay-combined-$id")
                        ClusterPersistentEventLogger.log(
                            "carplay_combined_test_start",
                            mapOf("session" to id, "durationMs" to maxDurationMs, "versionCode" to BuildConfig.VERSION_CODE)
                        )
                        CarPlayCleanMapCoordinator.setExperimentEnabled(true, startedAtMs + maxDurationMs)
                    }
                    CarPlayExperimentalHud.start(context)
                    CarPlayExperimentalHud.begin(id, enableHudImmediately, startedAtMs + maxDurationMs)
                    if (!record("capture_start", JSONObject().put("versionCode", BuildConfig.VERSION_CODE)
                        .put("versionName", BuildConfig.VERSION_NAME)
                        .put("mode", if (enableHudImmediately) "five_minute_combined_test" else "diagnostic_only")
                        .put("automaticHud", enableHudImmediately)
                        .put("maximumDurationMs", maxDurationMs))) return@post
                    val checks = CarPlayGuidanceParcel.selfTest()
                    if (!record("parcel_selftest_pass", JSONObject().put("checks", checks))) return@post
                    for (packageName in listOf(SERVICE_PACKAGE, "com.ts.carplay.app")) {
                        val evidence = JSONObject().put("package", packageName)
                        runCatching {
                            val info = context.packageManager.getPackageInfo(packageName, 0)
                            val path = requireNotNull(info.applicationInfo).sourceDir
                            val digest = MessageDigest.getInstance("SHA-256")
                            File(path).inputStream().use { input ->
                                val buffer = ByteArray(65_536)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    digest.update(buffer, 0, count)
                                }
                            }
                            evidence.put("versionName", info.versionName).put("apkPath", path)
                                .put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
                        }.onFailure { evidence.put("error", it.javaClass.simpleName) }
                        if (!record("installed_package", evidence)) return@post
                    }
                    if (gate.isOpen()) {
                        worker.postDelayed(durationTimeout, maxDurationMs)
                        tick.run()
                    }
                } catch (error: Exception) {
                    terminate("Falha na preparação: ${error.javaClass.simpleName}")
                }
            }
        }

        fun requestStop() {
            gate.close()
            worker.post { terminate("Captura encerrada pelo usuário") }
        }

        fun mark(label: String) {
            if (!gate.acquire()) return
            worker.post {
                try {
                    if (gate.isOpen()) record("marker", JSONObject().put("label", label.take(80)))
                } finally {
                    gate.release()
                }
            }.also { if (!it) gate.release() }
        }

        private inner class Binding(val number: Long, val uid: Int) {
            var requested = false
            var requestedAtMs = SystemClock.elapsedRealtime()
            var binder: IBinder? = null
            var registered = false
            val callback = GuidanceCallback(this)
            val death = IBinder.DeathRecipient { worker.post { lost(this, "binder_died") } }
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    worker.post { connected(this@Binding, service) }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    worker.post { lost(this@Binding, "service_disconnected") }
                }

                override fun onBindingDied(name: ComponentName?) {
                    worker.post { lost(this@Binding, "binding_died") }
                }

                override fun onNullBinding(name: ComponentName?) {
                    worker.post { lost(this@Binding, "null_binding") }
                }
            }
        }

        private fun bind() {
            try {
                val uid = context.packageManager.getApplicationInfo(SERVICE_PACKAGE, 0).uid
                val active = Binding(++generation, uid)
                binding = active
                active.requested = context.bindService(
                    Intent().setComponent(ComponentName(SERVICE_PACKAGE, SERVICE_CLASS)), active.connection, 0)
                record("bind_requested", JSONObject().put("generation", active.number).put("accepted", active.requested))
                if (!active.requested) lost(active, "bind_refused")
                else {
                    view = view.copy(message = "Aguardando serviço CarPlay")
                    publish()
                }
            } catch (error: Exception) {
                binding?.let { release(it) }
                retry("bind_error_${error.javaClass.simpleName}")
            }
        }

        private fun connected(active: Binding, remote: IBinder?) {
            if (binding !== active || !gate.isOpen() || ended) return
            if (remote == null) {
                lost(active, "missing_binder")
                return
            }
            try {
                active.binder = remote
                require(remote.interfaceDescriptor == SERVICE_DESCRIPTOR) { "unexpected service descriptor" }
                remote.linkToDeath(active.death, 0)
                active.registered = subscribe(active, 7)
                require(active.registered) { "subscription refused" }
                failures = 0
                CarPlayExperimentalHud.connect(id, active.number)
                view = view.copy(connected = true, message = "Serviço conectado; aguardando dados de rota", routeState = null, maneuver = null,
                    summary = view.summary.copy(lastKind = null))
                record("subscription_sent", JSONObject().put("generation", active.number))
                publish()
            } catch (error: Exception) {
                record("subscription_error", JSONObject().put("type", error.javaClass.simpleName))
                terminate("Assinatura indisponível: ${error.javaClass.simpleName}")
            }
        }

        private fun subscribe(active: Binding, transaction: Int): Boolean {
            val data = Parcel.obtain()
            return try {
                data.writeInterfaceToken(SERVICE_DESCRIPTOR)
                data.writeStrongBinder(active.callback)
                active.binder?.transact(transaction, data, null, IBinder.FLAG_ONEWAY) == true
            } finally {
                data.recycle()
            }
        }

        private fun release(active: Binding) {
            CarPlayExperimentalHud.disconnect(id, active.number)
            if (binding === active) binding = null
            if (active.registered) {
                runCatching { subscribe(active, 8) }
                active.registered = false
            }
            runCatching { active.binder?.unlinkToDeath(active.death, 0) }
            if (active.requested) {
                runCatching { context.unbindService(active.connection) }
                active.requested = false
            }
            active.binder = null
        }

        private fun lost(active: Binding, reason: String) {
            if (binding !== active || ended) return
            release(active)
            retry(reason)
        }

        private fun retry(reason: String) {
            if (!gate.isOpen() || ended) return
            failures = minOf(failures + 1, 4)
            val delay = minOf(15_000L * failures, 60_000L)
            nextBindMs = SystemClock.elapsedRealtime() + delay
            view = view.copy(connected = false, message = "Aguardando CarPlay; nova tentativa em ${delay / 1_000}s", routeState = null, maneuver = null,
                summary = view.summary.copy(lastKind = null))
            record(reason, JSONObject().put("retryMs", delay))
            publish()
        }

        private inner class GuidanceCallback(private val owner: Binding) : Binder() {
            init {
                attachInterface(object : IInterface {
                    override fun asBinder(): IBinder = this@GuidanceCallback
                }, CALLBACK_DESCRIPTOR)
            }

            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == INTERFACE_TRANSACTION) {
                    reply?.writeString(CALLBACK_DESCRIPTOR)
                    return true
                }
                if (code != 1) return super.onTransact(code, data, reply, flags)
                if (Binder.getCallingUid() != owner.uid || !gate.acquire()) return true
                val receivedAt = SystemClock.elapsedRealtime()
                val epochMs = System.currentTimeMillis()
                val parsed = runCatching {
                    require(data.dataAvail() <= CarPlayGuidanceParcel.MAX_BYTES + 512) { "oversized transaction" }
                    data.enforceInterface(CALLBACK_DESCRIPTOR)
                    CarPlayGuidanceReader.read(CarPlayGuidanceParcel(data))
                }
                val posted = worker.post {
                    try {
                        if (binding !== owner || !gate.isOpen() || ended) return@post
                        if (parsed.isFailure) {
                            view = view.copy(errors = view.errors + 1)
                            record("parse_rejected", JSONObject().put("generation", owner.number)
                                .put("type", parsed.exceptionOrNull()?.javaClass?.simpleName))
                            terminate("Callback incompatível; captura interrompida")
                        } else {
                            val raw = parsed.getOrNull()
                            CarPlayExperimentalHud.onCarPlay(id, owner.number, raw, receivedAt)
                            val summary = view.summary.received(raw)
                            view = view.copy(callbacks = view.callbacks + 1, lastCallbackMs = receivedAt,
                                routeState = raw?.mRouteGuidanceState, maneuver = raw?.mManeuverType,
                                summary = summary, message = when (summary.lastKind) {
                                    CarPlayCapturePayloadKind.EMPTY -> "Callback nulo; sem orientação"
                                    CarPlayCapturePayloadKind.INACTIVE -> "Callback sem rota elegível; não confirma navegação ativa"
                                    CarPlayCapturePayloadKind.CANDIDATE -> "Dados de rota potencialmente úteis; validar conteúdo"
                                    else -> "Dados recebidos; significado ainda não confirmado"
                                })
                            record("guidance", JSONObject().put("generation", owner.number)
                                .put("receivedElapsedMs", receivedAt).put("receivedEpochMs", epochMs)
                                .put("payloadKind", summary.lastKind?.name)
                                .put("raw", raw?.toCaptureJson() ?: JSONObject.NULL))
                            publish()
                        }
                    } finally {
                        gate.release()
                    }
                }
                if (!posted) gate.release()
                return true
            }
        }

        private fun record(event: String, details: JSONObject = JSONObject()): Boolean {
            if (ended) return false
            return try {
                files?.append(JSONObject().put("v", 1).put("session", id)
                    .put("epochMs", System.currentTimeMillis()).put("elapsedMs", SystemClock.elapsedRealtime())
                    .put("event", event).put("details", details).toString()) ?: error("capture files unavailable")
                true
            } catch (error: Exception) {
                terminate("Falha ao gravar captura: ${error.javaClass.simpleName}", false)
                false
            }
        }

        private fun publish() {
            if (!ended) update(this, view)
        }

        private fun captureCounts(now: Long): JSONObject = JSONObject()
            .put("callbacks", view.callbacks).put("errors", view.errors).put("dropped", gate.dropped)
            .put("emptyPayloads", view.summary.empty).put("inactivePayloads", view.summary.inactive)
            .put("candidatePayloads", view.summary.candidates).put("unconfirmedPayloads", view.summary.unconfirmed)
            .put("durationMs", if (startedAtMs == 0L) 0L else (now - startedAtMs).coerceAtLeast(0L))

        private fun terminate(message: String, writeEnd: Boolean = true) {
            if (ended) return
            val stoppedAtMs = SystemClock.elapsedRealtime()
            if (writeEnd) record("capture_stop", captureCounts(stoppedAtMs).put("reason", message))
            if (ended) return
            ended = true
            if (enableHudImmediately) {
                CarPlayCleanMapCoordinator.setExperimentEnabled(false)
                ClusterPersistentEventLogger.log(
                    "carplay_combined_test_stop",
                    mapOf(
                        "session" to id,
                        "reason" to message,
                        "callbacks" to view.callbacks,
                        "errors" to view.errors,
                        "dropped" to gate.dropped
                    )
                )
                runCatching { clusterCaptureLease?.close() }
                clusterCaptureLease = null
            }
            CarPlayExperimentalHud.end(id)
            gate.close()
            worker.removeCallbacks(tick)
            worker.removeCallbacks(durationTimeout)
            binding?.let { release(it) }
            finished(this, view.copy(running = false, connected = false, message = message, dropped = gate.dropped,
                stoppedAtMs = stoppedAtMs))
            thread.quitSafely()
        }
    }

    private const val MAX_DURATION_MS = 60 * 60_000L
    const val FIVE_MINUTE_TEST_MS = 5 * 60_000L
    private const val SERVICE_PACKAGE = "com.ts.carplay"
    private const val SERVICE_CLASS = "com.ts.carplay.CarPlayService"
    private const val SERVICE_DESCRIPTOR = "com.ts.carplay.common.aidl.ICarPlayService"
    private const val CALLBACK_DESCRIPTOR = "com.ts.carplay.common.aidl.IRouteGuidanceUpdateCallback"
}

private fun RawCarPlayGuidance.toCaptureJson(): JSONObject = JSONObject()
    .put("mManeuverType", mManeuverType)
    .put("mDistanceBetweenManeuverDisplayStr", mDistanceBetweenManeuverDisplayStr ?: JSONObject.NULL)
    .put("mDistanceBetweenManeuverDisplayUnits", mDistanceBetweenManeuverDisplayUnits)
    .put("mAfterManeuverRoadName", mAfterManeuverRoadName ?: JSONObject.NULL)
    .put("mDistanceRemainingDisplayStr", mDistanceRemainingDisplayStr ?: JSONObject.NULL)
    .put("mDistanceRemainingDisplayUnits", mDistanceRemainingDisplayUnits)
    .put("mTimeRemainingToDestination", mTimeRemainingToDestination)
    .put("mRouteGuidanceState", mRouteGuidanceState)
    .put("mJunctionType", mJunctionType)
    .put("mDrivingSide", mDrivingSide)
    .put("mJunctionElementExitAngle", mJunctionElementExitAngle)
