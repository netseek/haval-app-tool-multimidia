package br.com.redesurftank.havalshisuku.experimental

import java.security.MessageDigest

/** Pure validation and shell generation. No process restart or callback teardown. */
internal object CarPlayResidentProof {
    class Pending(message: String) : IllegalStateException(message)
    const val POLICY_MODE = "permissive-existing-policy-v1"
    const val PATCH_PROFILE = "frida-17.5.2-android-arm64-init-tailcall-ret-v1"
    const val ORIGINAL_INJECTOR_SHA = "bdfa97cdd84ca678c5e5eea9eb6625c9528acff32b4f9413f1e5c58a3d15222e"
    const val PATCHED_INJECTOR_SHA = "32f7c702ea36c9ad1d09d2dc9e21de5f4994d98f855c4e51db01bcc6d0fd496a"
    const val INJECTOR_SIZE = 51_758_352L
    data class Environment(val bootId: String, val systemServerPid: Int, val systemServerStart: Long,
        val policySha256: String, val injectorSha256: String, val injectorFingerprint: String)

    fun checkEnvironment(environment: Environment) = with(environment) {
        require(uuid.matches(bootId) && systemServerPid > 0 && systemServerStart > 0) { "Invalid preflight framework identity" }
        require(digest.matches(policySha256) && injectorSha256 == PATCHED_INJECTOR_SHA) { "Unverified preflight policy or injector" }
        require(injectorFingerprint.matches(Regex("[0-9]+:[0-9]+:$INJECTOR_SIZE:[0-9]+:[0-9]+:0:500"))) {
            "Preflight injector is not the immutable root-owned executable"
        }
    }

    fun checkPreflight(before: Environment, after: Environment, version: String) {
        checkEnvironment(before); checkEnvironment(after)
        require(before == after && version == "17.5.2") { "Injector probe changed its environment or version" }
    }

    fun preflightDirectory(boot: String, revision: String): String {
        require(uuid.matches(boot) && revision.matches(Regex("[1-9][0-9]{0,9}-[a-f0-9]{12}-[a-f0-9]{12}")))
        return "/data/local/tmp/impulse-carplay/preflight/$boot-$revision"
    }

    fun preflightReceipt(environment: Environment, revision: String): String {
        checkEnvironment(environment); preflightDirectory(environment.bootId, revision)
        return """{"schema":1,"state":"success","version":"17.5.2","revision":"$revision","bootId":"${environment.bootId}","systemServerPid":${environment.systemServerPid},"systemServerStart":${environment.systemServerStart},"policySha256":"${environment.policySha256}","injectorSha256":"${environment.injectorSha256}","injectorFingerprint":"${environment.injectorFingerprint}","policyMode":"$POLICY_MODE","patchProfile":"$PATCH_PROFILE"}"""
    }

