package br.com.redesurftank.havalshisuku.experimental

import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.FrameLayout
import br.com.redesurftank.havalshisuku.diagnostics.ClusterPersistentEventLogger
import br.com.redesurftank.App
import java.util.Arrays

object CarPlayCleanMapCoordinator {
    private const val OFFER_MAX_AGE_MS = 5_000L
    private val main = Handler(Looper.getMainLooper())

    private data class Offer(
        val endpoint: IBinder,
        val token: ByteArray,
        val generation: Long,
        val offeredAtMs: Long
    )

    private var enabled = false
    private var slot: FrameLayout? = null
    private var snapshots: CarPlaySecondaryClusterHost.SnapshotProvider? = null
    private var offer: Offer? = null
    private var host: CarPlaySecondaryClusterHost? = null

    fun setExperimentEnabled(value: Boolean) {
        main.post {
            enabled = value
            ClusterPersistentEventLogger.log("carplay_clean_map_gate", mapOf("enabled" to value))
            runCatching {
                App.getDeviceProtectedContext().sendBroadcast(
                    Intent(if (value) ACTION_REQUEST else ACTION_REVOKE).setPackage(OEM_PACKAGE)
                )
            }
            if (value) tryStart() else retire("disabled")
        }
    }

    fun isExperimentEnabled(): Boolean = enabled

    fun attachSlot(target: FrameLayout, provider: CarPlaySecondaryClusterHost.SnapshotProvider): AutoCloseable {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(slot == null || slot === target) { "Only one CarPlay clean-map slot is allowed" }
        slot = target
        snapshots = provider
        tryStart()
        return AutoCloseable {
            main.post {
                if (slot === target) {
                    retire("slot_detached")
                    slot = null
                    snapshots = null
                }
            }
        }
    }

    fun offerEndpoint(endpoint: IBinder, token: ByteArray, generation: Long) {
        require(token.size == 32 && generation > 0 && endpoint.isBinderAlive)
        val copy = token.clone()
        main.post {
            clearOffer()
            offer = Offer(endpoint, copy, generation, SystemClock.elapsedRealtime())
            ClusterPersistentEventLogger.log(
                "carplay_clean_map_endpoint",
                mapOf("generation" to generation, "accepted" to enabled)
            )
            tryStart()
        }
    }

    fun inputsChanged() {
        main.post { host?.onInputsChanged() ?: tryStart() }
    }

    private fun tryStart() {
        if (!enabled || host != null) return
        val target = slot ?: return
        val provider = snapshots ?: return
        val pending = offer ?: return
        if (!pending.endpoint.isBinderAlive ||
            SystemClock.elapsedRealtime() - pending.offeredAtMs > OFFER_MAX_AGE_MS) {
            clearOffer()
            return
        }
        lateinit var next: CarPlaySecondaryClusterHost
        next = CarPlaySecondaryClusterHost(target, provider, object : CarPlaySecondaryClusterHost.Listener {
            override fun onReady(generation: Long, width: Int, height: Int) {
                ClusterPersistentEventLogger.log(
                    "carplay_clean_map_surface_ready",
                    mapOf("generation" to generation, "width" to width, "height" to height)
                )
            }

            override fun onFailure(
                failure: CarPlaySecondaryClusterHost.Failure,
                consumerFailure: CarPlaySecondarySurfaceConsumer.Failure?
            ) {
                ClusterPersistentEventLogger.log(
                    "carplay_clean_map_failure",
                    mapOf("failure" to failure.name, "consumer" to (consumerFailure?.name ?: "none"))
                )
            }

            override fun onHidden() {
                ClusterPersistentEventLogger.log("carplay_clean_map_hidden")
            }

            override fun onRetired() {
                if (host === next) host = null
                clearOffer()
                ClusterPersistentEventLogger.log("carplay_clean_map_retired")
            }
        })
        host = next
        if (!next.start(pending.endpoint, pending.token, pending.generation)) {
            if (host === next) host = null
            clearOffer()
        }
    }

    private fun retire(reason: String) {
        ClusterPersistentEventLogger.log("carplay_clean_map_retire", mapOf("reason" to reason))
        host?.close()
        if (host == null) clearOffer()
    }

    private fun clearOffer() {
        offer?.let { Arrays.fill(it.token, 0) }
        offer = null
    }

    private const val OEM_PACKAGE = "com.ts.carplay"
    private const val ACTION_REQUEST = "br.com.redesurftank.havalshisuku.CARPLAY_CLEAN_MAP_REQUEST"
    private const val ACTION_REVOKE = "br.com.redesurftank.havalshisuku.CARPLAY_CLEAN_MAP_REVOKE"
}
