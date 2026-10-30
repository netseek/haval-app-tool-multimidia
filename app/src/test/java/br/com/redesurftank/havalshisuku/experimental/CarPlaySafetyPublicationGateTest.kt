package br.com.redesurftank.havalshisuku.experimental

import br.com.redesurftank.havalshisuku.experimental.CarPlaySafetyPublicationGate.Decision
import br.com.redesurftank.havalshisuku.experimental.CarPlaySafetyPublicationGate.Key
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlaySafetyPublicationGateTest {
    private val key = Key("12345678-abcd-4abc-8def-123456789abc", 21258, 130473,
        "abcdef01-2345-4678-8abc-123456789abc")

    private fun decide(gate: CarPlaySafetyPublicationGate, candidate: Key? = key,
                       current: Key? = key, requested: Boolean = true, enabled: Boolean = true,
                       inFlight: Boolean = true, phase: String = "preparing") =
        gate.decide(candidate, current, requested, enabled, inFlight, phase)

    @Test fun cancelledTicketCannotBeRevivedByPublisherCapturedBeforeFailure() {
        val gate = CarPlaySafetyPublicationGate()
        val captured = key // Publisher has already computed eligible=true outside the lock.
        assertEquals(Decision.ALLOW, decide(gate, captured))
        gate.cancel(key.ticket) // Failure/revocation wins the publication lock first.
        assertEquals(Decision.DENY, decide(gate, captured))
        assertEquals(Decision.DENY, decide(gate, captured, phase = "failed"))
    }

    @Test fun oldIdentityOrTicketSamplesCannotOverwriteCurrentAttempt() {
        val gate = CarPlaySafetyPublicationGate()
        for (current in listOf(key.copy(pid = 21259), key.copy(start = 130474),
                key.copy(ticket = "abcdef01-2345-4678-8abc-123456789abd"))) {
            assertEquals(Decision.DROP, decide(gate, candidate = key, current = current))
            assertEquals(Decision.DROP, decide(gate, candidate = key, current = current, requested = false))
            assertEquals(Decision.ALLOW, decide(gate, candidate = current, current = current))
        }
        assertEquals(Decision.DROP, decide(gate, current = null))
    }

    @Test fun enablementInFlightAndPreparingPhaseAreRevalidatedBeforePublication() {
        val gate = CarPlaySafetyPublicationGate()
        assertEquals(Decision.DENY, decide(gate, enabled = false))
        assertEquals(Decision.DENY, decide(gate, inFlight = false))
        assertEquals(Decision.DENY, decide(gate, requested = false))
        for (phase in listOf("", "none", "failed", "blocked", "ready")) {
            assertEquals(Decision.DENY, decide(gate, phase = phase))
        }
        assertEquals(Decision.DENY, decide(gate, candidate = null, current = null))
    }

    @Test fun cancellationDoesNotAuthorizeOrConsumeADifferentTicket() {
        val gate = CarPlaySafetyPublicationGate()
        gate.cancel(key.ticket)
        val next = key.copy(ticket = "abcdef01-2345-4678-8abc-123456789abd")
        assertEquals(Decision.ALLOW, decide(gate, next, next))
        assertEquals(Decision.DENY, decide(gate, key, key))
    }

    private fun packet(elapsed: Long = 99_900) = """{"eligible":true,"enabled":true,"inFlight":true,"attemptPhase":"preparing","quiescent":true,"bootId":"${key.boot}","ticket":"${key.ticket}","targetPid":${key.pid},"targetStartTime":${key.start},"elapsedMs":$elapsed}"""
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private fun shellCheck(packet: String, replacement: String? = null,
        coldAttach: Boolean = false): Boolean {
        val directory = Files.createTempDirectory("carplay-safety-").toFile()
        try {
            val safety = File(directory, "safety ' snapshot.json").apply { writeText(packet) }
            val clock = File(directory, "uptime").apply { writeText("100.00 0.00\n") }
            val marker = File(directory, "would-kill")
            val reads = File(directory, "reads")
            val newer = File(directory, "newer.json").apply { writeText(replacement.orEmpty()) }
            val replaceHook = if (replacement == null) "" else """
                grep() {
                    command grep "§@"
                    cpb_test_rc=§?
                    if [ "§2" = '"eligible":true' ]; then cp ${quote(newer.path)} ${quote(safety.path)}; fi
                    return "§cpb_test_rc"
                }
            """.trimIndent().replace('§', '$')
            val code = """
                set -eu
                cat() { printf 'read\n' >> ${quote(reads.path)}; command cat "§@"; }
                $replaceHook
            """.trimIndent().replace('§', '$') + "\n" +
                (if (coldAttach) CarPlaySafetyPublicationGate.coldAttachCheck(key, safety.path, clock.path)
                else CarPlaySafetyPublicationGate.warmKillCheck(key, safety.path, clock.path)) +
                "printf 'authorized\\n' > ${quote(marker.path)}\n"
            val process = ProcessBuilder("/bin/sh", "-c", code).redirectErrorStream(true).start()
            try {
                assertTrue("Local shell did not finish", process.waitFor(5, TimeUnit.SECONDS))
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertTrue(output, process.exitValue() == 0 || !marker.exists())
            } finally { process.destroy() }
            assertEquals("Exactly one safety snapshot must be read", listOf("read"), reads.readLines())
            if (replacement != null) assertEquals(replacement, safety.readText())
            return marker.exists()
        } finally { directory.deleteRecursively() }
    }

    @Test fun warmKillUsesOneFreshCoherentPacket() {
        assertTrue(shellCheck(packet()))
    }

    @Test fun coldAttachRequiresItsOwnFreshAdmissionInsteadOfQuiescence() {
        val cold = packet().replace("\"quiescent\":true", "\"quiescent\":false,\"coldAttach\":true")
        assertTrue(shellCheck(cold, coldAttach = true))
        assertFalse(shellCheck(packet().replace("\"quiescent\":true", "\"quiescent\":false"),
            coldAttach = true))
        assertFalse(shellCheck(cold.replace("\"coldAttach\":true", "\"coldAttach\":false"),
            coldAttach = true))
        assertFalse(shellCheck(cold, coldAttach = false))
    }

    @Test fun warmKillDoesNotRequireVehicleTelemetryOrInterpretLegacyGearAndSpeed() {
        for (legacy in listOf("\"gear\":\"D\",\"speedKmh\":45",
                "\"gear\":\"N\",\"speedKmh\":0", "\"gear\":null,\"speedKmh\":null")) {
            assertTrue(legacy, shellCheck(packet().dropLast(1) + "," + legacy + "}"))
        }
    }

    @Test fun warmKillRejectsEachLostPredicateAndWrongIdentity() {
        val invalid = listOf(
            "\"eligible\":true" to "\"eligible\":false",
            "\"enabled\":true" to "\"enabled\":false",
            "\"inFlight\":true" to "\"inFlight\":false",
            "\"attemptPhase\":\"preparing\"" to "\"attemptPhase\":\"failed\"",
            "\"quiescent\":true" to "\"quiescent\":false",
            key.boot to "12345678-abcd-4abc-8def-123456789abd",
            key.ticket to "abcdef01-2345-4678-8abc-123456789abd",
            "\"targetPid\":21258" to "\"targetPid\":212580",
            "\"targetStartTime\":130473" to "\"targetStartTime\":1304730"
        )
        for ((before, after) in invalid) assertFalse(after, shellCheck(packet().replace(before, after)))
        assertFalse(shellCheck(packet(96_999)))
        assertFalse(shellCheck(packet(100_031)))
        assertFalse(shellCheck(packet(0)))
    }

    @Test fun laterFreshRevocationCannotLendTimestampToAnOlderEligiblePacket() {
        val oldEligibleButStale = packet(95_000)
        val freshRevoked = packet().replace("\"eligible\":true", "\"eligible\":false")
        // A deterministic publisher replacement happens after the eligible grep.
        // Mixing independent file reads would combine old true with the fresh timestamp.
        assertFalse(shellCheck(oldEligibleButStale, freshRevoked))
    }
}