    /** Read only; the expensive executable hash is used before the cold gate, never by a child guard. */
    fun environmentSnapshot(injector: String): String = """
        test "§(cat /sys/fs/selinux/enforce)" = 0 && test "§(getenforce)" = Permissive || exit 1
        test "§(getprop sys.boot_completed)" = 1 || exit 1
        cpb_env_boot=§(cat /proc/sys/kernel/random/boot_id) || exit 1
        cpb_env_pid=§(pidof system_server) || exit 1
        case "§cpb_env_pid" in ''|*[!0-9]*) exit 1;; esac
        cpb_env_stat=§(cat /proc/§cpb_env_pid/stat) || exit 1
        cpb_env_tail=§{cpb_env_stat##*) }; set -- §cpb_env_tail
        case "§1" in Z|X|x) exit 1;; esac
        shift 19; cpb_env_start=§1
        cpb_env_policy=§(sha256sum /sys/fs/selinux/policy | cut -d' ' -f1) || exit 1
        cpb_env_injector=§(sha256sum ${quote(injector)} | cut -d' ' -f1) || exit 1
        cpb_env_fingerprint=§(stat -c '%d:%i:%s:%Y:%Z:%u:%a' ${quote(injector)}) || exit 1
        test "§(cat /proc/sys/kernel/random/boot_id)" = "§cpb_env_boot" || exit 1
        test "§(pidof system_server)" = "§cpb_env_pid" || exit 1
        cpb_env_stat=§(cat /proc/§cpb_env_pid/stat) || exit 1
        cpb_env_tail=§{cpb_env_stat##*) }; set -- §cpb_env_tail; shift 19
        test "§1" = "§cpb_env_start" && test "§(cat /sys/fs/selinux/enforce)" = 0 || exit 1
        printf 'env_boot=%s\nenv_system_pid=%s\nenv_system_start=%s\nenv_policy_sha=%s\nenv_injector_sha=%s\nenv_injector_fingerprint=%s\n' "§cpb_env_boot" "§cpb_env_pid" "§cpb_env_start" "§cpb_env_policy" "§cpb_env_injector" "§cpb_env_fingerprint"
    """.trimIndent().replace('§', '$') + "\n"

    fun environmentStateGuard(environment: Environment, injector: String): String {
        checkEnvironment(environment)
        return """
            test "§(cat /sys/fs/selinux/enforce)" = 0 && test "§(getenforce)" = Permissive || exit 1
            test "§(getprop sys.boot_completed)" = 1 || exit 1
            test "§(cat /proc/sys/kernel/random/boot_id)" = ${quote(environment.bootId)} || exit 1
            test "§(pidof system_server)" = ${environment.systemServerPid} || exit 1
            cpb_env_stat=§(cat /proc/${environment.systemServerPid}/stat) || exit 1
            cpb_env_tail=§{cpb_env_stat##*) }; set -- §cpb_env_tail
            case "§1" in Z|X|x) exit 1;; esac
            shift 19; test "§1" = ${environment.systemServerStart} || exit 1
            test "§(sha256sum /sys/fs/selinux/policy | cut -d' ' -f1)" = ${quote(environment.policySha256)} || exit 1
            test "§(stat -c '%d:%i:%s:%Y:%Z:%u:%a' ${quote(injector)})" = ${quote(environment.injectorFingerprint)} || exit 1
            test "§(pidof system_server)" = ${environment.systemServerPid} || exit 1
            cpb_env_stat=§(cat /proc/${environment.systemServerPid}/stat) || exit 1
            cpb_env_tail=§{cpb_env_stat##*) }; set -- §cpb_env_tail; shift 19
            test "§1" = ${environment.systemServerStart} || exit 1
            test "§(cat /sys/fs/selinux/enforce)" = 0 || exit 1
        """.trimIndent().replace('§', '$') + "\n"
    }

    fun environmentGuard(environment: Environment, revision: String, injector: String): String {
        val receipt = preflightDirectory(environment.bootId, revision) + "/success.json"
        return environmentStateGuard(environment, injector) + """
            test -f ${quote(receipt)} && test ! -L ${quote(receipt)} || exit 1
            test "§(stat -c %u ${quote(receipt)})" = 0 && test "§(stat -c %a ${quote(receipt)})" = 400 || exit 1
            test "§(cat ${quote(receipt)})" = ${quote(preflightReceipt(environment, revision))} || exit 1
        """.trimIndent().replace('§', '$') + "\n"
    }

    data class ColdGateBudget(val proofDeadlineMs: Long, val releaseDeadlineMs: Long)

