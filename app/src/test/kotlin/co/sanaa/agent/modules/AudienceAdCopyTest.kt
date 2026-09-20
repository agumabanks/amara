package co.sanaa.agent.modules

import co.sanaa.agent.api.SokoListing
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AudienceAdCopyTest {
    private fun item(title: String, description: String="")=SokoListing("x",title,description,10000,"",1,0,null,"https://soko24.co/image.jpg",JSONObject().put("slug","item"))
    @Test fun differentBuyersGetRelevantQuestionsWithoutInventedBenefits() {
        val receipt=AudienceAdCopy.angle(item("Custom receipt books"))
        val ink=AudienceAdCopy.angle(item("Stamp ink refill"))
        assertTrue(receipt.opening.contains("receipt"))
        assertTrue(receipt.question.contains("business details"))
        assertTrue(ink.question.contains("stamp model"))
        assertFalse(ink.opening.contains("fits all"))
        assertNotEquals(receipt,ink)
    }
    @Test fun evidenceExcludesUnsupportedTrustAndUrgencyClaims() {
        assertEquals("Blue fabric.",AudienceAdCopy.fact(item("Chair","The best chair, trusted by everyone. Blue fabric.")))
        assertEquals("",AudienceAdCopy.fact(item("Chair","Guaranteed to cure back pain.")))
        assertEquals("",AudienceAdCopy.fact(item("Chair")))
    }
    @Test fun unknownOfferDoesNotAcquireInventedAudienceOrExperience() {
        val angle=AudienceAdCopy.angle(item("Model Q accessory"))
        assertTrue(angle.opening.contains("Model Q accessory"))
        assertFalse(angle.opening.contains("I use"))
        assertFalse(angle.opening.contains("parents"))
    }
}
