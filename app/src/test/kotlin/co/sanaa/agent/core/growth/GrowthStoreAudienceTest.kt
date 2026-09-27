package co.sanaa.agent.core.growth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.api.SokoListing
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GrowthStoreAudienceTest {
    @Test fun groupRotationHistoryDoesNotCrossShopOrGroup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("amara_growth.db")
        val listing = SokoListing("chair-1", "Office chair", "Blue fabric", 120000, "Furniture",
            1, 0, 2, "https://soko24.co/uploads/chair.jpg", JSONObject().put("slug", "office-chair-1"))
        GrowthStore(context).use { store ->
            val first = GrowthStore.groupAudience("708:128", "group-a")
            store.bind("promotion-1", first, listing)
            store.outcome("promotion-1", "VERIFIED")
            assertEquals(listOf("chair-1"), store.history(first).map { it.listingId })
            assertTrue(store.history(GrowthStore.groupAudience("254:24", "group-a")).isEmpty())
            assertTrue(store.history(GrowthStore.groupAudience("708:128", "group-b")).isEmpty())
            assertTrue(store.history("Sales team").isEmpty())
            repeat(250) { index ->
                store.bind("large-$index", first, listing.copy(id="catalogue-$index"))
            }
            assertEquals(251,store.history(first).size)
            store.bind("repeat-chair",first,listing)
            assertEquals(251,store.history(first).size)
        }
    }
}