    /** Process birth precedes the OEM's ten-second gate; it is a conservative bound, not a gate marker. */
    fun coldGateBudget(startTicks: Long, ticksPerSecond: Long, nowMs: Long,
        attemptDeadlineMs: Long): ColdGateBudget {
        require(nowMs >= 0 && attemptDeadlineMs > nowMs) { "Cold preparation attempt expired" }
        require(startTicks > 0 && startTicks <= Long.MAX_VALUE / 1000 && ticksPerSecond > 0) {
            "Invalid cold startup clock"
        }
        val birthMs = startTicks * 1000 / ticksPerSecond
        require(nowMs >= birthMs && birthMs <= Long.MAX_VALUE - 10_000) { "Invalid cold startup chronology" }
        val releaseDeadline = minOf(birthMs + 10_000, attemptDeadlineMs)
        val proofDeadline = releaseDeadline - 1000 // Reserve time for final identity, safety and gate release.
        require(proofDeadline > nowMs) { "Conservative cold startup window expired" }
        return ColdGateBudget(proofDeadline, releaseDeadline)
    }

    fun rootTimeout(deadlineMs: Long, nowMs: Long): Long {
        require(nowMs >= 0 && deadlineMs > nowMs) { "Cold startup deadline expired" }
        return minOf(5000L, deadlineMs - nowMs)
    }

    /** Recheck in the same root shell immediately before a bounded startup mutation. */
    fun deadlineGuard(deadlineMs: Long, firstSampleElapsedMs: Long? = null): String {
        require(deadlineMs > 0 && (firstSampleElapsedMs == null || firstSampleElapsedMs >= 0))
        val freshness = firstSampleElapsedMs?.let {
            "test \$((cpb_now-$it)) -ge 0 && test \$((cpb_now-$it)) -le 3000 || exit 1\n"
        }.orEmpty()
        return """
            cpb_now=§(awk '{print int(§1*1000)}' /proc/uptime) || exit 1
            case "§cpb_now" in ''|*[!0-9]*) exit 1;; esac
            test "§cpb_now" -gt 0 && test "§cpb_now" -lt $deadlineMs || exit 1
        """.trimIndent().replace('§', '$') + "\n" + freshness
    }

    /** A pending poll spends the existing budget; terminal errors are never retried. */
    fun <T> await(deadline: Long, now: () -> Long, pause: () -> Unit, probe: () -> T): T {
        var last: Pending? = null
        while (now() < deadline) {
            try {
                val result = probe()
                check(now() < deadline) { "Resident proof completed after its deadline" }
                return result
            } catch (pending: Pending) {
                last = pending
                if (now() < deadline) pause()
            }
        }
        throw IllegalStateException("Resident proof deadline expired: ${last?.message}", last)
    }
    data class Binding(val role: String, val bootId: String, val targetPid: Int,
        val targetStartTime: Long, val revision: String, val ticket: String,
        val instance: String, val scriptSha256: String, val runtimeDirectory: String,
        val payloadDirectory: String, val environment: Environment, val execDeadlineMs: Long) {
        val scriptPath get() = if (role == "native") "$runtimeDirectory/cold-launcher.js" else "$payloadDirectory/endpoint.js"
    }
    data class Receipt(val binding: Binding, val childPid: Int, val childStartTime: Long,
        val commandSha256: String, val exitCode: Int? = null, val exitElapsedMs: Long? = null,
        val commandEvidence: String = "", val supervisorSha256: String = "")
    data class Challenge(val instance: String, val nonce: String, val issuedElapsedMs: Long)
    data class Sample(val sequence: Long, val elapsedMs: Long, val wallMs: Long,
        val phase: String = "", val replacements: Int = -1, val pinned: Boolean = false,
        val failureAbsent: Boolean = false, val armed: Boolean? = null,
        val firstElapsedMs: Long = elapsedMs, val firstWallMs: Long = wallMs)
    data class Response(val binding: Binding, val nonce: String, val disposing: Boolean?, val samples: List<Sample>)
    private val uuid = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    private val digest = Regex("[a-f0-9]{64}")

