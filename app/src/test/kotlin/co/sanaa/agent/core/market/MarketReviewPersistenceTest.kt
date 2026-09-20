package co.sanaa.agent.core.market

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.api.SokoListing
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class MarketReviewPersistenceTest {
    @Test fun repeatedReviewKeepsSingleCurrentComparisonAndHourlySnapshot() {
        val context=ApplicationProvider.getApplicationContext<Context>();context.deleteDatabase("amara_market.db")
        val db=MarketDatabase(context)
        try {
            val now=System.currentTimeMillis()
            repeat(3) { i -> db.writableDatabase.execSQL("INSERT INTO jiji_listings(listing_key,title,price_ugx,category,source,scraped_at,first_seen,last_seen,description,seller_name,location) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any>("item$i","Laser Printer 123",100000+i*1000,"Printers","JIJI",now,now,now,"Printer","Seller$i","Kampala")) }
            val market=MarketAnalyzer(context,db)
            val item=SokoListing("1","Laser Printer 123","",99000,"Printers",0,0,1,null,JSONObject())
            repeat(2) { market.recordReview(listOf(item),now) }
            db.readableDatabase.rawQuery("SELECT COUNT(*),competitors_count FROM soko_vs_market",null).use { it.moveToFirst();assertEquals(1,it.getInt(0));assertEquals(3,it.getInt(1)) }
            db.readableDatabase.rawQuery("SELECT COUNT(*) FROM market_snapshots",null).use { it.moveToFirst();assertEquals(1,it.getInt(0)) }
        } finally { db.close() }
    }
}
