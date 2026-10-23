package br.com.redesurftank.havalshisuku.navigation

import br.com.redesurftank.havalshisuku.managers.AndroidAutoNavigationTelemetry
import br.com.redesurftank.havalshisuku.hud.HudOverlayModel
import org.junit.Assert.*
import org.junit.Test

class CarPlayHudPolicyTest {
    private fun raw(state: Int = 1) = RawCarPlayGuidance(32, "150", 2, "Via de teste", "2,2", 0,
        480, state, 0, 0, -96)

    private fun enabledPolicy() = CarPlayHudPolicy().apply {
        begin("capture")
        connect("capture", 1)
        setEnabled(true)
        onAndroidAutoAvailability(false, 100)
    }

    @Test fun defaultIsDisabledAndRequiresCapture() {
        val policy = CarPlayHudPolicy()
        policy.setEnabled(true)
        assertFalse(policy.enabled)
        policy.onCarPlay("capture", 1, raw(), 100)
        assertFalse(policy.snapshot(100).active)
        policy.begin("capture")
        policy.connect("capture", 1)
        policy.onCarPlay("capture", 1, raw(), 100)
        assertFalse(policy.snapshot(100).active)
    }

    @Test fun combinedTestCanEnableHudAtomicallyAtCaptureStart() {
        val policy = CarPlayHudPolicy()
        policy.begin("capture", enableImmediately = true)
        assertTrue(policy.enabled)
        policy.connect("capture", 1)
        policy.onCarPlay("capture", 1, raw(), 100)
        assertFalse(policy.snapshot(100).active)
        policy.onAndroidAutoAvailability(false, 100)
        assertTrue(policy.snapshot(100).active)
        policy.end("capture")
        assertFalse(policy.enabled)
        assertFalse(policy.snapshot(101).active)
    }

    @Test fun activeCarPlayDisplaysOnlyTextWithoutInventingAAFields() {
        val directions = CarPlayHudPolicy.textDirections(raw())
        assertTrue(directions.active)
        assertEquals("Via de teste", directions.street)
        assertEquals("150 m", directions.distance)
        assertEquals("", directions.turn)
        assertNull(directions.turnId)
        assertNull(directions.distanceM)
        assertNull(directions.remainingM)
        assertNull(directions.remainingS)
        assertEquals("", directions.eta)
        assertEquals("", directions.nextStreet)
        assertTrue(directions.lanes.isEmpty())
        assertNull(directions.turnAngle)
        assertNull(directions.exitNumber)
        val tbt = requireNotNull(HudOverlayModel.tbtFor(directions, HudOverlayModel.Config(layout = HudOverlayModel.Layout.AR)))
        assertEquals("", tbt.icon)
        assertNull(tbt.arAngle)
        assertNull(tbt.guideAngle)
    }

    @Test fun inactivePendingReroutingUnknownAndNullNeverProduceGuidance() {
        for (state in listOf(0, 2, 3, 4, 5, 6, -1, 999)) {
            assertFalse(CarPlayHudPolicy.textDirections(raw(state)).active)
        }
        assertFalse(CarPlayHudPolicy.textDirections(null).active)
    }

    @Test fun arrivalWithStaleRoadAndDistanceStillClearsGuidance() {
        val policy = enabledPolicy()
        policy.onCarPlay("capture", 1, raw(), 100)
        assertTrue(policy.snapshot(100).active)
        policy.onCarPlay("capture", 1, raw(2), 101)
        assertFalse(policy.snapshot(101).active)
    }

    @Test fun missingRoadDistanceAndUnsupportedUnitFailClosed() {
        for (value in listOf(raw().copy(mAfterManeuverRoadName = " "),
            raw().copy(mDistanceBetweenManeuverDisplayStr = null),
            raw().copy(mDistanceBetweenManeuverDisplayUnits = 999),
            raw().copy(mDistanceBetweenManeuverDisplayUnits = 1))) {
            assertFalse(CarPlayHudPolicy.textDirections(value).active)
        }
    }

    @Test fun kilometersPreserveLocalizedDisplayWithoutGuessingNumbers() {
        val directions = CarPlayHudPolicy.textDirections(raw().copy(
            mDistanceBetweenManeuverDisplayStr = "1,234", mDistanceBetweenManeuverDisplayUnits = 0))
        assertEquals("1,234 km", directions.distance)
        assertNull(directions.distanceM)
    }

    @Test fun AAAlwaysWinsAndKeepsAllItsFields() {
        val policy = enabledPolicy()
        val aa = AndroidAutoNavigationTelemetry.Directions(true, street = "AA", distance = "20 m", distanceM = 20,
            turn = "ROUNDABOUT", turnId = 42, nextStreet = "Próxima", nextDistanceM = 100, nextTurn = "TURN_LEFT",
            remainingM = 2000, remainingS = 300, eta = "20:30",
            lanes = listOf(listOf(AndroidAutoNavigationTelemetry.LaneDirection(4, true))),
            exitNumber = 3, turnAngle = 180, roundaboutClockwise = false)
        policy.onAndroidAuto(aa)
        policy.onCarPlay("capture", 1, raw(), 100)
        assertEquals(aa, policy.snapshot(100))
        assertEquals(aa.toJson(), policy.snapshot(100).toJson())
        policy.end("capture")
        assertEquals(aa, policy.snapshot(200))
    }

