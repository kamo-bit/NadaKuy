package app.nada.player.updates

/** Process-wide foreground check budget; wall time is used only for a persisted snooze. */
class UpdateNoticePolicy(
    private var snoozedVersion: String? = null,
    private var snoozedUntil: Long = 0,
) {
    private var lastCheck: Long? = null
    private var checkInterval = CHECK_INTERVAL
    private var offeredVersion: String? = null
    private var offeredUntil = 0L

    fun shouldCheck(elapsed: Long): Boolean = lastCheck?.let { elapsed - it >= checkInterval } ?: true

    fun checked(elapsed: Long, succeeded: Boolean) {
        lastCheck = elapsed
        checkInterval = if (succeeded) CHECK_INTERVAL else RETRY_INTERVAL
    }

    fun offer(release: AppRelease, installed: String, now: Long): Boolean {
        val current = AppVersion.parse(installed) ?: return false
        val available = AppVersion.parse(release.version) ?: return false
        if (available <= current || release.apk?.version != release.version) return false
        if (offeredVersion == release.version && now < offeredUntil) return false
        if (snoozedVersion == release.version && now < snoozedUntil) return false
        offeredVersion = release.version
        offeredUntil = now + SNOOZE_DURATION
        return true
    }

    fun snooze(version: String, now: Long): Long {
        snoozedVersion = version
        snoozedUntil = now + SNOOZE_DURATION
        return snoozedUntil
    }

    companion object {
        const val CHECK_INTERVAL = 6 * 60 * 60 * 1000L
        const val RETRY_INTERVAL = 30 * 60 * 1000L
        const val SNOOZE_DURATION = 24 * 60 * 60 * 1000L
    }
}
