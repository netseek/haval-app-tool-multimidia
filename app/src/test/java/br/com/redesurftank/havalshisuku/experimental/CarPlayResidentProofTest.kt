package br.com.redesurftank.havalshisuku.experimental

import org.junit.Assert.*
import org.junit.Test

class CarPlayResidentProofTest {
    private val boot = "00112233-4455-6677-8899-aabbccddeeff"
    private val ticket = "10112233-4455-6677-8899-aabbccddeeff"
    private val instance = "20112233-4455-6677-8899-aabbccddeeff"
    private val nonce = "30112233-4455-6677-8899-aabbccddeeff"
    private val environment = CarPlayResidentProof.Environment(boot, 100, 1000, "b".repeat(64),
        CarPlayResidentProof.PATCHED_INJECTOR_SHA, "1:123:51758352:1000:1000:0:500")
    private val binding = CarPlayResidentProof.Binding("native", boot, 1234, 5678, "74138-123456789abc-abcdef012345",
        ticket, instance, "a".repeat(64), "/data/local/tmp/impulse-carplay/runtime/$boot-1234-5678",
        "/data/local/tmp/impulse-carplay/versions/74138-123456789abc-abcdef012345", environment, 20_000)
    private fun launch(b: CarPlayResidentProof.Binding = binding) =
        CarPlayResidentProof.Receipt(b, 2345, 6000, CarPlayResidentProof.commandSha256(b),
            commandEvidence = "declared-before-exec-v1", supervisorSha256 = CarPlayResidentProof.supervisorSha256(b))
    private fun exit(b: CarPlayResidentProof.Binding = binding) = launch(b).copy(exitCode = 0, exitElapsedMs = 10_000)
    private fun challenge() = CarPlayResidentProof.Challenge(instance, nonce, 10_100)
    private fun sample(sequence: Long, elapsed: Long) = CarPlayResidentProof.Sample(sequence, elapsed,
        1_000_000 + elapsed, "armed", 0, true, true)
    private fun response(b: CarPlayResidentProof.Binding = binding) = CarPlayResidentProof.Response(b, nonce, false,
        listOf(sample(1, 10_200), sample(2, 10_450)))
    private fun verify(b: CarPlayResidentProof.Binding = binding,
        start: CarPlayResidentProof.Receipt = launch(b), end: CarPlayResidentProof.Receipt = exit(b),
        challenge: CarPlayResidentProof.Challenge = challenge(), response: CarPlayResidentProof.Response = response(b),
        now: Long = 10_500, wall: Long = 1_010_500) =
        CarPlayResidentProof.checkResponse(b, start, end, challenge, response, now, wall)
    private fun rejects(action: () -> Unit) {
        try { action(); fail("Invalid residency proof accepted") }
        catch (_: IllegalArgumentException) {}
    }

    private fun pending(action: () -> Unit) {
        try { action(); fail("Unfinished proof accepted") }
        catch (_: CarPlayResidentProof.Pending) {}
    }

    @Test fun coldGateBudgetUsesProcessBirthAndActualClockTickFrequency() {
        for ((ticks, frequency) in listOf(3477L to 100L, 34_770L to 1000L)) {
            val budget = CarPlayResidentProof.coldGateBudget(ticks, frequency, 41_086, 362_580)
            assertEquals(43_770L, budget.proofDeadlineMs)
            assertEquals(44_770L, budget.releaseDeadlineMs)
        }
        val fractional = CarPlayResidentProof.coldGateBudget(100, 3, 34_000, 100_000)
        assertEquals(42_333L, fractional.proofDeadlineMs) // Floor conversion never lengthens the gate.
        assertEquals(43_333L, fractional.releaseDeadlineMs)
    }

