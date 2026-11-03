package br.com.redesurftank.havalshisuku.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPlayCaptureSummaryTest {
    private fun fixture(state: Int = 0) = RawCarPlayGuidance(
        mManeuverType = -1,
        mDistanceBetweenManeuverDisplayStr = "",
        mDistanceBetweenManeuverDisplayUnits = -1,
        mAfterManeuverRoadName = "",
        mDistanceRemainingDisplayStr = null,
        mDistanceRemainingDisplayUnits = 0,
        mTimeRemainingToDestination = 0,
        mRouteGuidanceState = state,
        mJunctionType = -1,
        mDrivingSide = -1,
        mJunctionElementExitAngle = 181
    )

    @Test fun capturedWazeDefaultsAreNotUsefulGuidance() {
        val summary = CarPlayCaptureSummary().received(fixture()).received(fixture())
        assertEquals(2L, summary.inactive)
        assertEquals(0L, summary.candidates)
        assertEquals(CarPlayCapturePayloadKind.INACTIVE, summary.lastKind)
    }

    @Test fun nullPayloadIsCountedSeparately() {
        val summary = CarPlayCaptureSummary().received(null)
        assertEquals(1L, summary.empty)
        assertEquals(0L, summary.inactive)
        assertEquals(CarPlayCapturePayloadKind.EMPTY, summary.lastKind)
    }

    @Test fun inactiveStatesRemainInactiveEvenWithStaleContent() {
        for (state in listOf(0, 2, 3, 4)) {
            assertEquals(CarPlayCapturePayloadKind.INACTIVE,
                CarPlayCaptureSummary.classify(fixture(state).copy(mManeuverType = 5, mAfterManeuverRoadName = "Via")))
        }
    }

    @Test fun eligibleStatesWithoutContentAreUnconfirmed() {
        for (state in listOf(1, 5, 6)) {
            assertEquals(CarPlayCapturePayloadKind.UNCONFIRMED, CarPlayCaptureSummary.classify(fixture(state)))
        }
    }

    @Test fun eligibleStatesWithContentAreOnlyCandidates() {
        for (state in listOf(1, 5, 6)) {
            assertEquals(CarPlayCapturePayloadKind.CANDIDATE,
                CarPlayCaptureSummary.classify(fixture(state).copy(mDistanceBetweenManeuverDisplayStr = "250")))
        }
    }

    @Test fun eachObservedContentFieldCanIdentifyACandidate() {
        val raw = fixture(1)
        for (candidate in listOf(raw.copy(mManeuverType = 0), raw.copy(mAfterManeuverRoadName = "Via"),
            raw.copy(mDistanceRemainingDisplayStr = "3,2"), raw.copy(mDistanceBetweenManeuverDisplayStr = "100"))) {
            assertEquals(CarPlayCapturePayloadKind.CANDIDATE, CarPlayCaptureSummary.classify(candidate))
        }
    }

    @Test fun whitespaceAndSentinelAngleDoNotEstablishUsefulContent() {
        assertEquals(CarPlayCapturePayloadKind.UNCONFIRMED, CarPlayCaptureSummary.classify(
            fixture(1).copy(mAfterManeuverRoadName = "  ", mDistanceRemainingDisplayStr = "\t",
                mDistanceBetweenManeuverDisplayStr = "\n")))
    }

    @Test fun unknownStatesRemainUnconfirmedDespiteContent() {
        for (state in listOf(-1, 7, Int.MAX_VALUE)) {
            assertEquals(CarPlayCapturePayloadKind.UNCONFIRMED,
                CarPlayCaptureSummary.classify(fixture(state).copy(mManeuverType = 2)))
        }
    }

    @Test fun countersAreExclusiveAndResetBetweenSessions() {
        val summary = CarPlayCaptureSummary().received(null).received(fixture())
            .received(fixture(1)).received(fixture(5).copy(mAfterManeuverRoadName = "Via"))
        assertEquals(1L, summary.empty)
        assertEquals(1L, summary.inactive)
        assertEquals(1L, summary.unconfirmed)
        assertEquals(1L, summary.candidates)
        assertEquals(CarPlayCapturePayloadKind.CANDIDATE, summary.lastKind)
        val restarted = CarPlayCaptureSummary()
        assertEquals(0L, restarted.empty + restarted.inactive + restarted.candidates + restarted.unconfirmed)
        assertNull(restarted.lastKind)
    }

    @Test fun candidateCountDoesNotMakeLaterInactivePayloadActive() {
        val summary = CarPlayCaptureSummary().received(fixture(1).copy(mManeuverType = 3)).received(fixture())
        assertEquals(1L, summary.candidates)
        assertEquals(1L, summary.inactive)
        assertEquals(CarPlayCapturePayloadKind.INACTIVE, summary.lastKind)
    }

    @Test fun classificationPreservesAllRawFields() {
        val raw = fixture(1).copy(mDistanceBetweenManeuverDisplayStr = " 1,234 ",
            mDistanceBetweenManeuverDisplayUnits = 999, mManeuverType = 999, mTimeRemainingToDestination = Long.MAX_VALUE)
        val before = raw.copy()
        CarPlayCaptureSummary().received(raw)
        assertEquals(before, raw)
    }
}
