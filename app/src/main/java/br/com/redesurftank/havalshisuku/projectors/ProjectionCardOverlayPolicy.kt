package br.com.redesurftank.havalshisuku.projectors

/** Authorization for transparent card overlays; a card notification is not driver input. */
object ProjectionCardOverlayPolicy {
    const val MAIN_MENU_CARD = 1
    const val AIRCON_CARD = 3

    fun shouldArmFromClusterInput(projectionActive: Boolean): Boolean = projectionActive

    fun shouldAllowAfterCardChange(
        projectionActive: Boolean,
        overlayAlreadyAllowed: Boolean,
        recentPhysicalInput: Boolean
    ): Boolean {
        if (!projectionActive) return false
        if (recentPhysicalInput) return true
        return overlayAlreadyAllowed
    }

    /**
     * Main-thread state. A changed projection identity starts a fresh authorization window;
     * input observed outside that window cannot authorize a later projection's bootstrap card.
     */
    class Session(private val inputWindowMs: Long = 1_500L) {
        private var projectionIdentity = 0
        private var lastPhysicalInputAtMs: Long? = null
        var overlayAllowed = false
            private set

        fun syncProjection(identity: Int) {
            if (identity == projectionIdentity) return
            projectionIdentity = identity
            lastPhysicalInputAtMs = null
            overlayAllowed = false
        }

        fun onPhysicalInput(identity: Int, nowMs: Long) {
            syncProjection(identity)
            if (!shouldArmFromClusterInput(identity != 0)) return
            lastPhysicalInputAtMs = nowMs
            overlayAllowed = true
        }

        fun onCardChanged(identity: Int, nowMs: Long): Boolean {
            syncProjection(identity)
            val recentInput = lastPhysicalInputAtMs?.let {
                nowMs >= it && nowMs - it <= inputWindowMs
            } ?: false
            overlayAllowed = shouldAllowAfterCardChange(identity != 0, overlayAllowed, recentInput)
            return overlayAllowed
        }

        fun reset() {
            projectionIdentity = 0
            lastPhysicalInputAtMs = null
            overlayAllowed = false
        }
    }
}