    @Test fun coldGateBudgetKeepsReleaseReserveAndOverallAttemptLimit() {
        val budget = CarPlayResidentProof.coldGateBudget(3477, 100, 40_000, 42_000)
        assertEquals(41_000L, budget.proofDeadlineMs)
        assertEquals(42_000L, budget.releaseDeadlineMs)
        rejects { CarPlayResidentProof.coldGateBudget(3477, 100, 41_000, 42_000) }
        rejects { CarPlayResidentProof.coldGateBudget(3477, 100, 43_770, 362_580) }
        rejects { CarPlayResidentProof.coldGateBudget(3477, 100, 50_000, 362_580) } // Old cold attach; no new window.
    }

    @Test fun invalidOrOverflowingStartupClocksCannotCreateAWindow() {
        for ((ticks, frequency) in listOf(0L to 100L, -1L to 100L, 3477L to 0L,
            3477L to -1L, Long.MAX_VALUE to 100L)) {
            rejects { CarPlayResidentProof.coldGateBudget(ticks, frequency, 40_000, 362_580) }
        }
        rejects { CarPlayResidentProof.coldGateBudget(4001, 100, 40_000, 362_580) } // Future birth.
        rejects { CarPlayResidentProof.coldGateBudget(3477, 100, -1, 362_580) }
        for (attempt in listOf(Long.MIN_VALUE, -1L, 0L, 40_000L)) {
            rejects { CarPlayResidentProof.coldGateBudget(3477, 100, 40_000, attempt) }
        }
    }

    @Test fun startupRootCallsSpendRemainingBudgetRatherThanTwoIndependentTimeouts() {
        assertEquals(5000L, CarPlayResidentProof.rootTimeout(43_770, 37_000))
        assertEquals(2770L, CarPlayResidentProof.rootTimeout(43_770, 41_000))
        assertEquals(1L, CarPlayResidentProof.rootTimeout(43_770, 43_769))
        rejects { CarPlayResidentProof.rootTimeout(43_770, 43_770) }
        rejects { CarPlayResidentProof.rootTimeout(43_770, 44_000) }
    }

    @Test fun conservativeStartupBudgetAcceptsInWindowCompletionAndStillRejectsLateSuccess() {
        val deadline = CarPlayResidentProof.coldGateBudget(3477, 100, 37_170, 362_580).proofDeadlineMs
        var now = 37_170L
        assertEquals("verified", CarPlayResidentProof.await(deadline, { now }, {}) {
            now = 41_086L; "verified"
        })
        try {
            now = 41_086
            CarPlayResidentProof.await(deadline, { now }, {}) { now = deadline; "late" }
            fail("A successful result at the conservative deadline was accepted")
        } catch (_: IllegalStateException) {}
    }

    @Test fun generatedStartupGuardRejectsLateAndStaleProofBeforeMutation() {
        val uptime = java.io.File.createTempFile("carplay-gate-clock-", ".txt")
        try {
            fun run(clock: String, sampleElapsed: Long): Int {
                uptime.writeText("$clock 0.00\n")
                val shell = CarPlayResidentProof.deadlineGuard(44_770, sampleElapsed)
                    .replace("/proc/uptime", CarPlayResidentProof.quote(uptime.absolutePath))
                val process = ProcessBuilder("sh", "-c", shell + "printf allowed")
                    .redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                val result = process.waitFor()
                assertEquals(result == 0, output == "allowed")
                return result
            }
            assertEquals(0, run("44.76", 42_000))
            assertNotEquals(0, run("44.77", 42_000))
            assertNotEquals(0, run("44.78", 42_000))
            assertNotEquals(0, run("44.00", 40_000))
            assertNotEquals(0, run("44.00", 45_000))
            assertNotEquals(0, run("0.00", 0))
        } finally { uptime.delete() }
    }

    @Test fun onlyPostExitFreshAdvancingSamplesProveNativeResidence() {
        val proof = verify()
        assertTrue(CarPlayResidentProof.nativeArmed(proof))
        assertFalse(CarPlayResidentProof.nativeInstalled(proof))
        val installed = response().copy(samples = response().samples.map { it.copy(phase = "installed-pinned", replacements = 9) })
        assertTrue(CarPlayResidentProof.nativeInstalled(verify(response = installed)))
    }

