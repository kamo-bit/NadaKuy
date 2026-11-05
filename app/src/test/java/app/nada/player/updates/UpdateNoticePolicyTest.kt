package app.nada.player.updates

import org.junit.Assert.*
import org.junit.Test

class UpdateNoticePolicyTest {
    private fun release(version: String = "1.9.0") = AppRelease(version, "", "", UpdatePackage("v$version", 100, "a".repeat(64)))

    @Test fun onlyNewerInstallableReleasesAreOffered() {
        val policy = UpdateNoticePolicy()
        assertFalse(policy.offer(release(), "1.9.0", 0))
        assertFalse(policy.offer(release(), "1.10.0", 0))
        assertFalse(policy.offer(release(), "unknown", 0))
        assertFalse(policy.offer(release().copy(apk = null), "1.8.1", 0))
        assertFalse(policy.offer(release().copy(version = "2.0.0"), "1.8.1", 0))
        assertTrue(policy.offer(release(), "1.8.1", 0))
        assertFalse(policy.offer(release(), "1.8.1", 1))
    }

    @Test fun foregroundChecksUseSuccessAndFailureBudgets() {
        val policy = UpdateNoticePolicy()
        assertTrue(policy.shouldCheck(0))
        policy.checked(50, succeeded = true)
        assertFalse(policy.shouldCheck(50 + UpdateNoticePolicy.CHECK_INTERVAL - 1))
        assertTrue(policy.shouldCheck(50 + UpdateNoticePolicy.CHECK_INTERVAL))
        policy.checked(100, succeeded = false)
        assertFalse(policy.shouldCheck(100 + UpdateNoticePolicy.RETRY_INTERVAL - 1))
        assertTrue(policy.shouldCheck(100 + UpdateNoticePolicy.RETRY_INTERVAL))
    }

    @Test fun persistedSnoozeExpiresAndDoesNotHideAnotherVersion() {
        val now = 1000L
        val first = UpdateNoticePolicy()
        val until = first.snooze("1.9.0", now)
        val restarted = UpdateNoticePolicy("1.9.0", until)
        assertFalse(restarted.offer(release(), "1.8.1", until - 1))
        assertTrue(restarted.offer(release("1.9.1"), "1.8.1", now))
        assertTrue(restarted.offer(release(), "1.8.1", until))
    }

    @Test fun sameProcessCanRemindAgainAfterOneDay() {
        val policy = UpdateNoticePolicy()
        assertTrue(policy.offer(release(), "1.8.1", 0))
        policy.snooze("1.9.0", 100)
        assertFalse(policy.offer(release(), "1.8.1", UpdateNoticePolicy.SNOOZE_DURATION))
        assertTrue(policy.offer(release(), "1.8.1", UpdateNoticePolicy.SNOOZE_DURATION + 100))
    }
}
