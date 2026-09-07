package co.sanaa.agent.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.core.knowledge.LearningDatabase
import co.sanaa.agent.core.knowledge.LearningLoop
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.io.File
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EvaluationAndLearningTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Test fun timingEvidenceExcludesOtherHoursAndUpdatesConclusion() {
        context.deleteDatabase("amara_learning.db")
        val db=LearningDatabase(context); val loop=LearningLoop(context,db)
        val time=ZonedDateTime.now().withHour(10).withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli()
        repeat(12) { loop.recordAction("WA_TEST","WHATSAPP",true,atMs=time-3_600_000) }
        repeat(3) { loop.recordAction("WA_TEST","WHATSAPP",false,atMs=time) }
        loop.observeOutcome("WA_TEST",false,time)
        val initial=loop.getPatterns("TIMING_V2",0.0).single()
        assertEquals(3,initial.evidenceCount)
        assertTrue(initial.value.startsWith("0%"))
        repeat(3) { loop.recordAction("WA_TEST","WHATSAPP",true,atMs=time) }
        loop.observeOutcome("WA_TEST",true,time)
        val updated=loop.getPatterns("TIMING_V2",0.0).single()
        assertEquals(6,updated.evidenceCount)
        assertTrue(updated.value.startsWith("50%"))
        db.close()
    }
    @Test fun journalSurvivesNewInstanceHashesIdentityAndStopsAtDeadline() {
        val window=EvaluationJournal(context).start(60000)
        EvaluationJournal(context).record("offered","private-contact-key",JSONObject().put("kind","WA_REPLY_INBOUND"))
        val file=File(context.filesDir,window.getString("file"))
        assertFalse(file.readText().contains("private-contact-key"))
        assertTrue(file.readText().contains(ContentHashing.hash("private-contact-key")))
        val before=file.length()
        context.getSharedPreferences("evaluation_window",Context.MODE_PRIVATE).edit().putLong("end",1).commit()
        EvaluationJournal(context).record("late")
        assertEquals(before,file.length())
    }
}
