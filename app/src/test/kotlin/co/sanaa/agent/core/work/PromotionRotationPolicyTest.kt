package co.sanaa.agent.core.work

import org.junit.Assert.assertTrue
import org.junit.Test

class PromotionRotationPolicyTest {
    @Test fun historyScalesWithCatalogueCadenceAndCap() {
        val now = 2_000_000_000_000L
        val smallFast = PromotionRotationPolicy.historyWindow(20, 30, 24, now)
        val largeSlow = PromotionRotationPolicy.historyWindow(500, 240, 3, now)
        assertTrue(smallFast.limit >= 60)
        assertTrue(largeSlow.limit > smallFast.limit)
        assertTrue(largeSlow.sinceMillis < smallFast.sinceMillis)
    }

    @Test fun tinyShopStillRetainsSeveralCompleteCycles() {
        val window = PromotionRotationPolicy.historyWindow(4, 30, 20, 2_000_000_000_000L)
        assertTrue(window.limit >= 12)
        assertTrue(window.catalogueSize == 4)
    }
}
