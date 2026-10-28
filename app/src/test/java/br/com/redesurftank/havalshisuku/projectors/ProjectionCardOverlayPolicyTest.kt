package br.com.redesurftank.havalshisuku.projectors

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionCardOverlayPolicyTest {
    @Test fun projectionInactiveNeverArmsOverlayFromClusterInput() {
        assertFalse(ProjectionCardOverlayPolicy.shouldArmFromClusterInput(false))
    }

    @Test fun projectionActiveArmsOverlayFromAnyPhysicalClusterInput() {
        assertTrue(ProjectionCardOverlayPolicy.shouldArmFromClusterInput(true))
    }

    @Test fun firstProjectionBootstrapWithoutPhysicalInputKeepsOverlaySuppressed() {
        assertFalse(ProjectionCardOverlayPolicy.shouldAllowAfterCardChange(true, false, false))
    }

    @Test fun physicalCardNavigationArmsOverlayEvenWhenNextCardIsNeutral() {
        assertTrue(ProjectionCardOverlayPolicy.shouldAllowAfterCardChange(true, false, true))
    }

    @Test fun armedOverlayStaysVisibleThroughNativeCardCarousel() {
        assertTrue(ProjectionCardOverlayPolicy.shouldAllowAfterCardChange(true, true, false))
    }

    @Test fun projectionInactiveDisarmsEvenIfOverlayWasPreviouslyAllowed() {
        assertFalse(ProjectionCardOverlayPolicy.shouldAllowAfterCardChange(false, true, true))
    }

    @Test fun inputBeforeProjectionDoesNotAuthorizeItsBootstrapCard() {
        val session = ProjectionCardOverlayPolicy.Session()
        session.onPhysicalInput(0, 100L)
        assertFalse(session.onCardChanged(1, 101L))
        assertFalse(session.overlayAllowed)
    }

    @Test fun bootstrapCardAndRepeatedStatePushesCannotArmTheSession() {
        val session = ProjectionCardOverlayPolicy.Session()
        session.syncProjection(1)
        assertFalse(session.onCardChanged(1, 100L))
        session.syncProjection(1)
        assertFalse(session.onCardChanged(1, 200L))
    }

    @Test fun physicalNavigationRetainsAuthorizationAcrossNeutralCardsAndExpiredInputWindow() {
        val session = ProjectionCardOverlayPolicy.Session()
        session.onPhysicalInput(1, 100L)
        assertTrue(session.overlayAllowed)
        assertTrue(session.onCardChanged(1, 101L))
        assertTrue(session.onCardChanged(1, 10_000L))
        session.syncProjection(1)
        assertTrue(session.overlayAllowed)
    }

    @Test fun disconnectDisarmsAndReconnectCannotReusePreviousSessionInput() {
        val session = ProjectionCardOverlayPolicy.Session()
        session.onPhysicalInput(1, 100L)
        assertTrue(session.overlayAllowed)
        session.syncProjection(0)
        assertFalse(session.overlayAllowed)
        assertFalse(session.onCardChanged(1, 102L))
    }

    @Test fun switchingProjectionSourceRequiresFreshPhysicalInput() {
        val session = ProjectionCardOverlayPolicy.Session()
        session.onPhysicalInput(1, 100L)
        assertFalse(session.onCardChanged(8, 101L))
        session.onPhysicalInput(8, 102L)
        assertTrue(session.onCardChanged(8, 103L))
        assertFalse(session.onCardChanged(1, 104L))
    }

    @Test fun explicitCacheResetCannotCarryAuthorizationIntoReloadedState() {
        val session = ProjectionCardOverlayPolicy.Session()
        session.onPhysicalInput(1, 100L)
        session.reset()
        assertFalse(session.onCardChanged(1, 101L))
        session.onPhysicalInput(1, 102L)
        assertTrue(session.onCardChanged(1, 103L))
    }
}
