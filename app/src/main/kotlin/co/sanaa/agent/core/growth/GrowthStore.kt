package co.sanaa.agent.core.growth

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import co.sanaa.agent.api.SokoListing
import co.sanaa.agent.modules.TikTokProductContent
import org.json.JSONObject

/** Records business work as attempts and verified outcomes, never as invented revenue. */
class GrowthStore(context: Context) : SQLiteOpenHelper(context, "amara_growth.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE promotions (work_key TEXT PRIMARY KEY, audience TEXT NOT NULL, listing_id TEXT NOT NULL, offering_type TEXT NOT NULL, created_at INTEGER NOT NULL, outcome TEXT NOT NULL)")
        db.execSQL("CREATE TABLE reports (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE reply_results (work_key TEXT PRIMARY KEY, latency_ms INTEGER NOT NULL, outcome TEXT NOT NULL, updated_at INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    data class Promotion(val listingId: String, val type: String)
    fun history(audience: String): List<Promotion> = readableDatabase.rawQuery(
        "SELECT listing_id, offering_type FROM promotions WHERE audience=? ORDER BY created_at DESC LIMIT 200", arrayOf(audience),
    ).use { c -> buildList { while (c.moveToNext()) add(Promotion(c.getString(0), c.getString(1))) } }

    fun bind(key: String, audience: String, listing: SokoListing) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO promotions VALUES (?, ?, ?, ?, ?, 'PREPARED')",
            arrayOf<Any>(key, audience, listing.id, typeOf(listing), System.currentTimeMillis()))
    }
    fun outcome(key: String, outcome: String) = writableDatabase.execSQL(
        "UPDATE promotions SET outcome=? WHERE work_key=?", arrayOf(outcome, key))
    fun recordReply(key: String, observedAt: Long, outcome: String) {
        if (observedAt <= 0) return
        val now = System.currentTimeMillis()
        writableDatabase.execSQL("INSERT OR REPLACE INTO reply_results VALUES (?, ?, ?, ?)",
            arrayOf<Any>(key, (now - observedAt).coerceAtLeast(0), outcome, now))
    }
    fun saveReport(report: JSONObject) = writableDatabase.execSQL("INSERT OR REPLACE INTO reports VALUES (1, ?, ?)",
        arrayOf<Any>(report.toString(), System.currentTimeMillis()))
    fun report(): JSONObject = readableDatabase.rawQuery("SELECT payload FROM reports WHERE id=1", null).use {
        if (it.moveToFirst()) JSONObject(it.getString(0)) else JSONObject()
    }
    fun dashboard(): Map<String, Any> {
        val since = System.currentTimeMillis() - 7 * 86_400_000L
        val promotions = readableDatabase.rawQuery("SELECT outcome, COUNT(*) FROM promotions WHERE created_at>=? GROUP BY outcome", arrayOf(since.toString())).use {
            buildMap<String, Int> { while (it.moveToNext()) put(it.getString(0), it.getInt(1)) }
        }
        val replies = readableDatabase.rawQuery("SELECT outcome, COUNT(*), AVG(latency_ms) FROM reply_results WHERE updated_at>=? GROUP BY outcome", arrayOf(since.toString())).use {
            buildList { while (it.moveToNext()) add(mapOf("outcome" to it.getString(0), "count" to it.getInt(1), "averageLatencySeconds" to it.getDouble(2) / 1000)) }
        }
        val verifiedLatencies = readableDatabase.rawQuery("SELECT latency_ms FROM reply_results WHERE updated_at>=? AND outcome='DONE' ORDER BY latency_ms", arrayOf(since.toString())).use {
            buildList<Long> { while(it.moveToNext()) add(it.getLong(0)) }
        }
        val report = report()
        return mapOf("promotionOutcomes" to promotions, "replyOutcomes" to replies,
            "verifiedReplyP95Seconds" to (if(verifiedLatencies.isEmpty()) "UNKNOWN" else verifiedLatencies[(kotlin.math.ceil(verifiedLatencies.size * .95).toInt()-1).coerceAtLeast(0)] / 1000.0),
            "review" to report.optString("summary", "Waiting for market and catalogue review"),
            "listingPlans" to jsonRows(report.optJSONArray("decisions")),
            "sourcingBriefs" to jsonRows(report.optJSONArray("sourcingBriefs")),
            "reviewedAt" to report.optLong("generatedAt"), "revenueStatus" to "Only confirmed orders/payments count as revenue")
    }

    private fun jsonRows(rows: org.json.JSONArray?): List<Map<String, Any>> = buildList {
        if (rows != null) for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            add(row.keys().asSequence().associateWith { row.get(it) })
        }
    }

    companion object {
        fun typeOf(listing: SokoListing) = if (listing.raw.optString("offering_type") == "SERVICE") "SERVICE" else "PRODUCT"
        /** Cycle through unseen offerings before recycling; alternate products/services when possible. */
        fun select(listings: List<SokoListing>, history: List<Promotion>, inquiryCounts: Map<String, Int> = emptyMap(), random: (Int) -> Int = { kotlin.random.Random.nextInt(it) }): SokoListing? {
            val usable = listings.distinctBy { it.id }.filter { it.stock != 0 && TikTokProductContent.from(it) != null }
            if (usable.isEmpty()) return null
            val recentIds = history.map { it.listingId }
            var pool = usable.filter { it.id !in recentIds }
            if (pool.isEmpty()) {
                val last = history.firstOrNull()?.listingId
                val eligible = usable.filter { it.id != last }.ifEmpty { usable }
                val oldest = eligible.maxOf { recentIds.indexOf(it.id) }
                pool = eligible.filter { recentIds.indexOf(it.id) == oldest }
            }
            val lastType = history.firstOrNull()?.type
            val otherType = pool.filter { typeOf(it) != lastType }.ifEmpty { pool }
            // Explore one time in four; otherwise prefer evidenced demand within the
            // rotation pool. Counts are qualified inquiries, never fabricated sales.
            val explore = inquiryCounts.isEmpty() || random(4).coerceIn(0,3) == 0
            val best = otherType.maxOf { inquiryCounts[it.id] ?: 0 }
            val ranked = if (explore || best == 0) otherType else otherType.filter { (inquiryCounts[it.id] ?: 0) == best }
            return ranked[random(ranked.size).coerceIn(0, ranked.lastIndex)]
        }
    }
}
