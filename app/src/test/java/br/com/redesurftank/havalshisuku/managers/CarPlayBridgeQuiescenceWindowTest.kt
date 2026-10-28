package br.com.redesurftank.havalshisuku.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPlayBridgeQuiescenceWindowTest {
    private val window = CarPlayBridgeQuiescenceWindow()

    private fun sample(now: Long, empty: Boolean? = true, epoch: Long = 1L,
                       boundAt: Long = 0L): Boolean? = window.observe(epoch, boundAt, now, empty)

    @Test fun boundServiceMustSettleBeforeEmptySamplesCount() {
        assertNull(sample(3_000))
        assertNull(sample(4_000))
        assertNull(sample(4_999))
        assertNull(sample(5_000))
        assertNull(sample(6_000))
        assertEquals(true, sample(7_000))
    }

    @Test fun countAloneCannotSubstituteForTwoSecondsOfEvidence() {
        for (now in listOf(5_000L, 5_500L, 6_000L, 6_500L)) assertNull(sample(now))
        assertEquals(true, sample(7_000))
        assertEquals(true, sample(7_500))
    }

    @Test fun threeSamplesAreEnoughOnlyWithTheRequiredSpan() {
        assertNull(sample(5_000))
        assertNull(sample(6_000))
        assertEquals(true, sample(7_000))
    }

    @Test fun unknownBreaksTheEntireEmptySequence() {
        sample(5_000)
        sample(6_000)
        assertNull(sample(6_500, null))
        assertNull(sample(7_000))
        assertNull(sample(8_000))
        assertEquals(true, sample(9_000))
    }

    @Test fun anyObservedPhoneOrProjectionBlocksAndResetsReadiness() {
        sample(5_000)
        sample(6_000)
        assertEquals(true, sample(7_000))
        assertEquals(false, sample(7_500, false))
        assertNull(sample(8_000))
        assertNull(sample(9_000))
        assertEquals(true, sample(10_000))
    }

    @Test fun positivePresenceIsReportedEvenWhileTheBindingSettles() {
        assertEquals(false, sample(1_000, false))
        assertNull(sample(2_000))
    }

    @Test fun reconnectCannotInheritThePreviousBindingEvidence() {
        sample(5_000)
        sample(6_000)
        assertEquals(true, sample(7_000))
        assertNull(sample(7_500, epoch = 2, boundAt = 7_000))
        assertNull(sample(12_000, epoch = 2, boundAt = 7_000))
        assertNull(sample(13_000, epoch = 2, boundAt = 7_000))
        assertEquals(true, sample(14_000, epoch = 2, boundAt = 7_000))
    }

    @Test fun aLongGapStartsFreshEvenIfEveryReplyIsEmpty() {
        sample(5_000)
        sample(6_000)
        assertNull(sample(7_501))
        assertNull(sample(8_501))
        assertEquals(true, sample(9_501))
    }

    @Test fun gapBoundaryIsInclusiveButStillRequiresThreeSamples() {
        assertNull(sample(5_000))
        assertNull(sample(6_500))
        assertEquals(true, sample(8_000))
    }

    @Test fun repeatedOrRegressingClocksCannotAccumulateEvidence() {
        sample(5_000)
        sample(6_000)
        assertNull(sample(6_000))
        assertNull(sample(5_000))
        assertNull(sample(6_000))
        assertEquals(true, sample(7_000))
    }

    @Test fun unknownOrFutureBindingTimeCannotAuthorizeAbsence() {
        for (boundAt in listOf(-1L, 8_000L)) {
            window.reset()
            assertNull(sample(5_000, boundAt = boundAt))
            assertNull(sample(6_000, boundAt = boundAt))
            assertNull(sample(7_000, boundAt = boundAt))
        }
    }

    @Test fun explicitResetRequiresAnEntireNewWindow() {
        sample(5_000)
        sample(6_000)
        assertEquals(true, sample(7_000))
        window.reset()
        assertNull(sample(7_500))
        assertNull(sample(8_500))
        assertEquals(true, sample(9_500))
    }
}