    @Test fun AAInactiveFallsBackOnlyToFreshCarPlay() {
        val policy = enabledPolicy()
        policy.onCarPlay("capture", 1, raw(), 100)
        policy.onAndroidAuto(AndroidAutoNavigationTelemetry.Directions(true, street = "AA"))
        policy.onAndroidAuto(AndroidAutoNavigationTelemetry.inactive())
        assertEquals("Via de teste", policy.snapshot(200).street)
        assertFalse(policy.snapshot(5_100).active)
    }

    @Test fun unknownActiveStaleAndFutureAndroidAutoAvailabilityFailClosed() {
        val policy = CarPlayHudPolicy().apply {
            begin("capture")
            connect("capture", 1)
            setEnabled(true)
            onCarPlay("capture", 1, raw(), 100)
        }
        assertFalse(policy.snapshot(100).active)
        policy.onAndroidAutoAvailability(true, 100)
        assertFalse(policy.snapshot(100).active)
        policy.onAndroidAutoAvailability(false, 101)
        assertTrue(policy.snapshot(101).active)
        assertFalse(policy.snapshot(5_101).active)
        policy.onAndroidAutoAvailability(false, 6_000)
        assertFalse(policy.snapshot(5_999).active)
    }

    @Test fun androidAutoObservationExpiryCanClearBeforeCarPlayDataExpiry() {
        val policy = enabledPolicy()
        policy.onCarPlay("capture", 1, raw(), 1_000)
        assertTrue(policy.snapshot(5_099).active)
        assertFalse(policy.snapshot(5_100).active)
        assertEquals(5_100L, policy.nextExpiryMs())
    }

    @Test fun recomputingAfterLongSleepClearsExpiredGuidanceImmediately() {
        val policy = enabledPolicy()
        policy.onCarPlay("capture", 1, raw(), 100)
        assertTrue(policy.snapshot(100).active)
        assertFalse(policy.snapshot(60_000).active)
    }

    @Test fun expiryBoundaryAndFutureTimestampFailClosed() {
        val policy = enabledPolicy()
        policy.onCarPlay("capture", 1, raw(), 100)
        assertFalse(policy.snapshot(99).active)
        assertTrue(policy.snapshot(5_099).active)
        assertFalse(policy.snapshot(5_100).active)
        assertEquals(5_100L, policy.nextExpiryMs())
    }

    @Test fun oldBindingCannotClearOrOverwriteNewGeneration() {
        val policy = enabledPolicy()
        policy.connect("capture", 2)
        policy.onCarPlay("capture", 2, raw(), 100)
        policy.disconnect("capture", 1)
        policy.onCarPlay("capture", 1, raw(0), 101)
        assertTrue(policy.snapshot(101).active)
        policy.disconnect("capture", 2)
        assertFalse(policy.snapshot(101).active)
        assertNull(policy.nextExpiryMs())
    }

    @Test fun oldCaptureCannotTerminateNewCaptureAndNewCaptureDefaultsOff() {
        val policy = enabledPolicy()
        policy.begin("new")
        assertFalse(policy.enabled)
        policy.connect("new", 1)
        policy.setEnabled(true)
        policy.onCarPlay("new", 1, raw(), 100)
        policy.end("capture")
        policy.onCarPlay("capture", 1, raw(0), 101)
        assertTrue(policy.snapshot(101).active)
        policy.end("new")
        assertFalse(policy.enabled)
        assertFalse(policy.snapshot(101).active)
    }

    @Test fun disablingRequiresAFreshCallbackAfterEnablingAgain() {
        val policy = enabledPolicy()
        policy.onCarPlay("capture", 1, raw(), 100)
        policy.setEnabled(false)
        assertFalse(policy.snapshot(100).active)
        policy.setEnabled(true)
        assertFalse(policy.snapshot(101).active)
        policy.onCarPlay("capture", 1, raw(), 102)
        assertTrue(policy.snapshot(102).active)
    }

    @Test fun rebindingClearsPreviouslyReceivedCarPlay() {
        val policy = enabledPolicy()
        policy.onCarPlay("capture", 1, raw(), 100)
        policy.connect("capture", 2)
        assertFalse(policy.snapshot(101).active)
    }

    @Test fun storedAALanesCannotBeMutatedByTheCaller() {
        val lane = mutableListOf(AndroidAutoNavigationTelemetry.LaneDirection(4, true))
        val lanes = mutableListOf<List<AndroidAutoNavigationTelemetry.LaneDirection>>(lane)
        val policy = CarPlayHudPolicy()
        policy.onAndroidAuto(AndroidAutoNavigationTelemetry.Directions(true, lanes = lanes))
        lane.clear()
        lanes.clear()
        assertEquals(listOf(listOf(AndroidAutoNavigationTelemetry.LaneDirection(4, true))), policy.snapshot(100).lanes)
    }
}
