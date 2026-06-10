package br.com.redesurftank.havalshisuku.projectors

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionCardOverlayPolicyTest {
    @Test
    fun projectionInactiveNeverArmsOverlayFromClusterInput() {
        assertFalse(ProjectionCardOverlayPolicy.shouldArmFromClusterInput(projectionActive = false))
    }

    @Test
    fun projectionActiveArmsOverlayFromAnyPhysicalClusterInput() {
        assertTrue(ProjectionCardOverlayPolicy.shouldArmFromClusterInput(projectionActive = true))
    }

    @Test
    fun firstProjectionBootstrapWithoutPhysicalInputKeepsOverlaySuppressed() {
        assertFalse(
            ProjectionCardOverlayPolicy.shouldAllowAfterCardChange(
                projectionActive = true,
                overlayAlreadyAllowed = false,
                recentPhysicalInput = false
            )
        )
    }

    @Test
    fun physicalCardNavigationArmsOverlayEvenWhenNextCardIsNeutral() {
        assertTrue(
            ProjectionCardOverlayPolicy.shouldAllowAfterCardChange(
                projectionActive = true,
                overlayAlreadyAllowed = false,
                recentPhysicalInput = true
            )
        )
    }

    @Test
    fun armedOverlayStaysVisibleThroughNativeCardCarousel() {
        assertTrue(
            ProjectionCardOverlayPolicy.shouldAllowAfterCardChange(
                projectionActive = true,
                overlayAlreadyAllowed = true,
                recentPhysicalInput = false
            )
        )
    }

    @Test
    fun projectionInactiveDisarmsEvenIfOverlayWasPreviouslyAllowed() {
        assertFalse(
            ProjectionCardOverlayPolicy.shouldAllowAfterCardChange(
                projectionActive = false,
                overlayAlreadyAllowed = true,
                recentPhysicalInput = true
            )
        )
    }
}