    @Test fun eachIdentityFieldMustMatchReceiptAndResponse() {
        val variants = listOf(binding.copy(role = "java"), binding.copy(bootId = ticket),
            binding.copy(targetPid = 1235), binding.copy(targetStartTime = 5679),
            binding.copy(revision = "74137-123456789abc-abcdef012345"), binding.copy(ticket = boot),
            binding.copy(instance = nonce), binding.copy(scriptSha256 = "b".repeat(64)),
            binding.copy(runtimeDirectory = binding.runtimeDirectory + "-other"),
            binding.copy(payloadDirectory = binding.payloadDirectory + "-other"))
        for (bad in variants) {
            rejects { verify(response = response().copy(binding = bad)) }
            rejects { verify(start = launch().copy(binding = bad)) }
            rejects { verify(end = exit().copy(binding = bad)) }
        }
    }

    @Test fun exactExitedChildAndArgumentsAreMandatory() {
        for (bad in listOf(launch().copy(childPid = 0), launch().copy(childStartTime = 0),
            launch().copy(childStartTime = 5677), launch().copy(commandSha256 = "0".repeat(64)))) {
            rejects { verify(start = bad) }
        }
        for (bad in listOf(exit().copy(childPid = 2346), exit().copy(childStartTime = 6001),
            exit().copy(exitCode = null), exit().copy(exitCode = 143), exit().copy(exitCode = 137),
            exit().copy(exitElapsedMs = null), exit().copy(exitElapsedMs = 0),
            exit().copy(commandSha256 = "0".repeat(64)))) rejects { verify(end = bad) }
        rejects { verify(start = launch().copy(childStartTime = 5677), end = exit().copy(childStartTime = 5677)) }
        rejects { verify(challenge = challenge().copy(issuedElapsedMs = 9999)) }
        rejects { verify(challenge = challenge().copy(issuedElapsedMs = 10_501)) }
    }

    @Test fun birthEvidenceMustBeExplicitAndBindTheReviewedSupervisor() {
        for (bad in listOf(launch().copy(commandEvidence = ""), launch().copy(commandEvidence = "observed-argv"),
            launch().copy(supervisorSha256 = "0".repeat(64)))) rejects { verify(start = bad) }
        for (bad in listOf(exit().copy(commandEvidence = ""), exit().copy(supervisorSha256 = "0".repeat(64)))) {
            rejects { verify(end = bad) }
        }
    }

    @Test fun replayDisposedOrIncompleteResponseNeverProvesResidence() {
        for (bad in listOf(response().copy(disposing = true),
            response().copy(disposing = null),
            response().copy(samples = response().samples + sample(3, 10_500)))) rejects { verify(response = bad) }
        rejects { verify(challenge = challenge().copy(instance = ticket)) }
        rejects { verify(challenge = challenge().copy(nonce = "")) }
    }

    @Test fun bothSamplesMustAdvanceAfterChallengeAndRemainFresh() {
        val first = response().samples[0]
        val second = response().samples[1]
        for (bad in listOf(first.copy(sequence = 0), first.copy(elapsedMs = 10_099),
            first.copy(elapsedMs = 7500 - 1), first.copy(wallMs = Long.MIN_VALUE), first.copy(wallMs = -1),
            first.copy(elapsedMs = 10_501), first.copy(wallMs = 1_010_501))) {
            rejects { verify(response = response().copy(samples = listOf(bad, second))) }
        }
        for (bad in listOf(second.copy(sequence = 1), second.copy(sequence = 0), second.copy(elapsedMs = 10_299))) {
            rejects { verify(response = response().copy(samples = listOf(first, bad))) }
        }
        val boundary = response().copy(samples = listOf(sample(1, 10_100), sample(2, 10_200)))
        verify(response = boundary, now = 13_100, wall = 1_013_100)
        pending { verify(response = boundary, now = 13_101, wall = 1_013_101) }
        pending { verify(response = response().copy(samples = listOf(first.copy(wallMs = 1_007_499), second))) }
    }

