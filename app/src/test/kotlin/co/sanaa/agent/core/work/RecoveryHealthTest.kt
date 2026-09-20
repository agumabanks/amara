package co.sanaa.agent.core.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class RecoveryHealthTest {
    @Test fun failuresSurviveRestartAlertsAreBoundedAndSuccessResets() {
        val c=ApplicationProvider.getApplicationContext<Context>()
        c.getSharedPreferences("recovery_health",Context.MODE_PRIVATE).edit().clear().commit()
        val item=WorkItem("one",Domain.TIKTOK,WorkKind.TIKTOK_COMMENT_REPLY,baseValueKes=1.0,urgencyHalfLifeHours=1.0,estimatedScreenSeconds=1)
        val failure=WorkResult(item,WorkStatus.FAILED,failure=FailureInfo(FailureClass.UI_MISMATCH,"Profile unavailable"))
        assertNull(RecoveryHealth(c).observe(failure,1000))
        assertNull(RecoveryHealth(c).observe(failure,2000))
        assertNotNull(RecoveryHealth(c).observe(failure,3000))
        assertNull(RecoveryHealth(c).observe(failure,4000))
        RecoveryHealth(c).observe(WorkResult(item,WorkStatus.DONE),5000)
        assertNull(RecoveryHealth(c).observe(failure,6*3_600_000L+6000))
    }
}
