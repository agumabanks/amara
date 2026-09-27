package co.sanaa.agent.modules

import co.sanaa.agent.api.SokoListing

/** Tries only reversible copy preparation; a pinned offering is never substituted. */
object AdOfferingPreparation {
    data class Prepared(val listing: SokoListing, val headline: String)

    suspend fun choose(
        listings: List<SokoListing>,
        pinnedId: String = "",
        select: (List<SokoListing>) -> SokoListing?,
        headline: suspend (SokoListing) -> String,
        rejected: (SokoListing) -> Unit,
    ): Prepared? {
        val remaining = listings.distinctBy { it.id }.toMutableList()
        repeat(if (pinnedId.isBlank()) 3 else 1) {
            val listing = if (pinnedId.isNotBlank()) remaining.singleOrNull { it.id == pinnedId }
                else select(remaining)
            if (listing == null) return null
            try { return Prepared(listing, headline(listing)) }
            catch (_: AdHeadlineUnavailable) { rejected(listing) }
            remaining.removeAll { it.id == listing.id }
        }
        return null
    }
}
