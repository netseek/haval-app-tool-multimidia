package br.com.redesurftank.havalshisuku.experimental

/** Called under the manager's publication lock; terminal tickets cannot be resurrected. */
internal class CarPlaySafetyPublicationGate {
    data class Key(val boot: String, val pid: Int, val start: Long, val ticket: String)
    enum class Decision { DROP, ALLOW, DENY }
    private val cancelled = mutableSetOf<String>()

    fun cancel(ticket: String?) { if (!ticket.isNullOrEmpty()) cancelled.add(ticket) }

    fun decide(candidate: Key?, current: Key?, requested: Boolean, enabled: Boolean,
               inFlight: Boolean, phase: String): Decision {
        if (candidate != current) return Decision.DROP
        return if (requested && candidate != null && candidate.ticket !in cancelled &&
            enabled && inFlight && phase == "preparing") Decision.ALLOW else Decision.DENY
    }

    companion object {
        private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

        private fun checkedPacket(key: Key, safetyPath: String, uptimePath: String,
            admissionCheck: String): String {
            require(key.pid > 0 && key.start > 0)
            require(key.boot.matches(Regex("[a-f0-9-]{36}")) && key.ticket.matches(Regex("[a-f0-9-]{36}")))
            val checks = listOf(
                "\"eligible\":true", "\"enabled\":true", "\"inFlight\":true",
                "\"attemptPhase\":\"preparing\"",
                "\"bootId\":\"${key.boot}\"", "\"ticket\":\"${key.ticket}\""
            ).joinToString("\n") { "printf '%s\\n' \"\$cpb_safety\" | grep -Fq ${quote(it)} || exit 1" }
            return """
                cpb_safety=§(cat ${quote(safetyPath)}) || exit 1
                $checks
                $admissionCheck
                printf '%s\n' "§cpb_safety" | grep -Eq '"targetPid":${key.pid}([,}])' || exit 1
                printf '%s\n' "§cpb_safety" | grep -Eq '"targetStartTime":${key.start}([,}])' || exit 1
                cpb_stamp=§(printf '%s\n' "§cpb_safety" | sed -n 's/.*"elapsedMs":\([0-9]*\).*/\1/p')
                case "§cpb_stamp" in ''|*[!0-9]*) exit 1;; esac
                test "§cpb_stamp" -gt 0 || exit 1
                cpb_now=§(awk '{print int(§1*1000)}' ${quote(uptimePath)}) || exit 1
                test §((cpb_now-cpb_stamp)) -ge -30 && test §((cpb_now-cpb_stamp)) -le 3000 || exit 1
            """.trimIndent().replace('§', '$') + "\n"
        }

        /** Read once, after expensive checks. All predicates describe this same immutable sample. */
        fun warmKillCheck(key: Key, safetyPath: String, uptimePath: String = "/proc/uptime"): String =
            checkedPacket(key, safetyPath, uptimePath,
                "printf '%s\\n' \"\$cpb_safety\" | grep -Eq '\"quiescent\":true([,}])' || exit 1")

        /** A cold attach never kills the OEM process; require a fresh exact cold/clear proof. */
        fun coldAttachCheck(key: Key, safetyPath: String, uptimePath: String = "/proc/uptime"): String =
            checkedPacket(key, safetyPath, uptimePath,
                "printf '%s\\n' \"\$cpb_safety\" | grep -Eq '\"coldAttach\":true([,}])' || exit 1")
    }
}
