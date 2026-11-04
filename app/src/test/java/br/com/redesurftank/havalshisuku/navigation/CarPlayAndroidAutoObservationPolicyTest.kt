package br.com.redesurftank.havalshisuku.navigation

import org.junit.Assert.*
import org.junit.Test

class CarPlayAndroidAutoObservationPolicyTest {
    private fun read(window: CarPlayAndroidAutoObservationPolicy, at: Long, status: Int?,
        epoch: Long = 1, boundAt: Long = 0, profile: Boolean = true, duration: Long = 0) =
        window.observe(profile, epoch, boundAt, at, at + duration, status)

    @Test fun noDeviceOrPowerNeedsExactProfileEpochSettleAndTwoSpacedSamples() {
        val window = CarPlayAndroidAutoObservationPolicy()
        assertNull(read(window, 1000, -1, profile = false).active)
        assertNull(read(window, 500, -1).active)
        assertNull(read(window, 1000, -1).active)
        assertEquals(false, read(window, 1500, -1).active)
        assertEquals("NO_DEVICE_OR_POWER", read(window, 2000, -1).reason)
    }

    @Test fun linkedStatesWinImmediatelyWithoutWaitingForNegativeSettling() {
        for (status in listOf(3, 7, 8)) {
            assertEquals(true, read(CarPlayAndroidAutoObservationPolicy(), 1, status).active)
        }
    }

    @Test fun allRecognizedInactiveStatesRequireRepeatedEvidence() {
        for (status in listOf(-1, 1, 5, 6, 10)) {
            val window = CarPlayAndroidAutoObservationPolicy()
            assertNull(read(window, 1000, status).active)
            assertEquals(false, read(window, 1500, status).active)
        }
    }

    @Test fun transitionsUnknownAndReadErrorsRevokeTheNegativeWindow() {
        for (status in listOf(0, 2, 4, 9, 999, -2, null)) {
            val window = CarPlayAndroidAutoObservationPolicy()
            read(window, 1000, -1)
            assertEquals(false, read(window, 1500, -1).active)
            assertNull(read(window, 2000, status).active)
            assertNull(read(window, 2500, -1).active)
            assertEquals(false, read(window, 3000, -1).active)
        }
    }

    @Test fun newBinderEpochCannotBorrowTheOldEpochNegative() {
        val window = CarPlayAndroidAutoObservationPolicy()
        read(window, 1000, -1)
        assertEquals(false, read(window, 1500, -1).active)
        assertNull(read(window, 2000, -1, epoch = 2, boundAt = 2000).active)
        assertNull(read(window, 3000, -1, epoch = 2, boundAt = 2000).active)
        assertEquals(false, read(window, 3500, -1, epoch = 2, boundAt = 2000).active)
        assertEquals("OLD_EPOCH", read(window, 4000, -1, epoch = 1).reason)
    }

    @Test fun invalidChronologySlowReadsAndLongGapsCannotConfirmAbsence() {
        val window = CarPlayAndroidAutoObservationPolicy()
        read(window, 1000, -1)
        assertNull(read(window, 1200, -1).active)
        assertNull(read(window, 1700, -1, duration = 1501).active)
        assertNull(read(window, 2200, -1).active)
        assertNull(read(window, 4000, -1).active)
        assertEquals(false, read(window, 4500, -1).active)
        assertEquals("OUT_OF_ORDER", read(window, 4400, -1).reason)
        assertNull(read(CarPlayAndroidAutoObservationPolicy(), 1000, 3, duration = -1).active)
        assertNull(read(CarPlayAndroidAutoObservationPolicy(), 1000, 3, profile = false).active)
    }
}
