package co.sanaa.agent.core.knowledge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * LearningLoop — Amara's self-improvement engine.
 * 
 * She tracks every action, learns from successes/failures, and updates
 * her own skills/behaviors. The loop is:
 * 
 * 1. OBSERVE: Record what happened
 * 2. ANALYZE: Compare against expectations
 * 3. LEARN: Extract patterns
 * 4. UPDATE: Modify skills/behaviors
 * 5. REPEAT
 * 
 * Stored in amara_learning.db
 */
class LearningDatabase(context: Context) : SQLiteOpenHelper(context, "amara_learning.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        // Every action Amara takes
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS action_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                action_type TEXT NOT NULL,      -- WA_FOLLOWUP, TIKTOK_POST, JIJI_SCRAPE, etc.
                domain TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                completed_at INTEGER,
                success INTEGER,
                details TEXT,                    -- JSON with action-specific data
                error TEXT,                      -- Error message if failed
                screen_seconds_used INTEGER,
                tokens_used INTEGER
            )
        """)

        // Patterns Amara discovers
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS learned_patterns (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                pattern_type TEXT NOT NULL,     -- TIMING, CONTENT, PRICING, ENGAGEMENT
                pattern_key TEXT NOT NULL,      -- e.g. "tiktok_post_18h"
                pattern_value TEXT NOT NULL,    -- e.g. "Posts at 18h get 2x engagement"
                confidence REAL NOT NULL,       -- 0.0 to 1.0
                evidence_count INTEGER,          -- How many observations support this
                first_seen INTEGER NOT NULL,
                last_seen INTEGER NOT NULL,
                applied_count INTEGER DEFAULT 0
            )
        """)

        // Skill performance tracking
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS skill_performance (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                skill_name TEXT NOT NULL,
                total_attempts INTEGER DEFAULT 0,
                successes INTEGER DEFAULT 0,
                failures INTEGER DEFAULT 0,
                avg_screen_seconds REAL,
                last_improved_at INTEGER,
                current_version INTEGER DEFAULT 1
            )
        """)

        // Self-improvement actions taken
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS improvement_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                improved_at INTEGER NOT NULL,
                component TEXT NOT NULL,        -- SOUL, SKILL, CONTEXT, MODE
                change_type TEXT NOT NULL,      -- ADD, REMOVE, MODIFY
                old_value TEXT,
                new_value TEXT,
                reason TEXT,
                triggered_by TEXT               -- What caused the improvement
            )
        """)

        // Market intelligence learnings
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS market_learnings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                category TEXT,
                product_name TEXT,
                our_price_ugx INTEGER,
                market_avg_ugx INTEGER,
                best_performer TEXT,            -- Which seller/product did best
                insight TEXT,                   -- What we learned
                action_taken TEXT,              -- What we did about it
                learned_at INTEGER NOT NULL
            )
        """)

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_action_type ON action_log(action_type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_action_domain ON action_log(domain)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_action_time ON action_log(started_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pattern_type ON learned_patterns(pattern_type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_skill_name ON skill_performance(skill_name)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
}

/**
 * LearningLoop — Main API for self-improvement.
 */
class LearningLoop(
    private val context: Context,
    private val db: LearningDatabase
) {
    /** Extracts an evidence-backed timing/reliability pattern after every outcome. */
    fun observeOutcome(actionType: String, success: Boolean, atMs: Long = System.currentTimeMillis()) {
        val hour = java.time.Instant.ofEpochMilli(atMs).atZone(java.time.ZoneId.systemDefault()).hour
        // Only samples in this local-hour bucket support a timing claim.
        val samples = db.readableDatabase.rawQuery(
            "SELECT started_at, success FROM action_log WHERE action_type=? AND started_at>=?",
            arrayOf(actionType, (atMs - 30L * 86_400_000).toString()),
        ).use { c -> buildList { while (c.moveToNext()) {
            if (java.time.Instant.ofEpochMilli(c.getLong(0)).atZone(java.time.ZoneId.systemDefault()).hour == hour)
                add(c.getInt(1) == 1)
        } } }
        val evidence = samples.size
        if (evidence < 3) return
        val rate = samples.count { it }.toDouble() / evidence
        recordPattern(
            type = "TIMING_V2", key = "${actionType}@${hour}",
            value = "${(rate * 100).toInt()}% verified execution success around ${hour}:00 ($evidence samples; not conversion)",
            confidence = (evidence / 12.0).coerceAtMost(1.0) * rate,
        )
        db.writableDatabase.execSQL(
            "UPDATE learned_patterns SET evidence_count=? WHERE pattern_type='TIMING_V2' AND pattern_key=?",
            arrayOf(evidence, "${actionType}@${hour}"))
    }

    /** Conservative multiplier only after repeated evidence; never changes authority. */
    fun scoreFactor(actionType: String, hour: Int): Double {
        val pattern = getPatterns("TIMING_V2", 0.35).firstOrNull { it.key == "${actionType}@${hour}" }
            ?: return 1.0
        if (pattern.evidenceCount < 3) return 1.0
        return (0.75 + pattern.confidence * 0.5).coerceIn(0.75, 1.25)
    }

    fun exportMemory(): JSONObject {
        val patterns = JSONArray()
        db.readableDatabase.rawQuery(
            "SELECT pattern_type,pattern_key,pattern_value,confidence,evidence_count,first_seen,last_seen,applied_count FROM learned_patterns",
            null,
        ).use { c -> while (c.moveToNext()) patterns.put(JSONObject()
            .put("type", c.getString(0)).put("key", c.getString(1)).put("value", c.getString(2))
            .put("confidence", c.getDouble(3)).put("evidence_count", c.getInt(4))
            .put("first_seen", c.getLong(5)).put("last_seen", c.getLong(6)).put("applied_count", c.getInt(7))) }
        val skills = JSONArray()
        db.readableDatabase.rawQuery(
            "SELECT skill_name,total_attempts,successes,failures,avg_screen_seconds,current_version FROM skill_performance",
            null,
        ).use { c -> while (c.moveToNext()) skills.put(JSONObject()
            .put("name", c.getString(0)).put("attempts", c.getInt(1)).put("successes", c.getInt(2))
            .put("failures", c.getInt(3)).put("avg_seconds", c.getDouble(4)).put("version", c.getInt(5))) }
        return JSONObject().put("patterns", patterns).put("skills", skills)
    }

    fun importMemory(payload: JSONObject): Int {
        var merged = 0
        val writable = db.writableDatabase
        writable.beginTransaction()
        try {
            payload.optJSONArray("patterns")?.let { rows -> for (i in 0 until rows.length()) {
                val p = rows.optJSONObject(i) ?: continue
                if (p.optString("type").isBlank() || p.optString("key").isBlank()) continue
                val existingId = writable.rawQuery(
                    "SELECT id,last_seen FROM learned_patterns WHERE pattern_type=? AND pattern_key=? ORDER BY last_seen DESC LIMIT 1",
                    arrayOf(p.optString("type"), p.optString("key")),
                ).use { if (it.moveToFirst() && p.optLong("last_seen") > it.getLong(1)) it.getLong(0) else -1L }
                if (existingId > 0) writable.execSQL(
                    "UPDATE learned_patterns SET pattern_value=?,confidence=?,evidence_count=MAX(evidence_count,?),last_seen=?,applied_count=MAX(applied_count,?) WHERE id=?",
                    arrayOf(p.optString("value"), p.optDouble("confidence"), p.optInt("evidence_count"), p.optLong("last_seen"), p.optInt("applied_count"), existingId),
                ) else if (writable.rawQuery(
                    "SELECT 1 FROM learned_patterns WHERE pattern_type=? AND pattern_key=?", arrayOf(p.optString("type"), p.optString("key")),
                ).use { !it.moveToFirst() }) writable.execSQL(
                    "INSERT INTO learned_patterns(pattern_type,pattern_key,pattern_value,confidence,evidence_count,first_seen,last_seen,applied_count) VALUES(?,?,?,?,?,?,?,?)",
                    arrayOf(p.optString("type"), p.optString("key"), p.optString("value"), p.optDouble("confidence"), p.optInt("evidence_count"), p.optLong("first_seen"), p.optLong("last_seen"), p.optInt("applied_count")),
                )
                merged++
            } }
            payload.optJSONArray("skills")?.let { rows -> for (i in 0 until rows.length()) {
                val s = rows.optJSONObject(i) ?: continue
                val name = s.optString("name").trim()
                if (name.isBlank()) continue
                val exists = writable.rawQuery("SELECT 1 FROM skill_performance WHERE skill_name=?", arrayOf(name)).use { it.moveToFirst() }
                if (!exists) {
                    writable.execSQL(
                        "INSERT INTO skill_performance(skill_name,total_attempts,successes,failures,avg_screen_seconds,current_version) VALUES(?,?,?,?,?,?)",
                    arrayOf<Any>(name, s.optInt("attempts"), s.optInt("successes"), s.optInt("failures"), s.optDouble("avg_seconds"), s.optInt("version", 1)),
                    )
                    merged++
                }
            } }
            writable.setTransactionSuccessful()
        } finally { writable.endTransaction() }
        return merged
    }

    fun dashboard(limit: Int = 12): Map<String, Any> {
        val recent = mutableListOf<Map<String, Any>>()
        val cursor = db.readableDatabase.rawQuery(
            "SELECT action_type, domain, completed_at, success, details, error, screen_seconds_used FROM action_log ORDER BY id DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 50).toString()),
        )
        while (cursor.moveToNext()) recent += mapOf(
            "action" to cursor.getString(0),
            "domain" to cursor.getString(1),
            "timestamp" to cursor.getLong(2),
            "success" to (cursor.getInt(3) == 1),
            "details" to cursor.getString(4).orEmpty(),
            "error" to cursor.getString(5).orEmpty(),
            "screenSeconds" to cursor.getInt(6),
        )
        cursor.close()
        val totalCursor = db.readableDatabase.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(success),0), COALESCE(SUM(screen_seconds_used),0) FROM action_log",
            null,
        )
        val totals = if (totalCursor.moveToFirst()) mapOf(
            "attempts" to totalCursor.getInt(0),
            "successes" to totalCursor.getInt(1),
            "screenSeconds" to totalCursor.getInt(2),
        ) else mapOf("attempts" to 0, "successes" to 0, "screenSeconds" to 0)
        totalCursor.close()
        return mapOf("totals" to totals, "recent" to recent, "errors" to errorHistory(), "patterns" to getTopPatterns(5).map {
            mapOf("type" to it.type, "value" to it.value, "confidence" to it.confidence, "evidenceCount" to it.evidenceCount)
        })
    }

    /** Durable failure history; counts are evidence, not claims of an automatic fix. */
    fun errorHistory(): List<Map<String, Any>> = db.readableDatabase.rawQuery(
        """SELECT action_type, error, COUNT(*), MIN(completed_at), MAX(completed_at)
           FROM action_log WHERE success = 0 AND error IS NOT NULL AND error != ''
           GROUP BY action_type, error ORDER BY MAX(completed_at) DESC LIMIT 30""", null,
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(mapOf(
            "action" to cursor.getString(0),
            "error" to co.sanaa.agent.core.Redactor.redact(cursor.getString(1)).take(600),
            "count" to cursor.getInt(2), "firstSeen" to cursor.getLong(3),
            "lastSeen" to cursor.getLong(4),
        ))
    } }

    /**
     * Suggests recurring read-only work only after verified successes occurred on
     * at least three distinct days in the same three-hour window. Suggestions are
     * owner-visible evidence, never executable authority and never auto-enabled.
     */
    fun routineSuggestions(days: Int = 30): List<RoutineSuggestion> {
        val allowed = setOf(
            "SOKO_AUDIT", "SOKO_INVENTORY_CHECK", "TIKTOK_ANALYTICS_CHECK",
            "JIJI_SCRAPE", "JUMIA_CAPTURE", "MARKET_ANALYSIS",
            "INTERNAL_BRIEFING_PREP", "INTERNAL_COMMERCIAL_CYCLE",
        )
        val cutoff = System.currentTimeMillis() - days.coerceIn(7, 90) * 86_400_000L
        data class Sample(val action: String, val at: Long, val seconds: Int)
        val samples = mutableListOf<Sample>()
        db.readableDatabase.rawQuery(
            "SELECT action_type,started_at,screen_seconds_used FROM action_log WHERE success=1 AND started_at>=? ORDER BY started_at",
            arrayOf(cutoff.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val action = cursor.getString(0)
                if (action in allowed) samples += Sample(action, cursor.getLong(1), cursor.getInt(2))
            }
        }
        val zone = java.time.ZoneId.systemDefault()
        return samples.groupBy { sample ->
            val hour = java.time.Instant.ofEpochMilli(sample.at).atZone(zone).hour
            sample.action to (hour / 3) * 3
        }.mapNotNull { (key, grouped) ->
            val distinctDays = grouped.map {
                java.time.Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate()
            }.distinct()
            if (distinctDays.size < 3) return@mapNotNull null
            val (action, bucketHour) = key
            RoutineSuggestion(
                id = "learned:${action.lowercase()}:$bucketHour",
                actionType = action,
                label = action.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) },
                rule = "Observed on ${distinctDays.size} days around ${"%02d:00".format(bucketHour)}–${"%02d:00".format((bucketHour + 3).coerceAtMost(24))}",
                evidenceCount = grouped.size,
                confidence = (0.55 + distinctDays.size * 0.08).coerceAtMost(0.95),
                averageScreenSeconds = grouped.map { it.seconds }.average(),
            )
        }.sortedWith(compareByDescending<RoutineSuggestion> { it.confidence }.thenByDescending { it.evidenceCount })
    }

    /**
     * Record an action Amara took.
     */
    fun recordAction(
        actionType: String,
        domain: String,
        success: Boolean,
        details: String = "",
        error: String? = null,
        screenSeconds: Int = 0,
        tokensUsed: Int = 0,
        atMs: Long = System.currentTimeMillis(),
    ) {
        val now = atMs
        db.writableDatabase.execSQL(
            """INSERT INTO action_log 
               (action_type, domain, started_at, completed_at, success, details, error, screen_seconds_used, tokens_used)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf(actionType, domain, now.toString(), now.toString(), if (success) "1" else "0",
                co.sanaa.agent.core.Redactor.redact(details), error?.let(co.sanaa.agent.core.Redactor::redact), screenSeconds.toString(), tokensUsed.toString())
        )
    }

    /**
     * Record a pattern Amara discovered.
     */
    fun recordPattern(
        type: String,
        key: String,
        value: String,
        confidence: Double
    ) {
        val now = System.currentTimeMillis()
        // Check if pattern already exists
        val cursor = db.readableDatabase.rawQuery(
            "SELECT id, evidence_count FROM learned_patterns WHERE pattern_type = ? AND pattern_key = ?",
            arrayOf(type, key)
        )

        if (cursor.moveToFirst()) {
            val id = cursor.getLong(0)
            val count = cursor.getInt(1) + 1
            db.writableDatabase.execSQL(
                "UPDATE learned_patterns SET evidence_count = ?, last_seen = ?, confidence = ?, pattern_value = ? WHERE id = ?",
                arrayOf(count.toString(), now.toString(), confidence.toString(), value, id.toString())
            )
        } else {
            db.writableDatabase.execSQL(
                """INSERT INTO learned_patterns 
                   (pattern_type, pattern_key, pattern_value, confidence, evidence_count, first_seen, last_seen)
                   VALUES (?, ?, ?, ?, 1, ?, ?)""",
                arrayOf(type, key, value, confidence.toString(), now.toString(), now.toString())
            )
        }
        cursor.close()
    }

    /**
     * Get patterns by type.
     */
    fun getPatterns(type: String, minConfidence: Double = 0.5): List<LearnedPattern> {
        val cursor = db.readableDatabase.rawQuery(
            "SELECT pattern_type, pattern_key, pattern_value, confidence, evidence_count FROM learned_patterns WHERE pattern_type = ? AND confidence >= ? ORDER BY confidence DESC",
            arrayOf(type, minConfidence.toString())
        )

        val patterns = mutableListOf<LearnedPattern>()
        while (cursor.moveToNext()) {
            patterns.add(LearnedPattern(
                type = cursor.getString(0),
                key = cursor.getString(1),
                value = cursor.getString(2),
                confidence = cursor.getDouble(3),
                evidenceCount = cursor.getInt(4)
            ))
        }
        cursor.close()
        return patterns
    }

    /**
     * Get top patterns across all types.
     */
    fun getTopPatterns(limit: Int = 10): List<LearnedPattern> {
        val cursor = db.readableDatabase.rawQuery(
            "SELECT pattern_type, pattern_key, pattern_value, confidence, evidence_count FROM learned_patterns ORDER BY confidence DESC, evidence_count DESC LIMIT ?",
            arrayOf(limit.toString())
        )

        val patterns = mutableListOf<LearnedPattern>()
        while (cursor.moveToNext()) {
            patterns.add(LearnedPattern(
                type = cursor.getString(0),
                key = cursor.getString(1),
                value = cursor.getString(2),
                confidence = cursor.getDouble(3),
                evidenceCount = cursor.getInt(4)
            ))
        }
        cursor.close()
        return patterns
    }

    /**
     * Record skill performance.
     */
    fun recordSkillPerformance(skillName: String, success: Boolean, screenSeconds: Int) {
        val now = System.currentTimeMillis()
        val cursor = db.readableDatabase.rawQuery(
            "SELECT id, total_attempts, successes, failures, avg_screen_seconds FROM skill_performance WHERE skill_name = ?",
            arrayOf(skillName)
        )

        if (cursor.moveToFirst()) {
            val id = cursor.getLong(0)
            val total = cursor.getInt(1) + 1
            val successes = cursor.getInt(2) + if (success) 1 else 0
            val failures = cursor.getInt(3) + if (!success) 1 else 0
            val avgScreen = ((cursor.getDouble(4) * (total - 1)) + screenSeconds) / total

            db.writableDatabase.execSQL(
                "UPDATE skill_performance SET total_attempts = ?, successes = ?, failures = ?, avg_screen_seconds = ? WHERE id = ?",
                arrayOf(total.toString(), successes.toString(), failures.toString(), avgScreen.toString(), id.toString())
            )
        } else {
            db.writableDatabase.execSQL(
                "INSERT INTO skill_performance (skill_name, total_attempts, successes, failures, avg_screen_seconds) VALUES (?, 1, ?, ?, ?)",
                arrayOf(skillName, if (success) "1" else "0", if (!success) "1" else "0", screenSeconds.toString())
            )
        }
        cursor.close()
    }

    /**
     * Get skills that need improvement (>30% failure rate, min 5 attempts).
     */
    fun getSkillsNeedingImprovement(): List<SkillPerformance> {
        val cursor = db.readableDatabase.rawQuery(
            "SELECT skill_name, total_attempts, successes, failures, avg_screen_seconds FROM skill_performance WHERE total_attempts >= 5 AND (CAST(failures AS REAL) / total_attempts) > 0.3",
            null
        )

        val skills = mutableListOf<SkillPerformance>()
        while (cursor.moveToNext()) {
            skills.add(SkillPerformance(
                name = cursor.getString(0),
                totalAttempts = cursor.getInt(1),
                successes = cursor.getInt(2),
                failures = cursor.getInt(3),
                avgScreenSeconds = cursor.getDouble(4)
            ))
        }
        cursor.close()
        return skills
    }

    /**
     * Log an improvement Amara made to herself.
     */
    fun logImprovement(
        component: String,
        changeType: String,
        oldValue: String?,
        newValue: String,
        reason: String,
        triggeredBy: String
    ) {
        db.writableDatabase.execSQL(
            "INSERT INTO improvement_log (improved_at, component, change_type, old_value, new_value, reason, triggered_by) VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf(System.currentTimeMillis().toString(), component, changeType, oldValue, newValue, reason, triggeredBy)
        )
    }

    /**
     * Record a market learning.
     */
    fun recordMarketLearning(
        category: String,
        productName: String,
        ourPrice: Int,
        marketAvg: Int,
        bestPerformer: String,
        insight: String,
        actionTaken: String
    ) {
        db.writableDatabase.execSQL(
            """INSERT INTO market_learnings 
               (category, product_name, our_price_ugx, market_avg_ugx, best_performer, insight, action_taken, learned_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf(category, productName, ourPrice.toString(), marketAvg.toString(), bestPerformer, insight, actionTaken, System.currentTimeMillis().toString())
        )
    }

    /**
     * Get action statistics for a given type.
     */
    fun getActionStats(actionType: String, days: Int = 7): ActionStats {
        val cutoff = System.currentTimeMillis() - (days * 86400000L)
        val cursor = db.readableDatabase.rawQuery(
            "SELECT COUNT(*), SUM(success), AVG(screen_seconds_used) FROM action_log WHERE action_type = ? AND started_at > ?",
            arrayOf(actionType, cutoff.toString())
        )

        return if (cursor.moveToFirst()) {
            ActionStats(
                total = cursor.getInt(0),
                successes = cursor.getInt(1),
                avgScreenSeconds = cursor.getDouble(2)
            )
        } else {
            ActionStats(0, 0, 0.0)
        }.also { cursor.close() }
    }

    /**
     * Generate a learning summary for the owner.
     */
    fun generateSummary(): String {
        val sb = StringBuilder()
        sb.appendLine("📊 Amara's Learning Summary")
        sb.appendLine()

        // Top patterns
        val patterns = getTopPatterns(5)
        if (patterns.isNotEmpty()) {
            sb.appendLine("## Top Learned Patterns")
            for (p in patterns) {
                sb.appendLine("- ${p.key}: ${p.value} (${(p.confidence * 100).toInt()}% confidence)")
            }
            sb.appendLine()
        }

        // Skills needing improvement
        val weakSkills = getSkillsNeedingImprovement()
        if (weakSkills.isNotEmpty()) {
            sb.appendLine("## Skills Needing Improvement")
            for (s in weakSkills) {
                val failRate = (s.failures.toDouble() / s.totalAttempts * 100).toInt()
                sb.appendLine("- ${s.name}: ${failRate}% failure rate (${s.totalAttempts} attempts)")
            }
            sb.appendLine()
        }

        // Recent improvements
        val cursor = db.readableDatabase.rawQuery(
            "SELECT component, change_type, reason FROM improvement_log ORDER BY improved_at DESC LIMIT 5", null
        )
        sb.appendLine("## Recent Self-Improvements")
        while (cursor.moveToNext()) {
            sb.appendLine("- ${cursor.getString(0)}: ${cursor.getString(1)} — ${cursor.getString(2)}")
        }
        cursor.close()

        return sb.toString()
    }
}

data class LearnedPattern(
    val type: String,
    val key: String,
    val value: String,
    val confidence: Double,
    val evidenceCount: Int
)

data class SkillPerformance(
    val name: String,
    val totalAttempts: Int,
    val successes: Int,
    val failures: Int,
    val avgScreenSeconds: Double
)

data class ActionStats(
    val total: Int,
    val successes: Int,
    val avgScreenSeconds: Double
)

data class RoutineSuggestion(
    val id: String,
    val actionType: String,
    val label: String,
    val rule: String,
    val evidenceCount: Int,
    val confidence: Double,
    val averageScreenSeconds: Double,
)
