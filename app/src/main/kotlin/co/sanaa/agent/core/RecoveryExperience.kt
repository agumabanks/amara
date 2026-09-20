package co.sanaa.agent.core

import android.content.Context
import org.json.JSONObject

/** Durable observed recovery outcomes. Only fixed, reviewed strategies may be reused. */
class RecoveryExperience(context: Context) {
    private val prefs = context.getSharedPreferences("verified_recovery_experience", Context.MODE_PRIVATE)
    fun record(scope: String, strategy: String, verified: Boolean) {
        val key = ContentHashing.hash(scope)
        synchronized(RecoveryExperience::class.java) {
            val row = JSONObject(prefs.getString(key, "{}")!!)
            row.put("strategy", strategy).put("last_verified", verified)
                .put("observed_at", System.currentTimeMillis())
                .put(if (verified) "successes" else "failures", row.optInt(if (verified) "successes" else "failures") + 1)
            check(prefs.edit().putString(key, row.toString()).commit())
        }
    }
    fun provenStrategy(scope: String): String? {
        val row = JSONObject(prefs.getString(ContentHashing.hash(scope), "{}")!!)
        return row.optString("strategy").takeIf {
            row.optBoolean("last_verified") && row.optInt("successes") > 0 &&
                System.currentTimeMillis() - row.optLong("observed_at") < 7 * 86_400_000L
        }
    }
}
