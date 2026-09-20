package co.sanaa.agent.modules

import co.sanaa.agent.api.SokoListing
import co.sanaa.agent.core.growth.MarketGrowthReview

/** A recognisable buying situation, then catalogue evidence. No invented customer stories. */
internal object AudienceAdCopy {
    data class Angle(val opening: String, val question: String)
    fun angle(listing: SokoListing, variant: Int = 0): Angle {
        val title=listing.title.lowercase()
        val service=listing.raw.optString("offering_type")=="SERVICE"
        val choices=when {
            "receipt" in title -> listOf(Angle("A customer asks for a receipt. Keep the sale clear for both of you.","What business details should appear on yours?"),Angle("For the shop counter, where every sale needs a clear record.","Which receipt size does your counter use?"))
            "stamp" in title && "ink" !in title && "pad" !in title -> listOf(Angle("Signing off invoices or office paperwork every day?", "What wording do you need on your stamp?"),Angle("For the desk that handles the day's paperwork.","Which details should your stamp show?"))
            "ink" in title || "ink pad" in title -> listOf(Angle("Stamp impressions getting faint? Check the ink and pad before replacing the stamp.","Which stamp model and ink colour are you using?"))
            "business card" in title -> listOf(Angle("After a good conversation, give them your details to take away.","What name and contact details should your cards carry?"))
            "chair" in title && !service -> listOf(Angle("Choosing a chair for the desk you use every day?", "What desk height and space are you working with?"),Angle("Setting up a work desk? Start with the chair's fit.","Which dimensions do you need us to check?"))
            "sms" in title -> listOf(Angle("Need to update customers who have asked to hear from you?", "Is this for appointment reminders, order updates or an announcement?"))
            "banner" in title || "poster" in title -> listOf(Angle("Your offer needs to be readable before someone walks past.","Where will it be displayed, and what size do you need?"))
            "badge" in title || "id card" in title -> listOf(Angle("Getting staff or guests ready for the day?", "How many people need one, and when are they needed?"))
            "website" in title || "web design" in title -> listOf(Angle("Customers asking where they can see what you offer?", "What should visitors be able to find or do on your site?"))
            service -> listOf(Angle("Working out the details for ${listing.title.trim()}?", "Send your brief and deadline so we can confirm the scope."))
            else -> listOf(Angle("Checking whether ${listing.title.trim()} fits what you need?", "Which detail would help you decide?"))
        }
        return choices[Math.floorMod(variant,choices.size)]
    }
    fun fact(listing: SokoListing, limit: Int = 150): String {
        val plain=MarketGrowthReview.plainText(listing.description).replace(Regex("\\s+")," ").trim()
        // Keep a complete factual sentence. Do not turn unverifiable catalogue hype into a trust claim.
        return plain.split(Regex("(?<=[.!?])\\s+")).firstOrNull { sentence ->
            sentence.length in 1..limit && !Regex("(?i)guarantee|best|number one|#1|trusted by|everyone|perfect|life.changing|limited.time|hurry|only today").containsMatchIn(sentence)
        }.orEmpty()
    }
}
