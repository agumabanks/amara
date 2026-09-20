package co.sanaa.agent.core.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TikTokCadenceTest {
    @Test fun randomizedBoundsAndDurableDueTime() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("tiktok_cadence", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(30 * 60_000L, TikTokCadence.randomDelayMillis(30, 0.0))
        assertEquals(30 * 60_000L, TikTokCadence.randomDelayMillis(30, 1.0))
        val cadence = TikTokCadence(context)
        val due = cadence.dueAt(30, 1000)
        assertEquals(due, TikTokCadence(context).dueAt(30, 2000))
        cadence.finishOpportunity("wrong-key", 30, due)
        assertEquals(due, cadence.dueAt(30, due))
        cadence.finishOpportunity("tiktok-due-$due", 30, due)
        val next = cadence.dueAt(30, due)
        assertTrue(next - due in 30 * 60_000L..30 * 60_000L)
        cadence.finishOpportunity("tiktok-due-$due", 30, due)
        assertEquals(next, cadence.dueAt(30, due))
        cadence.finishOpportunity("tiktok-due-$next",30,next,verified=false)
        assertEquals(next+5*60_000L,cadence.dueAt(30,next))
    }

    @Test fun ownerClosedOpportunityAdvancesInsteadOfKeepingItsTombstoneDueForever() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("tiktok_cadence", Context.MODE_PRIVATE).edit().clear().commit()
        val cadence = TikTokCadence(context)
        val due = cadence.dueAt(30, 1_000)
        cadence.skipOwnerClosedOpportunity("wrong-key", 30, due)
        assertEquals(due, cadence.dueAt(30, due))

        cadence.skipOwnerClosedOpportunity("tiktok-due-$due", 30, due)
        assertEquals(due + 30 * 60_000L, cadence.dueAt(30, due))
    }
    @Test fun tenMinuteCadenceDoesNotAccumulateRenderTimeOrBurstAfterPause() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("tiktok_cadence", Context.MODE_PRIVATE).edit().clear().commit()
        val cadence = TikTokCadence(context)
        val due = cadence.dueAt(10, 1000)
        cadence.finishOpportunity("tiktok-due-$due", 10, due + 120_000)
        val next = cadence.dueAt(10, due + 120_000)
        assertEquals(due + 600_000, next)
        cadence.finishOpportunity("tiktok-due-$next", 10, next + 1_300_000)
        assertEquals(next + 1_800_000, cadence.dueAt(10, next + 1_300_000))
    }

}
