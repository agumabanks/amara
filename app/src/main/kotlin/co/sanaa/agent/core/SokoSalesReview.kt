package co.sanaa.agent.core

import co.sanaa.agent.actions.SokoInventoryItem

/** Read-only, fact-bound next actions for an owner who asks how to grow this shop. */
internal object SokoSalesReview {
    fun report(shopName: String, products: List<SokoInventoryItem>, reachedEnd: Boolean): String {
        val available = products.filter { it.stockState.trim().equals("in stock", ignoreCase = true) }
        if (available.isEmpty()) {
            return "I could not verify an in-stock product in $shopName. Review the current Terminal catalogue and stock before marketing a specific item; nothing was published or sent."
        }
        val priced = available.filter { it.priceUgx != null && it.priceUgx > 0 }
        val highlight = priced.maxByOrNull { it.priceUgx ?: 0 } ?: available.first()
        val entry = priced.filter { it.name != highlight.name }.minByOrNull { it.priceUgx ?: Long.MAX_VALUE }
            ?: available.firstOrNull { it.name != highlight.name }
        val weak = products.firstOrNull { it.weakReasons.isNotEmpty() }
        fun evidence(item: SokoInventoryItem) = "${item.name} (${item.priceUgx?.let { "UGX $it" } ?: "price not shown"}, ${item.stockState})"
        val second = if (entry != null) {
            "2. Test an approved low-price entry offer using ${evidence(entry)} with relevant buyers who have consented; track qualified inquiries."
        } else {
            "2. Verify another in-stock product and its price before offering a second option to buyers."
        }
        val third = if (weak != null) {
            "3. Review ${weak.name}'s listing: ${weak.weakReasons.joinToString()}. Verify the edit in Terminal before using it in marketing."
        } else {
            "3. Compare real inquiries, orders, stock and product costs before choosing a discount or scaling any campaign; those outcomes were not verified by this scan."
        }
        return "Current signed shop: $shopName. Read ${products.size} visible product cards${if (reachedEnd) " to the visible end" else "; coverage is partial"}. " +
            "1. Prepare a product highlight for ${evidence(highlight)} using its verified photo, price and benefit; choose an approved channel and measure inquiries. " +
            "$second $third Nothing was published, sent or edited in this review."
    }
}
