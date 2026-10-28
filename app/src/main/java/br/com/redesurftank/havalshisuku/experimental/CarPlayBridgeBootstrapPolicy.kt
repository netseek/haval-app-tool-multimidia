package br.com.redesurftank.havalshisuku.experimental

/** Decisions only: this policy never starts, stops, injects or unloads a process. */
object CarPlayBridgeBootstrapPolicy {
    const val MAX_OBSERVATION_AGE_MS = 15_000L
    const val MAX_ATTEMPTS_PER_PROCESS = 1

    enum class Phase { NONE, ARMED, INSTALLED, READY, FAILED, FAILED_PINNED, UNKNOWN }

    enum class Action {
        DISABLED, WAIT_ROOT, WAIT_ASSETS, WAIT_PROCESS, WAIT_SAFE,
        PRESERVE_READY, PRESERVE_PINNED, COLD_ATTACH, COLD_RESTART, BLOCKED
    }

    enum class Reason {
        DISABLED, ALREADY_READY, READY_PREVIOUS_REVISION, OWNED_ATTEMPT_IN_PROGRESS,
        PINNED_FAILURE, UNKNOWN_RUNTIME_STATE, MAPPING_WITHOUT_LIVE_IDENTITY,
        UNEXPECTED_BRIDGE_MAPPING, PUBLISHED_IDENTITY_UNTRUSTED, PUBLISHED_MAPPING_MISSING,
        OWNERS_NOT_ALIVE, ATTEMPT_NOT_IN_PROGRESS, PRIOR_FAILURE, FAILURE_CIRCUIT_OPEN,
        INVALID_ATTEMPT_COUNT, ATTEMPT_BUDGET_EXHAUSTED, ROOT_UNAVAILABLE,
        ASSETS_UNVERIFIED, BOOT_IDENTITY_UNKNOWN, PROCESS_ABSENT,
        PROCESS_IDENTITY_UNTRUSTED, COLD_STATE_UNKNOWN, OBSERVATION_NOT_FRESH,
        CARPLAY_NOT_KNOWN_INACTIVE, ANDROID_AUTO_NOT_KNOWN_INACTIVE,
        COLD_PROCESS, SAFE_IDLE_RESTART
    }

    data class Decision(val action: Action, val reason: Reason)

    /**
     * Facts must come from one fresh local observation. processIdentityMatches
     * includes the kernel boot ID, PID and /proc starttime, not PID alone. Old
     * persisted phases must be discarded by the observer on an identity change.
     * ownersAlive describes the required owner(s) for the observed phase.
     * Projection activity includes a connected session, even with its UI hidden.
     */
    data class Snapshot(
        val enabled: Boolean,
        val rootReady: Boolean,
        val assetsVerified: Boolean,
        val bootIdentityKnown: Boolean,
        val processPresent: Boolean,
        val processIdentityMatches: Boolean,
        val phase: Phase,
        val ownersAlive: Boolean,
        val hasBridgeMapping: Boolean,
        val coldModulesAbsent: Boolean?,
        val coldAttachSafe: Boolean,
        val observationAgeMs: Long?,
        val carPlayActive: Boolean?,
        val androidAutoActive: Boolean?,
        val attemptsForProcess: Int,
        val failureCircuitOpen: Boolean,
        val revisionMatches: Boolean,
        val attemptInProgress: Boolean = false
    )

    fun evaluate(snapshot: Snapshot): Decision {
        return with(snapshot) {
            fun decide(action: Action, reason: Reason) = Decision(action, reason)
            fun blocked(reason: Reason) = decide(Action.BLOCKED, reason)

            if (phase == Phase.FAILED_PINNED) return blocked(Reason.PINNED_FAILURE)
            if (phase == Phase.UNKNOWN) return blocked(Reason.UNKNOWN_RUNTIME_STATE)
            val liveIdentity = bootIdentityKnown && processPresent && processIdentityMatches
            if (hasBridgeMapping && !liveIdentity) return blocked(Reason.MAPPING_WITHOUT_LIVE_IDENTITY)

            // Preserving published callbacks takes precedence over enablement,
            // upgrade, root availability and attempt budgets. None permits unloading.
            if (phase == Phase.READY || phase == Phase.INSTALLED || phase == Phase.ARMED) {
                if (!liveIdentity) return blocked(Reason.PUBLISHED_IDENTITY_UNTRUSTED)
                if (!hasBridgeMapping) return blocked(Reason.PUBLISHED_MAPPING_MISSING)
                if (!ownersAlive) return blocked(Reason.OWNERS_NOT_ALIVE)
                if (phase == Phase.READY) {
                    return decide(Action.PRESERVE_READY,
                        if (revisionMatches) Reason.ALREADY_READY else Reason.READY_PREVIOUS_REVISION)
                }
                if (!attemptInProgress) return blocked(Reason.ATTEMPT_NOT_IN_PROGRESS)
                return decide(Action.PRESERVE_PINNED, Reason.OWNED_ATTEMPT_IN_PROGRESS)
            }
            if (hasBridgeMapping) return blocked(Reason.UNEXPECTED_BRIDGE_MAPPING)
            if (phase == Phase.FAILED) return blocked(Reason.PRIOR_FAILURE)
            if (!enabled) return decide(Action.DISABLED, Reason.DISABLED)
            if (failureCircuitOpen) return blocked(Reason.FAILURE_CIRCUIT_OPEN)
            if (attemptsForProcess < 0) return blocked(Reason.INVALID_ATTEMPT_COUNT)
            if (attemptsForProcess >= MAX_ATTEMPTS_PER_PROCESS) return blocked(Reason.ATTEMPT_BUDGET_EXHAUSTED)
            if (!rootReady) return decide(Action.WAIT_ROOT, Reason.ROOT_UNAVAILABLE)
            if (!assetsVerified) return decide(Action.WAIT_ASSETS, Reason.ASSETS_UNVERIFIED)
            if (!bootIdentityKnown) return decide(Action.WAIT_PROCESS, Reason.BOOT_IDENTITY_UNKNOWN)
            if (!processPresent) return decide(Action.WAIT_PROCESS, Reason.PROCESS_ABSENT)
            if (!processIdentityMatches) return decide(Action.WAIT_PROCESS, Reason.PROCESS_IDENTITY_UNTRUSTED)
            if (coldModulesAbsent == null) return decide(Action.WAIT_PROCESS, Reason.COLD_STATE_UNKNOWN)
            if (observationAgeMs == null || observationAgeMs !in 0L..MAX_OBSERVATION_AGE_MS) {
                return decide(Action.WAIT_SAFE, Reason.OBSERVATION_NOT_FRESH)
            }
            // A genuinely cold OEM process cannot own a running projection yet. If the
            // current visual sample is explicitly clear, arm the reviewed cold interceptor
            // before consulting DCM's retained connection rows. This admits no warm restart:
            // warm processes still require the full DCM/Bluetooth quiescence proof below.
            if (coldModulesAbsent && coldAttachSafe) {
                return decide(Action.COLD_ATTACH, Reason.COLD_PROCESS)
            }
            if (carPlayActive != false) return decide(Action.WAIT_SAFE, Reason.CARPLAY_NOT_KNOWN_INACTIVE)
            if (androidAutoActive != false) return decide(Action.WAIT_SAFE, Reason.ANDROID_AUTO_NOT_KNOWN_INACTIVE)
            if (coldModulesAbsent) decide(Action.COLD_ATTACH, Reason.COLD_PROCESS)
            else decide(Action.COLD_RESTART, Reason.SAFE_IDLE_RESTART)
        }
    }
}
