package co.sanaa.agent.core

import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.core.work.*
import co.sanaa.agent.core.work.WorkStatus
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ModuleActivityStoreTest {
    @Test fun distinctOutcomesSurviveReopeningWithoutInventingEffects() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        ModuleActivityStore(context).use { store ->
            store.record("skip","YOUTUBE_SHORT_PUBLISH","SKIPPED","Cap reached")
            store.record("hold","YOUTUBE_SHORT_PUBLISH","ESCALATED","Channel not verified")
            store.record("partial","YOUTUBE_SHORT_PUBLISH","PARTIAL","Preparation incomplete")
        }
        ModuleActivityStore(context).use { store ->
            store.record("skip","YOUTUBE_SHORT_PUBLISH","SKIPPED","Repeated observation")
            val stats=store.dashboard(emptyList())["YouTube"] as Map<*,*>
            assertEquals(3,stats["attempts"])
            assertEquals(1,stats["skipped"]);assertEquals(1,stats["held"]);assertEquals(1,stats["partial"])
            assertEquals(0,stats["verified"]);assertEquals(0,stats["completed"])
        }
    }
    @Test fun taskCompletionCannotInventPublicationAndRetriesAreNotDoubleRecorded() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val item=WorkItem("a",Domain.TIKTOK,WorkKind.TIKTOK_ANALYTICS_CHECK,baseValueKes=1.0,urgencyHalfLifeHours=1.0,estimatedScreenSeconds=1)
        ModuleActivityStore(context).use { store ->
            repeat(2) { store.record(WorkResult(item,WorkStatus.DONE)) }
            val stats=store.dashboard(emptyList())["TikTok"] as Map<*,*>
            assertEquals(1,stats["completed"]);assertEquals(1,stats["attempts"]);assertEquals(0,stats["verified"])
            val now=System.currentTimeMillis()
            val receipt=SideEffectTransaction("pub","post_tiktok","tiktok","hash",null,SideEffectState.UNCERTAIN,now,now,"Unproven")
            val uncertain=store.dashboard(listOf(receipt))["TikTok"] as Map<*,*>
            assertEquals(1,uncertain["uncertain"]);assertEquals(0,uncertain["verified"])
        }
    }
}
