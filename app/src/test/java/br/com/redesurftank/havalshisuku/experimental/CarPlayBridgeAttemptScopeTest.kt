package br.com.redesurftank.havalshisuku.experimental

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayBridgeAttemptScopeTest {
    private val old = "boot-a-100-200"
    private val fresh = "boot-a-101-300"
    private val revision = "74148-aaaaaaaaaaaa-bbbbbbbbbbbb"

    @Test fun sameProcessCannotSpendASecondAutomaticAttempt() {
        assertEquals(1, CarPlayBridgeAttemptScope.attemptsForProcess(old, old))
        assertTrue(CarPlayBridgeAttemptScope.failureCircuitOpen(old, revision, old, revision))
    }

    @Test fun replacementOemProcessGetsItsOwnSingleAttemptWithoutAnAndroidReboot() {
        assertEquals(0, CarPlayBridgeAttemptScope.attemptsForProcess(fresh, old))
        assertFalse(CarPlayBridgeAttemptScope.failureCircuitOpen(fresh, revision, old, revision))
        assertEquals("$fresh-$revision", CarPlayBridgeAttemptScope.markerName(fresh, revision))
    }

    @Test fun missingOrMismatchedStateNeverConsumesTheCurrentProcessBudget() {
        assertEquals(0, CarPlayBridgeAttemptScope.attemptsForProcess(fresh, null))
        assertEquals(0, CarPlayBridgeAttemptScope.attemptsForProcess(null, old))
        assertFalse(CarPlayBridgeAttemptScope.failureCircuitOpen(fresh, revision, fresh, "other"))
    }
}
