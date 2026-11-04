package br.com.redesurftank.havalshisuku.experimental

import br.com.redesurftank.havalshisuku.experimental.CarPlayBridgeBootstrapPolicy.Action
import br.com.redesurftank.havalshisuku.experimental.CarPlayBridgeBootstrapPolicy.Phase
import br.com.redesurftank.havalshisuku.experimental.CarPlayBridgeBootstrapPolicy.Reason
import br.com.redesurftank.havalshisuku.experimental.CarPlayBridgeBootstrapPolicy.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CarPlayBridgeBootstrapPolicyTest {
    private fun idle() = Snapshot(
        enabled = true, rootReady = true, assetsVerified = true,
        bootIdentityKnown = true, processPresent = true, processIdentityMatches = true,
        phase = Phase.NONE, ownersAlive = false, hasBridgeMapping = false,
        coldModulesAbsent = true, coldAttachSafe = true, observationAgeMs = 0L,
        carPlayActive = false, androidAutoActive = false, attemptsForProcess = 0,
        failureCircuitOpen = false, revisionMatches = true
    )

    private fun decide(snapshot: Snapshot) = CarPlayBridgeBootstrapPolicy.evaluate(snapshot)
    private fun action(snapshot: Snapshot) = decide(snapshot).action
    private fun assertDoesNotPrepare(snapshot: Snapshot) {
        assertFalse(action(snapshot) in setOf(Action.COLD_ATTACH, Action.COLD_RESTART))
    }

    @Test fun coldAndWarmIdleProcessesHaveDifferentActions() {
        assertEquals(Action.COLD_ATTACH, action(idle()))
        assertEquals(Action.COLD_RESTART, action(idle().copy(coldModulesAbsent = false)))
    }

    @Test fun enablingDoesNotSubstituteForRootAssetsOrIdentity() {
        assertEquals(Action.DISABLED, action(idle().copy(enabled = false)))
        assertEquals(Action.WAIT_ROOT, action(idle().copy(rootReady = false)))
        assertEquals(Action.WAIT_ASSETS, action(idle().copy(assetsVerified = false)))
        assertEquals(Action.WAIT_PROCESS, action(idle().copy(bootIdentityKnown = false)))
        assertEquals(Action.WAIT_PROCESS, action(idle().copy(processPresent = false)))
        assertEquals(Action.WAIT_PROCESS, action(idle().copy(processIdentityMatches = false)))
        assertEquals(Action.WAIT_PROCESS, action(idle().copy(coldModulesAbsent = null)))
    }

    @Test fun freshProjectionObservationHasAnInclusiveBoundAndRejectsUnknownOrFutureData() {
        val limit = CarPlayBridgeBootstrapPolicy.MAX_OBSERVATION_AGE_MS
        assertEquals(Action.COLD_ATTACH, action(idle().copy(observationAgeMs = limit)))
        for (age in listOf(null, -1L, limit + 1L, Long.MAX_VALUE)) {
            assertEquals(Reason.OBSERVATION_NOT_FRESH, decide(idle().copy(observationAgeMs = age)).reason)
        }
    }

    @Test fun warmConnectedOrUnknownProjectionStateStillBlocksRestart() {
        for (active in listOf(true, null)) {
            assertEquals(Reason.CARPLAY_NOT_KNOWN_INACTIVE,
                decide(idle().copy(coldModulesAbsent = false, coldAttachSafe = false,
                    carPlayActive = active)).reason)
            assertEquals(Reason.ANDROID_AUTO_NOT_KNOWN_INACTIVE,
                decide(idle().copy(coldModulesAbsent = false, coldAttachSafe = false,
                    androidAutoActive = active)).reason)
        }
    }

    @Test fun coldClearVisualProofCanArmBeforeRetainedDcmRowsSettle() {
        for (carPlay in listOf(true, null)) for (androidAuto in listOf(true, null)) {
            assertEquals(Action.COLD_ATTACH, action(idle().copy(
                carPlayActive = carPlay, androidAutoActive = androidAuto)))
        }
    }

    @Test fun coldAttachNeverUsesMissingOrActiveVisualEvidence() {
        for (active in listOf(true, null)) {
            assertEquals(Reason.CARPLAY_NOT_KNOWN_INACTIVE,
                decide(idle().copy(coldAttachSafe = false, carPlayActive = active)).reason)
            assertEquals(Reason.ANDROID_AUTO_NOT_KNOWN_INACTIVE,
                decide(idle().copy(coldAttachSafe = false, androidAutoActive = active)).reason)
        }
    }

    @Test fun readinessSurvivesAppRestartRootLossAndDeferredRevision() {
        val ready = idle().copy(phase = Phase.READY, hasBridgeMapping = true, ownersAlive = true)
        assertEquals(Action.PRESERVE_READY, action(ready))
        val afterAppRestart = ready.copy(rootReady = false, assetsVerified = false,
            revisionMatches = false, attemptsForProcess = 1,
            carPlayActive = true, androidAutoActive = null, observationAgeMs = null)
        assertEquals(Action.PRESERVE_READY, action(afterAppRestart))
        assertEquals(Reason.READY_PREVIOUS_REVISION, decide(afterAppRestart).reason)
    }

    @Test fun disableOrOpenCircuitDoesNotRequestDestructionOfLiveCallbacks() {
        val ready = idle().copy(phase = Phase.READY, hasBridgeMapping = true, ownersAlive = true,
            enabled = false, failureCircuitOpen = true)
        assertEquals(Action.PRESERVE_READY, action(ready))
    }

    @Test fun onlyOwnedInProgressArmingAndInstallationArePreserved() {
        for (phase in listOf(Phase.ARMED, Phase.INSTALLED)) {
            val pending = idle().copy(phase = phase, hasBridgeMapping = true, ownersAlive = true,
                attemptInProgress = true, attemptsForProcess = 1)
            assertEquals(Action.PRESERVE_PINNED, action(pending))
            assertEquals(Reason.ATTEMPT_NOT_IN_PROGRESS,
                decide(pending.copy(attemptInProgress = false)).reason)
            assertEquals(Reason.OWNERS_NOT_ALIVE, decide(pending.copy(ownersAlive = false)).reason)
        }
    }

    @Test fun stalePublishedStatesCannotAuthorizePidReuseOrNewBoot() {
        for (phase in listOf(Phase.ARMED, Phase.INSTALLED, Phase.READY)) {
            val old = idle().copy(phase = phase, hasBridgeMapping = true, ownersAlive = true,
                attemptInProgress = true)
            for (stale in listOf(old.copy(processIdentityMatches = false),
                old.copy(bootIdentityKnown = false), old.copy(processPresent = false))) {
                assertEquals(Action.BLOCKED, action(stale))
            }
        }
    }

    @Test fun missingMappingOrLostOwnerAfterPublicationIsBlocked() {
        val ready = idle().copy(phase = Phase.READY, hasBridgeMapping = true, ownersAlive = true)
        assertEquals(Reason.OWNERS_NOT_ALIVE, decide(ready.copy(ownersAlive = false)).reason)
        assertEquals(Reason.PUBLISHED_MAPPING_MISSING, decide(ready.copy(hasBridgeMapping = false)).reason)
    }

    @Test fun mappingAloneNeverPermitsAttachingOrRestarting() {
        assertEquals(Reason.UNEXPECTED_BRIDGE_MAPPING, decide(idle().copy(hasBridgeMapping = true)).reason)
        assertEquals(Reason.MAPPING_WITHOUT_LIVE_IDENTITY,
            decide(idle().copy(hasBridgeMapping = true, processIdentityMatches = false)).reason)
    }

    @Test fun failedUnknownAndPinnedFailuresCannotBecomeAutomaticRetries() {
        for (phase in listOf(Phase.FAILED, Phase.FAILED_PINNED, Phase.UNKNOWN)) {
            for (mapping in listOf(true, false)) {
                val failed = idle().copy(phase = phase, hasBridgeMapping = mapping)
                assertEquals(Action.BLOCKED, action(failed))
                assertDoesNotPrepare(failed.copy(revisionMatches = false, attemptsForProcess = 0))
            }
        }
    }

    @Test fun repeatedBootSignalsAndMalformedCountsCannotSpendAnotherAttempt() {
        for (attempts in listOf(-1, 1, 2, Int.MAX_VALUE)) {
            assertEquals(Action.BLOCKED, action(idle().copy(attemptsForProcess = attempts)))
        }
        assertEquals(Reason.FAILURE_CIRCUIT_OPEN, decide(idle().copy(failureCircuitOpen = true)).reason)
    }

    @Test fun aNewRevisionDoesNotSilentlyResetTheFailureBudget() {
        assertEquals(Action.BLOCKED, action(idle().copy(revisionMatches = false, attemptsForProcess = 1)))
        assertEquals(Action.BLOCKED, action(idle().copy(revisionMatches = false, failureCircuitOpen = true)))
        // The persistent owner explicitly resets state for a reviewed revision;
        // the policy itself never treats a different revision as a reset.
        assertEquals(Action.COLD_ATTACH, action(idle().copy(revisionMatches = false)))
    }
}
