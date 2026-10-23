package br.com.redesurftank.havalshisuku.navigation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import br.com.redesurftank.havalshisuku.hud.HudOverlayController
import br.com.redesurftank.havalshisuku.managers.AndroidAutoNavigationTelemetry
import java.util.concurrent.atomic.AtomicBoolean

object CarPlayExperimentalHud {
    private val main = Handler(Looper.getMainLooper())
    private val policy = CarPlayHudPolicy()
    private val started = AtomicBoolean(false)
    private var published = AndroidAutoNavigationTelemetry.inactive()
    @Volatile var enabled = false
        private set
    private val expire = Runnable { publish() }
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON) change { }
        }
    }

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        runCatching {
            context.applicationContext.registerReceiver(wakeReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        }.onFailure {
            started.set(false)
            Log.e(TAG, "Unable to register HUD wake revalidation", it)
        }
    }

    fun begin(id: String, enableImmediately: Boolean = false) = change {
        policy.begin(id, enableImmediately)
    }

    fun setEnabled(value: Boolean) = change { policy.setEnabled(value) }

    fun connect(id: String, generation: Long) = change { policy.connect(id, generation) }

    fun disconnect(id: String, generation: Long) = change { policy.disconnect(id, generation) }

    fun end(id: String) = change { policy.end(id) }

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
            enabled = policy.enabled
            publish(forwardUnchanged)
            main.removeCallbacks(expire)
            policy.nextExpiryMs()?.let { deadline ->
                val delay = deadline - SystemClock.elapsedRealtime()
                if (delay > 0L) main.postDelayed(expire, delay)
            }
        }
    }

    private fun publish(forwardUnchanged: Boolean = false) {
        val next = policy.snapshot(SystemClock.elapsedRealtime())
        if (!forwardUnchanged && next == published) return
        published = next
        HudOverlayController.onDirections(next)
    }

    private const val TAG = "CarPlayExperimentalHud"
}