    fun challenge(instance: String, previous: Challenge?, now: Long, nonce: () -> String): Challenge {
        require(uuid.matches(instance) && now >= 0) { "Invalid challenge identity or clock" }
        return previous?.takeIf { it.instance == instance && now - it.issuedElapsedMs in 0..2500 }
            ?: Challenge(instance, nonce().also { require(uuid.matches(it)) }, now)
    }

    fun checkBinding(binding: Binding) = with(binding) {
        checkEnvironment(environment)
        require(environment.bootId == bootId) { "Residency and preflight boots differ" }
        require(execDeadlineMs > 0) { "Residency launch deadline missing" }
        require(role in setOf("native", "java")) { "Unknown residency role" }
        require(uuid.matches(bootId) && uuid.matches(ticket) && uuid.matches(instance)) { "Invalid residency UUID" }
        require(targetPid > 0 && targetStartTime in 1..9_007_199_254_740_991L) { "Invalid residency target" }
        require(revision.matches(Regex("[1-9][0-9]{0,9}-[a-f0-9]{12}-[a-f0-9]{12}")) && digest.matches(scriptSha256)) { "Invalid residency payload identity" }
        require(runtimeDirectory == "/data/local/tmp/impulse-carplay/runtime/$bootId-$targetPid-$targetStartTime") { "Invalid residency runtime directory" }
        require(payloadDirectory == "/data/local/tmp/impulse-carplay/versions/$revision") { "Invalid residency payload directory" }
    }

    fun bindingJson(b: Binding): String {
        checkBinding(b)
        return """{"schema":1,"role":"${b.role}","bootId":"${b.bootId}","targetPid":${b.targetPid},"targetStartTime":${b.targetStartTime},"revision":"${b.revision}","ticket":"${b.ticket}","instance":"${b.instance}","scriptSha256":"${b.scriptSha256}","runtimeDirectory":"${b.runtimeDirectory}","payloadDirectory":"${b.payloadDirectory}"}"""
    }
    fun parameters(b: Binding) = "{\"impulseResidency\":" + bindingJson(b) + "}"
    fun arguments(b: Binding, injector: String): List<String> {
        checkBinding(b)
        require(injector == "${b.payloadDirectory}/cpbridge-inject") { "Unexpected residency injector" }
        return listOf(injector, "-p", b.targetPid.toString(), "-s", b.scriptPath, "-R", "qjs", "-e", "-P", parameters(b))
    }
    fun commandSha256(b: Binding) = sha256((arguments(b, "${b.payloadDirectory}/cpbridge-inject").joinToString("\u0000") + "\u0000").toByteArray(Charsets.UTF_8))
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun checkLaunch(expected: Binding, launch: Receipt) {
        checkBinding(expected)
        require(launch.binding == expected) { "Residency launch identity mismatch" }
        require(launch.childPid > 0 && launch.childStartTime >= expected.targetStartTime) { "Residency child identity mismatch" }
        require(launch.commandSha256 == commandSha256(expected)) { "Residency declared CLI arguments mismatch" }
        require(launch.commandEvidence == "declared-before-exec-v1" && launch.supervisorSha256 == supervisorSha256(expected)) {
            "Residency pre-exec supervisor evidence mismatch"
        }
    }

    fun checkExit(expected: Binding, launch: Receipt, exit: Receipt) {
        checkLaunch(expected, launch)
        checkLaunch(expected, exit)
        require(launch.childPid == exit.childPid && launch.childStartTime == exit.childStartTime) { "Residency child identity mismatch" }
        require(exit.exitCode == 0 && (exit.exitElapsedMs ?: 0) > 0) { "Residency CLI did not exit successfully" }
    }

    enum class Recovery { PENDING, ARMED, INSTALLED }
    fun recovery(phase: String, mappingMatches: Boolean?, proofAvailable: Boolean): Recovery {
        require(mappingMatches != false) { "Unexpected resident bridge mapping" }
        require(phase in setOf("armed", "installing", "installed-pinned")) { "Native phase is terminal or unknown: $phase" }
        if (!proofAvailable || mappingMatches == null || phase == "installing") return Recovery.PENDING
        return if (phase == "armed") Recovery.ARMED else Recovery.INSTALLED
    }

