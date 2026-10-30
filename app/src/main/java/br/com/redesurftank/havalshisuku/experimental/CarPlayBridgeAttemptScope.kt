package br.com.redesurftank.havalshisuku.experimental

/** Pure identity scoping for the automatic CarPlay preparation circuit. */
object CarPlayBridgeAttemptScope {
    fun attemptsForProcess(currentIdentity: String?, attemptedIdentity: String?): Int =
        if (currentIdentity.isUsable() && currentIdentity == attemptedIdentity) 1 else 0

    fun failureCircuitOpen(
        currentIdentity: String?,
        currentRevision: String,
        circuitIdentity: String?,
        circuitRevision: String?
    ): Boolean = currentIdentity.isUsable() && currentIdentity == circuitIdentity &&
        currentRevision == circuitRevision

    fun markerName(processIdentity: String, revision: String): String {
        require(processIdentity.isUsable() && processIdentity.none { it == '/' || it == '\u0000' })
        require(revision.isNotBlank() && revision.none { it == '/' || it == '\u0000' })
        return "$processIdentity-$revision"
    }

    private fun String?.isUsable(): Boolean = !this.isNullOrBlank()
}
