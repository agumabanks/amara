package co.sanaa.agent.core.social

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class TikTokSocialStoreTest {
    private fun store(): TikTokSocialStore {
        val c=ApplicationProvider.getApplicationContext<Context>()
        c.deleteDatabase("amara_tiktok_social.db")
        return TikTokSocialStore(c)
    }
    @Test fun reconciliationDoesNotRearmAndHasCooldown() {
        val s=store();val key="a".repeat(64);val at=200000000L
        s.observe(key,"creator",JSONObject(),at);assertTrue(s.reserve(key,"creator","Specific observation",at));s.outcome(key,"UNCERTAIN")
        assertNotNull(s.nextReconciliation(at+3_600_001))
        assertNull(s.nextReconciliation(at+3_600_002))
        s.recordReconciliation(key,"Specific observation",true,at+3_600_003)
        assertFalse(s.eligible(key,"creator",at+86_400_001))
    }
    @Test fun reservationSurvivesRestartAndUncertainOutcomeNeverRearms() {
        val s=store();val key="a".repeat(64);val now=200000000L
        s.observe(key,"creator",JSONObject(),now)
        assertTrue(s.reserve(key,"creator","A contextual question",now))
        s.close()
        val reopened=TikTokSocialStore(ApplicationProvider.getApplicationContext())
        assertFalse(reopened.reserve(key,"creator","Another question",now+86400001))
        reopened.outcome(key,"UNCERTAIN")
        assertFalse(reopened.eligible(key,"creator",now+86400001))
    }
    @Test fun rollingLimitsAndCreatorCooldownApplyEvenWhenFailed() {
        val s=store();val now=200000000L
        for(i in 0..12) s.observe(co.sanaa.agent.core.ContentHashing.hash("limit$i"),"creator$i",JSONObject(),now)
        for(i in 0..11) {
            val time=now+i*1200001L
            assertTrue(s.reserve(co.sanaa.agent.core.ContentHashing.hash("limit$i"),"creator$i","Specific comment $i",time))
            s.outcome(co.sanaa.agent.core.ContentHashing.hash("limit$i"),"FAILED")
        }
        assertFalse(s.eligible(co.sanaa.agent.core.ContentHashing.hash("limit12"),"creator12",now+12*1200001L))
        assertTrue(s.eligible(co.sanaa.agent.core.ContentHashing.hash("limit12"),"creator12",now+86400001L))
    }
    @Test fun restoredReservationCannotBeSentAgainAndMetricsRemainUnknownWhenMissing() {
        val s=store();val key="f".repeat(64);s.observe(key,"creator",JSONObject(),200000000)
        assertTrue(s.reserve(key,"creator","Good question",200000000))
        val backup=s.exportMemory();s.close()
        val restored=store();restored.importMemory(backup)
        assertFalse(restored.eligible(key,"creator",300000000))
        restored.metrics(JSONObject().put("handle","@shop"),1)
        restored.metrics(JSONObject().put("handle","@shop").put("followers",12),2)
        assertFalse(restored.summary().has("observed_follower_change"))
    }
    @Test fun oldClaimsSurviveWhenDetailedResearchIsTrimmedAndAccountsDoNotMix() {
        val s=store();val key="a".repeat(64);val now=200000000L
        s.observe(key,"old creator",JSONObject(),now)
        assertTrue(s.reserve(key,"old creator","Original response",now))
        for(i in 1..260) s.observe(co.sanaa.agent.core.ContentHashing.hash("post$i"),"creator$i",JSONObject(),now+i)
        val backup=s.exportMemory()
        assertEquals(250,backup.getJSONArray("posts").length())
        s.close()
        val restored=store();restored.importMemory(backup)
        assertFalse(restored.eligible(key,"old creator",now+86400001))
        restored.metrics(JSONObject().put("handle","@first").put("followers",50),1)
        restored.metrics(JSONObject().put("handle","@second").put("followers",100),2)
        assertFalse(restored.summary().has("observed_follower_change"))
    }

    @Test fun unchangedReviewedPostDoesNotSpendAnotherModelCall() {
        val s=store();val key="e".repeat(64)
        s.observe(key,"creator",JSONObject().put("caption","A public caption"))
        assertTrue(s.needsReview(key))
        s.decision(key,JSONObject().put("action","skip"))
        s.observe(key,"creator",JSONObject().put("caption","A public caption"))
        assertFalse(s.needsReview(key))
        assertTrue(s.needsReview(key,System.currentTimeMillis()+8L*86400000))
    }

}
