package co.sanaa.agent.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.core.knowledge.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class LearningDecisionTest {
    @Test fun onlyExecutedChoiceIsRecordedOnceWithItsActualOutcome() {
        val c=ApplicationProvider.getApplicationContext<Context>();c.deleteDatabase("amara_learning.db")
        val db=LearningDatabase(c)
        try {
            val loop=LearningLoop(c,db)
            repeat(4) { loop.recordPattern("TIMING_V2","ACTION@10","Supported execution timing",0.8) }
            repeat(5) { loop.scoreFactor("ACTION",10) }
            db.readableDatabase.rawQuery("SELECT COUNT(*) FROM adaptation_decisions",null).use { it.moveToFirst();assertEquals(0,it.getInt(0)) }
            repeat(2) { loop.appliedDecision("work1","ACTION",10) }
            loop.decisionOutcome("work1","FAILED")
            db.readableDatabase.rawQuery("SELECT COUNT(*),outcome FROM adaptation_decisions",null).use { it.moveToFirst();assertEquals(1,it.getInt(0));assertEquals("FAILED",it.getString(1)) }
            db.readableDatabase.rawQuery("SELECT applied_count FROM learned_patterns",null).use { it.moveToFirst();assertEquals(1,it.getInt(0)) }
        } finally { db.close() }
    }
}
