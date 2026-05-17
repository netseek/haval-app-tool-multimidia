package br.com.redesurftank.havalshisuku.broadcastReceivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

class AndroidAutoMonitorReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AA_MONITOR"
        private val handler = Handler(Looper.getMainLooper())
        private var pendingPokeRunnable: Runnable? = null
        private const val DEBOUNCE_MS = 300L
        private const val POST_POKE_COOLDOWN_MS = 2000L

        /**
         * Timestamp of the last poke we sent. Background events within
         * POST_POKE_COOLDOWN_MS of this are ignored — they are side-effects
         * of our own poke (am start causes a brief bg→fg oscillation).
         */
        @Volatile
        var lastPokeTimestamp: Long = 0L

        /**
         * Timestamp of the last confirmed foreground broadcast.
         * Used by the watchdog to detect stale focus.
         */
        @Volatile
        var lastForegroundTimestamp: Long = 0L
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val extras = intent.extras
        val state = intent.getStringExtra("state")
        val extrasStr = extras?.keySet()?.joinToString(", ") { key -> "$key=${extras.get(key)}" } ?: "none"
        Log.w(TAG, "Received Broadcast: $action | Extras: $extrasStr")

        if (action != "ts.car.androidauto.view_state") return

        val hasForceFocusApps = br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher.hasAnyForceFocusApp()
        if (!hasForceFocusApps) {
            Log.w(TAG, "No apps with forceFocus enabled, ignoring.")
            return
        }

        if (state == "foreground") {
            lastForegroundTimestamp = System.currentTimeMillis()
            // Poke succeeded — clear cooldown so next real touch is handled immediately
            lastPokeTimestamp = 0L
            // Cancel any pending poke — AA confirmed it's in foreground
            pendingPokeRunnable?.let {
                Log.d(TAG, "Foreground received, cancelling pending poke")
                handler.removeCallbacks(it)
                pendingPokeRunnable = null
            }
            return
        }

        // Background event — but is it from our own poke?
        val timeSinceLastPoke = System.currentTimeMillis() - lastPokeTimestamp
        if (lastPokeTimestamp > 0 && timeSinceLastPoke < POST_POKE_COOLDOWN_MS) {
            Log.d(TAG, "AA background detected but within post-poke cooldown (${timeSinceLastPoke}ms ago). Ignoring.")
            return
        }

        // Genuine background event — schedule a poke
        pendingPokeRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            Log.w(TAG, "Debounced poke firing (${DEBOUNCE_MS}ms since last background event)")
            pendingPokeRunnable = null
            lastPokeTimestamp = System.currentTimeMillis()
            br.com.redesurftank.havalshisuku.managers.DisplayAppLauncher.syncInterconnectionFocus("broadcast_background")
        }
        pendingPokeRunnable = runnable
        handler.postDelayed(runnable, DEBOUNCE_MS)
        Log.d(TAG, "AA background detected — poke scheduled/reset (debounce ${DEBOUNCE_MS}ms)")
    }
}
