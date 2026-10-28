package br.com.redesurftank.havalshisuku.navigation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import br.com.redesurftank.havalshisuku.diagnostics.ClusterPersistentEventLogger
import br.com.redesurftank.havalshisuku.hud.HudOverlayController
import br.com.redesurftank.havalshisuku.managers.AndroidAutoClusterController
import br.com.redesurftank.havalshisuku.managers.AndroidAutoNavigationTelemetry
import java.util.concurrent.atomic.AtomicBoolean

object CarPlayExperimentalHud {
    private val main = Handler(Looper.getMainLooper())
    private val policy = CarPlayHudPolicy()
    private val started = AtomicBoolean(false)
    private var published = AndroidAutoNavigationTelemetry.inactive()
    @Volatile var enabled = false
        private set
    @Volatile var decisionMessage = "HUD experimental desativado"
        private set
    private var context: Context? = null
    private var captureId: String? = null
    private var captureGeneration: Long? = null
    private var captureSerial = 0L
    private var captureDeadlineMs = 0L
    private data class ObserverKey(val id: String, val generation: Long, val serial: Long)
    private var observerKey: ObserverKey? = null
    private val observer by lazy { CarPlayAndroidAutoObserver() }
    private var passiveReason = "NOT_OBSERVED"
    private var lastDiagnostic: String? = null
    private var lastDiagnosticAtMs = 0L
    private val expire = Runnable { refresh() }
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON) change { }
        }
    }

    fun start(context: Context) {
        this.context = context.applicationContext
        if (!started.compareAndSet(false, true)) return
        runCatching {
            context.applicationContext.registerReceiver(wakeReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        }.onFailure {
            started.set(false)
            Log.e(TAG, "Unable to register HUD wake revalidation", it)
        }
    }

    fun begin(id: String, enableImmediately: Boolean = false,
        deadlineElapsedMs: Long = SystemClock.elapsedRealtime() + 300_000L) = change {
        captureId = id
        captureGeneration = null
        captureSerial++
        captureDeadlineMs = deadlineElapsedMs
        lastDiagnostic = null
        policy.begin(id, enableImmediately)
    }

    fun setEnabled(value: Boolean) = change { policy.setEnabled(value) }

    fun connect(id: String, generation: Long) = change {
        if (captureId == id) { captureGeneration = generation; policy.clearPassiveObservation() }
        policy.connect(id, generation)
    }

    fun disconnect(id: String, generation: Long) = change {
        if (captureId == id && captureGeneration == generation) captureGeneration = null
        policy.disconnect(id, generation)
    }

    fun end(id: String) = change {
        if (captureId == id) { captureId = null; captureGeneration = null }
        policy.end(id)
    }

    fun onAndroidAuto(directions: AndroidAutoNavigationTelemetry.Directions) {
        val frozen = directions.copy(lanes = directions.lanes.map { it.toList() })
        change(forwardUnchanged = true) { policy.onAndroidAuto(frozen) }
    }

    fun onAndroidAutoAvailability(active: Boolean, observedAtMs: Long) {
        change { policy.onAndroidAutoAvailability(active, observedAtMs) }
    }

    fun onCarPlay(id: String, generation: Long, raw: RawCarPlayGuidance?, receivedAtMs: Long) {
        change {
            policy.onCarPlay(id, generation, raw, receivedAtMs)
        }
    }

    private fun change(forwardUnchanged: Boolean = false, action: () -> Unit) {
        main.post {
            action()
            refresh(forwardUnchanged)
        }
    }

    /** Main-thread-only gate shared with the independent map controller. */
    fun isAndroidAutoKnownInactive(nowMs: Long): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        return policy.isAndroidAutoKnownInactive(nowMs, hostAndroidAutoActive())
    }

    private fun hostAndroidAutoActive(): Boolean = AndroidAutoClusterController.isSessionActive() ||
        AndroidAutoClusterController.isSurfaceAttached()

    private fun refresh(forwardUnchanged: Boolean = false) {
        updateObserver()
        enabled = policy.enabled
        publish(forwardUnchanged)
        main.removeCallbacks(expire)
        val now = SystemClock.elapsedRealtime()
        policy.nextRevalidationMs(now, captureDeadlineMs.takeIf { policy.enabled && captureId != null })?.let { deadline ->
            val delay = deadline - now
            if (delay > 0) main.postDelayed(expire, delay)
        }
    }

    private fun updateObserver() {
        if (captureId != null && SystemClock.elapsedRealtime() >= captureDeadlineMs) policy.setEnabled(false)
        val id = captureId
        val generation = captureGeneration
        val next = if (policy.enabled && id != null && generation != null && context != null)
            ObserverKey(id, generation, captureSerial) else null
        if (next == observerKey) return
        if (observerKey != null) observer.stop()
        observerKey = next
        policy.clearPassiveObservation()
        passiveReason = "OBSERVER_STOPPED"
        if (next != null) observer.start(requireNotNull(context), captureDeadlineMs) { observed ->
            if (observerKey != next || captureId != next.id || captureGeneration != next.generation ||
                !policy.enabled || SystemClock.elapsedRealtime() >= captureDeadlineMs) return@start
            passiveReason = observed.reason
            policy.onPassiveAndroidAutoAvailability(observed.active, observed.observedAtMs)
            refresh()
        }
    }

    private fun publish(forwardUnchanged: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val hostActive = hostAndroidAutoActive()
        val next = policy.snapshot(now, hostActive)
        val diagnostics = policy.diagnostic(now, hostActive) + ("aa_reader_reason" to passiveReason)
        decisionMessage = when (diagnostics["reason"]) {
            "AA_DIRECTIONS", "AA_HOST_ACTIVE", "AA_LINK_ACTIVE" -> "Android Auto com prioridade no HUD"
            "EXPERIMENT_DISABLED", "CAPTURE_MISSING" -> "HUD experimental desativado"
            "BINDING_MISSING" -> "Aguardando conexão do serviço CarPlay"
            "AA_INACTIVE_NOT_CONFIRMED" -> "Aguardando confirmação de Android Auto inativo"
            "CP_DATA_MISSING", "CP_GUIDANCE_INELIGIBLE" -> "Aguardando orientação ativa do CarPlay"
            "CP_DATA_NOT_FRESH" -> "Aguardando dados recentes do CarPlay"
            else -> "Texto do CarPlay enviado ao HUD"
        }
        if (forwardUnchanged || next != published) {
            published = next
            HudOverlayController.onDirections(next)
        }
        if (captureId != null) {
            val signature = diagnostics.filterKeys { !it.endsWith("_age") }.toSortedMap().entries
                .joinToString("|") { "${it.key}=${it.value}" }
            if (lastDiagnostic != signature || now - lastDiagnosticAtMs >= 5_000L) {
                lastDiagnostic = signature
                lastDiagnosticAtMs = now
                ClusterPersistentEventLogger.log("carplay_hud_decision", diagnostics)
            }
        }
    }

    private const val TAG = "CarPlayExperimentalHud"
}
