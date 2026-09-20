package co.sanaa.agent.core.market

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.actions.AccessibilityActions
import co.sanaa.agent.actions.WhatsAppScreenSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class JijiResearchTest {
    @Test fun resultsMustBelongToJijiAndTheRequestedQuery() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        MarketDatabase(context).use { db ->
            val scraper=JijiScraper(context,db,AccessibilityActions(context))
            val result=WhatsAppScreenSnapshot(JijiScraper.PACKAGE_JIJI,listOf("Receipt printer","USh 50,000","Receipt printer USB","Kampala"),"")
            assertTrue(scraper.searchResultsMatch(result,"receipt printer"))
            assertFalse(scraper.searchResultsMatch(result,"office chair"))
            assertFalse(scraper.searchResultsMatch(result.copy(packageName="com.whatsapp"),"receipt printer"))
            assertFalse(scraper.isInstalled())
        }
    }
}