    @Test fun everyNativeSampleRequiresPinExactPhaseCountAndNoFailure() {
        val valid = sample(1, 10_200)
        for (bad in listOf(valid.copy(phase = "failed-pinned"), valid.copy(phase = "none"),
            valid.copy(pinned = false), valid.copy(failureAbsent = false), valid.copy(replacements = 9),
            valid.copy(phase = "installed-pinned", replacements = 8),
            valid.copy(phase = "installed-pinned", replacements = -1))) {
            rejects { verify(response = response().copy(samples = listOf(bad, sample(2, 10_450)))) }
        }
    }

    @Test fun javaResidenceNeedsTwoArmedSamplesFromJavaRole() {
        val java = binding.copy(role = "java")
        val ready = response(java).copy(samples = response().samples.map { it.copy(armed = true) })
        verify(b = java, response = ready)
        for (index in 0..1) pending {
            verify(b = java, response = ready.copy(samples = ready.samples.mapIndexed { i, value ->
                if (i == index) value.copy(armed = false) else value
            }))
        }
    }

    @Test fun delayedResponseAndInstallingKeepSameAttemptAndEligibilityUntilFreshCompletion() {
        var now = 10_100L
        var calls = 0
        val gate = CarPlaySafetyPublicationGate()
        val key = CarPlaySafetyPublicationGate.Key(boot, binding.targetPid, binding.targetStartTime, ticket)
        val result = CarPlayResidentProof.await(20_000, { now }, { now += 80 }) {
            calls++
            assertEquals(CarPlaySafetyPublicationGate.Decision.ALLOW,
                gate.decide(key, key, true, true, true, "preparing"))
            when (calls) {
                1 -> { now += 1200; throw CarPlayResidentProof.Pending("first poll missed") }
                2 -> verify(response = response().copy(samples = response().samples.map { it.copy(phase = "installing") }), now = now, wall = 1_000_000 + now)
                else -> verify(response = response().copy(samples = response().samples.map { it.copy(phase = "installed-pinned", replacements = 9) }), now = now, wall = 1_000_000 + now)
            }
        }
        assertEquals(3, calls)
        assertTrue(CarPlayResidentProof.nativeInstalled(result))
        assertEquals(CarPlaySafetyPublicationGate.Decision.DENY,
            gate.decide(key, key, false, true, true, "preparing")) // live quiescence loss still revokes
    }

    @Test fun fatalIdentityReceiptOrDisposalIsNotRetriedEvenAfterOnePendingPoll() {
        for (failure in listOf<() -> Unit>(
            { verify(response = response().copy(binding = binding.copy(ticket = boot))) },
            { verify(end = exit().copy(exitCode = 137)) },
            { verify(response = response().copy(nonce = ticket, disposing = true)) },
            { verify(response = response().copy(nonce = ticket, samples = response().samples.map { it.copy(phase = "failed") })) },
            { verify(response = response().copy(nonce = ticket, samples = response().samples.map { it.copy(pinned = false) })) }
        )) {
            var now = 0L
            var calls = 0
            rejects {
                CarPlayResidentProof.await(10_000, { now }, { now += 80 }) {
                    calls++
                    if (calls == 1) throw CarPlayResidentProof.Pending("not yet")
                    failure()
                }
            }
            assertEquals(2, calls)
        }
    }

    @Test fun deadlineExpiresWithoutExtendingAttemptAndRejectsLateSuccessfulReturn() {
        var now = 0L
        var calls = 0
        try {
            CarPlayResidentProof.await(200, { now }, { now += 100 }) {
                calls++; throw CarPlayResidentProof.Pending("not yet")
            }
            fail("Pending exceeded attempt deadline")
        } catch (_: IllegalStateException) {}
        assertEquals(2, calls)
        try {
            now = 0
            CarPlayResidentProof.await(200, { now }, {}) { now = 201; "late" }
            fail("late result accepted")
        } catch (_: IllegalStateException) {}
    }

