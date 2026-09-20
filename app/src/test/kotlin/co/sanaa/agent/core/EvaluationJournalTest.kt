package co.sanaa.agent.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EvaluationJournalTest {
    @Test fun releaseLoggingContinuesAfterItsFirstDayButManualWindowRemainsBounded() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("evaluation_window", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val journal = EvaluationJournal(context)
        journal.startReleaseObservation()
        prefs.edit().putLong("end", 1).commit()
        journal.record("next_day")
        assertTrue(prefs.getLong("end", 0) > System.currentTimeMillis())
        assertTrue(journal.exportObservation()!!.readText().contains("next_day"))
        journal.start()
        prefs.edit().putLong("end", 1).commit()
        journal.record("past_manual_deadline")
        assertFalse(journal.exportObservation()!!.readText().contains("past_manual_deadline"))
    }
    @Test fun releaseWindowSurvivesRestartAndExports() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("evaluation_window", Context.MODE_PRIVATE).edit().clear().commit()
        val journal = EvaluationJournal(context)
        journal.startReleaseObservation()
        val prefs = context.getSharedPreferences("evaluation_window", Context.MODE_PRIVATE)
        val start = prefs.getLong("start", 0)
        assertEquals(24 * 3_600_000L, prefs.getLong("end", 0) - start)
        EvaluationJournal(context).startReleaseObservation()
        assertEquals(start, prefs.getLong("start", 0))
        // Simulate a different install identity without resetting the active window.
        prefs.edit().putLong("release_install", -2).commit()
        EvaluationJournal(context).startReleaseObservation()
        assertEquals(start, prefs.getLong("start", 0))
        journal.record("verified_test")
        assertTrue(journal.exportObservation()!!.readText().contains("verified_test"))
    }
    @Test fun sevenDayWindowIsSupportedAndLongerWindowsAreBounded() {
        val journal = EvaluationJournal(ApplicationProvider.getApplicationContext<Context>())
        val week = 7 * 24 * 3_600_000L
        for (duration in listOf(week, Long.MAX_VALUE)) {
            val result = journal.start(duration)
            assertEquals(week, result.getLong("end") - result.getLong("start"))
        }
        val normal = journal.start()
        assertEquals(5 * 3_600_000L, normal.getLong("end") - normal.getLong("start"))
    }
}
