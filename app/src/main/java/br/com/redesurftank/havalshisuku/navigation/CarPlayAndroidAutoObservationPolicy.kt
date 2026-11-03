package br.com.redesurftank.havalshisuku.navigation

/** Only the reviewed, passive LinkCommand query may feed this window. No device identifiers. */
internal class CarPlayAndroidAutoObservationPolicy {
    data class Observation(val active: Boolean?, val observedAtMs: Long, val reason: String)
    private var epoch = -1L
    private var previousNegativeAt: Long? = null
    private var lastStartedAt: Long? = null

    fun reset() { epoch = -1L; previousNegativeAt = null; lastStartedAt = null }

    fun observe(profileVerified: Boolean, epoch: Long, boundAtMs: Long,
        startedAtMs: Long, completedAtMs: Long, status: Int?): Observation {
        if (epoch < this.epoch) return Observation(null, completedAtMs, "OLD_EPOCH")
        if (this.epoch != epoch) { reset(); this.epoch = epoch }
        fun unknown(reason: String): Observation {
            previousNegativeAt = null
            return Observation(null, completedAtMs, reason)
        }
        if (!profileVerified) return unknown("FIRMWARE_UNKNOWN")
        if (lastStartedAt?.let { startedAtMs <= it } == true) return Observation(null, completedAtMs, "OUT_OF_ORDER")
        lastStartedAt = startedAtMs
        if (epoch < 0 || boundAtMs < 0 || startedAtMs < boundAtMs || completedAtMs < startedAtMs ||
            completedAtMs - startedAtMs > MAX_READ_MS) return unknown("READ_NOT_FRESH")
        when (status) {
            3, 7, 8 -> { previousNegativeAt = null; return Observation(true, startedAtMs, "AA_ACTIVE") }
            -1, 1, 5, 6, 10 -> Unit // Exact ABI: no current device/power, available, stopped or failed.
            else -> return unknown(if (status == null) "READ_UNKNOWN" else "STATUS_TRANSITION_OR_UNKNOWN")
        }
        if (startedAtMs - boundAtMs < SETTLE_MS) return unknown("BINDER_SETTLING")
        val previous = previousNegativeAt
        previousNegativeAt = startedAtMs
        if (previous == null || startedAtMs - previous !in MIN_SAMPLE_GAP_MS..MAX_SAMPLE_GAP_MS) {
            return Observation(null, startedAtMs, "INACTIVE_CONFIRMATION_PENDING")
        }
        return Observation(false, startedAtMs, if (status == -1) "NO_DEVICE_OR_POWER" else "AA_INACTIVE")
    }

    companion object {
        const val APK_SHA256 = "c40147275d9bd0f32e89230d4fcce0e8b073877faf8e9d57f920033495e35cd7"
        const val APK_SIZE = 2_472_405L
        const val SETTLE_MS = 1_000L
        const val MIN_SAMPLE_GAP_MS = 500L
        const val MAX_SAMPLE_GAP_MS = 1_500L
        const val MAX_READ_MS = 1_500L
    }
}