    fun checkResponse(expected: Binding, launch: Receipt, exit: Receipt, challenge: Challenge,
        response: Response, nowElapsedMs: Long, nowWallMs: Long): Sample {
        checkExit(expected, launch, exit)
        require(uuid.matches(challenge.nonce) && challenge.instance == expected.instance) { "Invalid residency challenge" }
        require(challenge.issuedElapsedMs >= requireNotNull(exit.exitElapsedMs) &&
            challenge.issuedElapsedMs <= nowElapsedMs) { "Residency challenge predates CLI exit" }
        require(response.binding == expected && response.disposing == false) { "Residency response identity or disposal mismatch" }
        require(uuid.matches(response.nonce)) { "Invalid residency response nonce" }
        require(response.samples.size <= 2) { "Invalid residency sample count" }
        var unfinished = response.samples.size < 2
        for (sample in response.samples) {
            require(sample.sequence > 0 && sample.elapsedMs >= 0 && sample.wallMs >= 0) { "Invalid residency sample" }
            if (expected.role == "native") {
                if (sample.phase == "installing" && sample.pinned && sample.failureAbsent && sample.replacements in 0..9) {
                    unfinished = true
                } else require(nativeArmed(sample) || nativeInstalled(sample)) { "Resident native state is terminal: ${sample.phase}" }
            } else {
                require(sample.armed != null) { "Resident Java state is unknown" }
                if (sample.armed == false) unfinished = true
            }
        }
        // Old healthy responses are pending; terminal/invalid evidence above is never hidden by nonce churn.
        if (response.nonce != challenge.nonce) throw Pending("Still awaiting the current residency nonce")
        var stale = false
        for (sample in response.samples) {
            require(sample.elapsedMs >= challenge.issuedElapsedMs && sample.elapsedMs <= nowElapsedMs &&
                sample.wallMs <= nowWallMs) { "Invalid residency sample chronology" }
            if (nowElapsedMs - sample.elapsedMs > 3000 || nowWallMs - sample.wallMs > 3000) stale = true
        }
        if (response.samples.size == 2) {
            val first = response.samples[0]
            val last = response.samples[1]
            require(last.sequence > first.sequence && last.elapsedMs - first.elapsedMs >= 100) { "Residency samples did not advance" }
        }
        if (stale) throw Pending("Residency response is no longer fresh")
        if (unfinished) throw Pending("Residency response is not complete")
        val last = response.samples.last()
        return last.copy(firstElapsedMs = response.samples.first().elapsedMs, firstWallMs = response.samples.first().wallMs)
    }

    fun fresh(sample: Sample, nowElapsed: Long, nowWall: Long): Boolean =
        sample.firstElapsedMs >= 0 && sample.firstWallMs >= 0 &&
            nowElapsed - sample.firstElapsedMs in 0..3000 && nowWall - sample.firstWallMs in 0..3000 &&
            nowElapsed - sample.elapsedMs in 0..3000 && nowWall - sample.wallMs in 0..3000

    fun nativeArmed(sample: Sample) = sample.phase == "armed" && sample.replacements == 0 && sample.pinned && sample.failureAbsent
    fun nativeInstalled(sample: Sample) = sample.phase == "installed-pinned" && sample.replacements == 9 && sample.pinned && sample.failureAbsent

    fun supervisorSha256(binding: Binding) = sha256(supervisor(binding).toByteArray(Charsets.UTF_8))

