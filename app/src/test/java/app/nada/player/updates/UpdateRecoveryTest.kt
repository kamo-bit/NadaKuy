package app.nada.player.updates

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateRecoveryTest {
    @Test fun retainedPackageSurvivesPrunedJobHistoryAndLateCancellation() {
        assertEquals(UpdatePhase.READY, updatePhase(null, true, false))
        assertEquals(UpdatePhase.READY, updatePhase(WorkInfo.State.CANCELLED, true, false))
        assertEquals(UpdatePhase.READY, updatePhase(WorkInfo.State.SUCCEEDED, true, false))
        assertEquals(UpdatePhase.CANCELLED, updatePhase(WorkInfo.State.CANCELLED, false, false))
        assertEquals(UpdatePhase.FAILED, updatePhase(null, false, false))
    }

    @Test fun activeVerificationAndExplicitFailureCannotAdvertiseAnInstallablePackage() {
        assertEquals(UpdatePhase.VERIFYING, updatePhase(WorkInfo.State.RUNNING, true, true))
        assertEquals(UpdatePhase.DOWNLOADING, updatePhase(WorkInfo.State.RUNNING, false, false))
        assertEquals(UpdatePhase.FAILED, updatePhase(WorkInfo.State.FAILED, true, false))
        assertEquals(UpdatePhase.WAITING, updatePhase(WorkInfo.State.ENQUEUED, false, false))
    }
}