    @Test fun pendingNonceSurvivesFirstPollAndOnlyRenewsAfterLease() {
        var generated = 0
        val create = { generated++; nonce }
        val first = CarPlayResidentProof.challenge(instance, null, 10_000, create)
        assertSame(first, CarPlayResidentProof.challenge(instance, first, 11_200, create))
        assertSame(first, CarPlayResidentProof.challenge(instance, first, 12_500, create))
        assertNotSame(first, CarPlayResidentProof.challenge(instance, first, 12_501, create))
        assertEquals(2, generated)
        pending { verify(response = response().copy(nonce = ticket)) }
        pending { verify(response = response().copy(samples = response().samples.take(1))) }
        pending { verify(response = response().copy(samples = emptyList())) }
    }

    @Test fun recoveryWaitsForKnownArmedWithoutMappingAndNeverPreparesForeignOrUnknownState() {
        assertEquals(CarPlayResidentProof.Recovery.PENDING, CarPlayResidentProof.recovery("armed", null, true))
        assertEquals(CarPlayResidentProof.Recovery.PENDING, CarPlayResidentProof.recovery("armed", true, false))
        assertEquals(CarPlayResidentProof.Recovery.PENDING, CarPlayResidentProof.recovery("installing", true, true))
        assertEquals(CarPlayResidentProof.Recovery.INSTALLED, CarPlayResidentProof.recovery("installed-pinned", true, true))
        for (phase in listOf("none", "unknown", "failed", "failed-pinned")) rejects {
            CarPlayResidentProof.recovery(phase, true, false)
        }
        rejects { CarPlayResidentProof.recovery("armed", false, true) }
    }

    @Test fun proofMustRemainJointlyFreshAfterTheOtherRoleAndIdentityChecksFinish() {
        val proof = verify()
        assertTrue(CarPlayResidentProof.fresh(proof, 13_200, 1_013_200))
        assertFalse(CarPlayResidentProof.fresh(proof, 13_201, 1_013_201))
        assertFalse(CarPlayResidentProof.fresh(proof, 10_449, 1_010_449))
        assertFalse(CarPlayResidentProof.fresh(proof.copy(firstWallMs = Long.MIN_VALUE), 10_500, 1_010_500))
    }

