package br.com.redesurftank.havalshisuku.experimental

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher

/** ActivityManager reads stay off the UI thread and exist only for the explicit test. */
internal class CarPlayCleanMapSampler(
    private val deadlineElapsedMs: Long,
    private val publish: (CarPlayCleanMapTaskObservation) -> Unit
) : AutoCloseable {
    @Volatile private var closed = false
    private val poll = object : Runnable {
        override fun run() {
            if (closed || SystemClock.elapsedRealtime() >= deadlineElapsedMs) { close(); return }
            val sample = runCatching { DisplayAppLauncher.readCarPlayCleanMapTasks() }
                .getOrElse { CarPlayCleanMapTaskObservation.unknown() }
            if (!closed && SystemClock.elapsedRealtime() < deadlineElapsedMs) publish(sample)
            if (!closed) worker.postDelayed(this, 500)
        }
    }

    init { worker.post(poll) }

    override fun close() {
        closed = true
        worker.removeCallbacks(poll)
    }

    companion object {
        // A blocked AM read can never create one extra thread per later test.
        // All sessions share one serial daemon; close removes only its own work.
        private val thread = HandlerThread("carplay-clean-map-observe").apply { isDaemon = true; start() }
        private val worker = Handler(thread.looper)
    }
}
