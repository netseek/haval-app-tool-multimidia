package br.com.redesurftank.havalshisuku.projectors

object ProjectionCardOverlayPolicy {
    const val MAIN_MENU_CARD = 1
    const val AIRCON_CARD = 3

    fun shouldArmFromClusterInput(projectionActive: Boolean): Boolean {
        return projectionActive
    }

    fun shouldAllowAfterCardChange(
        projectionActive: Boolean,
        overlayAlreadyAllowed: Boolean,
        recentPhysicalInput: Boolean
    ): Boolean {
        if (!projectionActive) return false
        if (recentPhysicalInput) return true
        return overlayAlreadyAllowed
    }
}
