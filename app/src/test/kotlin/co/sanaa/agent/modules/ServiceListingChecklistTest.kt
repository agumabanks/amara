package co.sanaa.agent.modules

import co.sanaa.agent.api.SokoListing
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ServiceListingChecklistTest {
    @Test fun longDescriptionDoesNotHideMissingBuyingFacts() {
        val row = SokoListing("service:7", "Logo Design", "Beautiful designs. ".repeat(30), 100000,
            "Services", 1, 0, null, "https://soko24.co/logo.jpg", JSONObject().put("offering_type", "SERVICE").put("slug", "logo"))
        assertEquals(5, ServiceListingChecklist.missing(row).size)
        row.raw.put("deliverables", "One logo").put("options", "None").put("pricing_type", "fixed")
            .put("delivery_timeframe", "3 days").put("required_inputs", "Company name and brief")
        assertTrue(ServiceListingChecklist.missing(row).isEmpty())
    }
}