    /** A pre-exec barrier makes birth observable even when the CLI returns immediately.
     * commandSha256 declares the exact exec arguments; it is NOT a live argv observation.
     * The immutable supervisor source and payload hashes bind that declaration.
     */
    fun supervisor(binding: Binding): String {
        val args = arguments(binding, "${binding.payloadDirectory}/cpbridge-inject").joinToString(" ", transform = ::quote)
        val run = binding.runtimeDirectory
        val role = binding.role
        val base = bindingJson(binding).dropLast(1)
        val hash = commandSha256(binding)
        val start = "$run/$role-launch.json"
        val end = "$run/$role-exit.json"
        val release = "$run/$role-exec-${binding.instance}.release"
        // installPayload attests the immutable injector hash and size before the cold gate.
        return """
            #!/system/bin/sh
            umask 077
            test ! -e ${quote(start)} && test ! -e ${quote(end)} && test ! -e ${quote(release)} || exit 91
            cpb_supervisor_sha=§(sha256sum "§0" | cut -d' ' -f1)
            test "§{#cpb_supervisor_sha}" = 64 || exit 93
            test "§(sha256sum ${quote(binding.scriptPath)} | cut -d' ' -f1)" = ${quote(binding.scriptSha256)} || exit 95
            (
              cpb_wait=0
              while [ ! -f ${quote(release)} ]; do
                cpb_wait=§((cpb_wait+1))
                test "§cpb_wait" -le 200 || exit 92
                sleep 0.01
              done
              test "§(stat -c %u ${quote(release)})" = 0 && test "§(stat -c %a ${quote(release)})" = 400 || exit 96
              test "§(cat ${quote(release)})" = ${quote(binding.instance)} || exit 97
              ${environmentGuard(binding.environment, binding.revision, "${binding.payloadDirectory}/cpbridge-inject")}
              ${deadlineGuard(binding.execDeadlineMs)}
              exec $args
            ) > ${quote("$run/$role-injector.log")} 2>&1 < /dev/null &
            cpb_child=§!
            cpb_start=0
            cpb_stat=§(cat /proc/§cpb_child/stat 2>/dev/null)
            cpb_tail=§{cpb_stat##*) }; set -- §cpb_tail
            cpb_alive=yes
            case "§1" in ''|Z|X|x) cpb_alive=;; esac
            if [ "§#" -ge 20 ] && [ "§cpb_alive" = yes ]; then
              shift 19; cpb_start=§1
              case "§cpb_start" in ''|*[!0-9]*) cpb_start=0;; esac
            fi
            if [ "§cpb_start" -gt 0 ]; then
              if printf '%s,"childPid":%s,"childStartTime":%s,"commandSha256":"%s","commandEvidence":"declared-before-exec-v1","supervisorSha256":"%s"}\n' ${quote(base)} "§cpb_child" "§cpb_start" ${quote(hash)} "§cpb_supervisor_sha" > ${quote("$start.next")} &&
                  chmod 400 ${quote("$start.next")} && chown 0:0 ${quote("$start.next")} && mv ${quote("$start.next")} ${quote(start)}; then
                printf '%s\n' ${quote(binding.instance)} > ${quote("$release.next")} &&
                  chmod 400 ${quote("$release.next")} && chown 0:0 ${quote("$release.next")} && mv ${quote("$release.next")} ${quote(release)}
              fi
            fi
            wait "§cpb_child"
            cpb_exit=§?
            cpb_elapsed=§(awk '{print int(§1*1000)}' /proc/uptime)
            printf '%s,"childPid":%s,"childStartTime":%s,"commandSha256":"%s","commandEvidence":"declared-before-exec-v1","supervisorSha256":"%s","exitCode":%s,"exitElapsedMs":%s}\n' ${quote(base)} "§cpb_child" "§cpb_start" ${quote(hash)} "§cpb_supervisor_sha" "§cpb_exit" "§cpb_elapsed" > ${quote("$end.next")} &&
            chmod 400 ${quote("$end.next")} && chown 0:0 ${quote("$end.next")} && mv ${quote("$end.next")} ${quote(end)}
        """.trimIndent().replace('§', '$') + "\n"
    }
    fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
