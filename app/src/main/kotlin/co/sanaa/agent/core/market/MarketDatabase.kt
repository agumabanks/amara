package co.sanaa.agent.core.market

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Jiji Market Intelligence Database.
 * Stores scraped listings, price history, competitor analysis.
 */
class MarketDatabase(context: Context) : SQLiteOpenHelper(context, "amara_market.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        // Jiji listings scraped from the app
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS jiji_listings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                listing_key TEXT NOT NULL UNIQUE,  -- jiji listing identifier
                title TEXT NOT NULL,
                price_ugx INTEGER,
                description TEXT,
                seller_name TEXT,
                seller_rating REAL,
                location TEXT,
                category TEXT,
                image_count INTEGER DEFAULT 0,
                is_featured INTEGER DEFAULT 0,
                source TEXT NOT NULL DEFAULT 'JIJI',
                scraped_at INTEGER NOT NULL,
                first_seen INTEGER NOT NULL,
                last_seen INTEGER NOT NULL
            )
        """)

        // Price history for tracking changes
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS price_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                listing_key TEXT NOT NULL,
                price_ugx INTEGER NOT NULL,
                recorded_at INTEGER NOT NULL,
                FOREIGN KEY (listing_key) REFERENCES jiji_listings(listing_key)
            )
        """)

        // Competitor sellers we track
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS competitors (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                seller_name TEXT NOT NULL UNIQUE,
                seller_rating REAL,
                listing_count INTEGER DEFAULT 0,
                avg_price_ugx INTEGER,
                top_category TEXT,
                first_seen INTEGER NOT NULL,
                last_seen INTEGER NOT NULL
            )
        """)

        // Market analysis snapshots
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS market_snapshots (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                category TEXT NOT NULL,
                snapshot_at INTEGER NOT NULL,
                avg_price_ugx INTEGER,
                min_price_ugx INTEGER,
                max_price_ugx INTEGER,
                median_price_ugx INTEGER,
                total_listings INTEGER,
                new_listings_24h INTEGER,
                hot_products TEXT  -- JSON array of trending product names
            )
        """)

        // Our own Soko listings vs market comparison
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS soko_vs_market (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                soko_product_id TEXT NOT NULL,
                soko_title TEXT NOT NULL,
                soko_price_ugx INTEGER,
                market_avg_ugx INTEGER,
                market_min_ugx INTEGER,
                market_max_ugx INTEGER,
                price_position TEXT,  -- BELOW_MARKET, AT_MARKET, ABOVE_MARKET
                competitors_count INTEGER,
                analyzed_at INTEGER NOT NULL,
                recommendation TEXT  -- what Amara suggests
            )
        """)

        // Market opportunities Amara identifies
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS opportunities (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                description TEXT,
                category TEXT,
                opportunity_type TEXT,  -- PRICE_GAP, DEMAND_SURGE, SUPPLY_SHORTAGE, TRENDING
                potential_revenue_ugx INTEGER,
                confidence REAL,
                status TEXT DEFAULT 'OPEN',  -- OPEN, ACTED_ON, EXPIRED
                created_at INTEGER NOT NULL,
                acted_at INTEGER
            )
        """)

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_jiji_category ON jiji_listings(category)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_jiji_seller ON jiji_listings(seller_name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_jiji_scraped ON jiji_listings(scraped_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_market_source ON jiji_listings(source)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_price_listing ON price_history(listing_key)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_snapshots_category ON market_snapshots(category)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE jiji_listings ADD COLUMN source TEXT NOT NULL DEFAULT 'JIJI'")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_market_source ON jiji_listings(source)")
        }
    }
}
