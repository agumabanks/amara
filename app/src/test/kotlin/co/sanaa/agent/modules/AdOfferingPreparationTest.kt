package co.sanaa.agent.modules

import co.sanaa.agent.api.SokoListing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AdOfferingPreparationTest {
    private fun listing(id: String, title: String) = SokoListing(id, title, "", 1000, "", 1, 0, 0,
        "https://example.com/ad.jpg", JSONObject().put("slug", id))

    @Test fun rejectedCopyRotatesBeforeAnyDispatch() = runBlocking {
        val bad = listing("bad", "An unfamiliar long catalogue title")
        val good = listing("good", "Sanaa Smart Business Cards")
        val rejected = mutableListOf<String>()
        val result = AdOfferingPreparation.choose(listOf(bad, good), select = { it.firstOrNull() },
            headline = { AdHeadline.fallback(it.title) }, rejected = { rejected += it.id })
        assertEquals(listOf("bad"), rejected)
        assertEquals("good", result!!.listing.id)
        assertEquals("Smart Business Cards", result.headline)
    }

    @Test fun pinnedOfferingNeverSubstitutesAndMissingPinNeverSelects() = runBlocking {
        val rows = listOf(listing("bad", "An unfamiliar long catalogue title"), listing("good", "Office Chair"))
        val rejected = mutableListOf<String>()
        assertNull(AdOfferingPreparation.choose(rows, "bad", { error("must not rotate") },
            { AdHeadline.fallback(it.title) }, { rejected += it.id }))
        assertEquals(listOf("bad"), rejected)
        assertNull(AdOfferingPreparation.choose(rows, "missing", { error("must not rotate") },
            { error("must not generate") }, { error("must not reject") }))
    }

    @Test fun exhaustedPreparationIsBoundedAndCancellationPropagates() = runBlocking {
        val rows = (1..5).map { listing("$it", "An unfamiliar long catalogue title") }
        var attempts = 0
        assertNull(AdOfferingPreparation.choose(rows, select = { it.firstOrNull() },
            headline = { attempts++; throw AdHeadlineUnavailable() }, rejected = {}))
        assertEquals(3, attempts)
        try {
            AdOfferingPreparation.choose(rows, select = { it.firstOrNull() },
                headline = { throw CancellationException("owner stopped") }, rejected = { fail("Not a copy failure") })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