    @Test fun supervisorUsesExactEternalizeParametersAndWaitsWithoutRetry() {
        val arguments = CarPlayResidentProof.arguments(binding, "${binding.payloadDirectory}/cpbridge-inject")
        assertEquals(listOf("-p", "1234", "-s", binding.scriptPath, "-R", "qjs", "-e", "-P"), arguments.drop(1).dropLast(1))
        assertEquals("{\"impulseResidency\":" + CarPlayResidentProof.bindingJson(binding) + "}", arguments.last())
        val shell = CarPlayResidentProof.supervisor(binding)
        assertTrue(shell.contains("wait \"\$cpb_child\""))
        assertFalse(shell.contains("/proc/\$cpb_child/cmdline"))
        assertTrue(shell.contains("declared-before-exec-v1"))
        assertTrue(shell.contains("exec "))
        assertTrue(shell.indexOf("mv '" + binding.runtimeDirectory + "/native-launch.json.next'") <
            shell.indexOf("mv '" + binding.runtimeDirectory + "/native-exec-"))
        assertTrue(shell.contains("childStartTime"))
        assertTrue(shell.contains("exitElapsedMs"))
        assertFalse(shell.contains("kill "))
        assertEquals(1, Regex("exec '" + Regex.escape("${binding.payloadDirectory}/cpbridge-inject") + "'").findAll(shell).count())
        rejects { CarPlayResidentProof.arguments(binding, "bad'; touch /tmp/injected; '") }
        rejects { CarPlayResidentProof.bindingJson(binding.copy(revision = "bad'; touch x")) }
    }
    @Test fun generatedSupervisorPassesHostShellSyntaxCheck() {
        val file = java.io.File.createTempFile("carplay-residency-", ".sh")
        try {
            file.writeText(CarPlayResidentProof.supervisor(binding))
            val process = ProcessBuilder("sh", "-n", file.absolutePath).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.waitFor())
        } finally { file.delete() }
    }

    @Test fun preflightRequiresTheExactPatchedExecutableAndAnUnchangedFrameworkPolicyAndVersion() {
        CarPlayResidentProof.checkPreflight(environment, environment, "17.5.2")
        for (changed in listOf(environment.copy(bootId = ticket), environment.copy(systemServerPid = 101),
            environment.copy(systemServerStart = 1001), environment.copy(policySha256 = "c".repeat(64)),
            environment.copy(injectorFingerprint = "1:124:51758352:1000:1000:0:500"))) {
            rejects { CarPlayResidentProof.checkPreflight(environment, changed, "17.5.2") }
        }
        rejects { CarPlayResidentProof.checkPreflight(environment, environment, "17.5.3") }
        rejects { CarPlayResidentProof.checkEnvironment(environment.copy(injectorSha256 = CarPlayResidentProof.ORIGINAL_INJECTOR_SHA)) }
        rejects { CarPlayResidentProof.checkEnvironment(environment.copy(injectorFingerprint = "1:123:51758352:1000:1000:1000:500")) }
        rejects { CarPlayResidentProof.checkEnvironment(environment.copy(injectorFingerprint = "1:123:51758352:1000:1000:0:700")) }
    }

    @Test fun environmentIsBoundByTheSupervisorWithoutChangingTheResidencyWireSchemaOrArguments() {
        val changed = binding.copy(environment = environment.copy(policySha256 = "c".repeat(64)))
        assertNotEquals(CarPlayResidentProof.supervisorSha256(binding), CarPlayResidentProof.supervisorSha256(changed))
        assertEquals(CarPlayResidentProof.arguments(binding, "${binding.payloadDirectory}/cpbridge-inject"),
            CarPlayResidentProof.arguments(changed, "${changed.payloadDirectory}/cpbridge-inject"))
        assertFalse(CarPlayResidentProof.bindingJson(binding).contains("policySha256"))
        rejects { CarPlayResidentProof.checkLaunch(changed, launch()) }
        rejects { CarPlayResidentProof.checkBinding(binding.copy(execDeadlineMs = 0)) }
    }

    @Test fun supervisorRechecksTheApprovedEnvironmentAndDeadlineAfterTheBirthBarrierBeforeExec() {
        val shell = CarPlayResidentProof.supervisor(binding)
        val barrier = shell.indexOf("test \"\$(cat '" + binding.runtimeDirectory + "/native-exec-")
        val enforcement = shell.indexOf("cat /sys/fs/selinux/enforce", barrier)
        val deadline = shell.indexOf("test \"\$cpb_now\" -gt 0", enforcement)
        val exec = shell.indexOf("exec '${binding.payloadDirectory}/cpbridge-inject'")
        assertTrue(barrier >= 0 && enforcement > barrier && deadline > enforcement && exec > deadline)
        assertTrue(shell.contains(environment.policySha256))
        assertTrue(shell.contains(environment.injectorFingerprint))
        assertTrue(shell.contains("/preflight/${boot}-${binding.revision}/success.json"))
        assertFalse(shell.contains("sha256sum '${binding.payloadDirectory}/cpbridge-inject'"))
        assertFalse(shell.contains("setenforce"))
    }

    @Test fun preflightReceiptIsDeterministicAndSeparateFromTheAgentBinding() {
        val receipt = CarPlayResidentProof.preflightReceipt(environment, binding.revision)
        assertTrue(receipt.contains("\"policyMode\":\"permissive-existing-policy-v1\""))
        assertTrue(receipt.contains("\"state\":\"success\""))
        assertTrue(receipt.contains(environment.injectorSha256))
        assertEquals(receipt, CarPlayResidentProof.preflightReceipt(environment, binding.revision))
        rejects { CarPlayResidentProof.preflightReceipt(environment, "invalid'; touch /tmp/x") }
    }

}
