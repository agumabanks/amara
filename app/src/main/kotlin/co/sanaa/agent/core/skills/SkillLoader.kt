package co.sanaa.agent.core.skills

import android.content.Context
import java.io.File

/**
 * SkillLoader — Loads on-demand procedural knowledge.
 * Skills are loaded ONLY when needed (progressive disclosure).
 * Saves tokens by not loading everything at once.
 * 
 * Location: /data/data/co.sanaa.agent/files/skills/
 */
class SkillLoader(private val context: Context) {

    private val skillsDir = File(context.filesDir, "skills").apply { mkdirs() }

    /**
     * Load a skill by name. Returns null if not found.
     */
    fun load(name: String): String? {
        val file = File(skillsDir, "$name.md")
        return if (file.exists()) file.readText().trim() else null
    }

    /**
     * Check if a skill exists.
     */
    fun has(name: String): Boolean {
        return File(skillsDir, "$name.md").exists()
    }

    /**
     * Save/update a skill.
     */
    fun save(name: String, content: String) {
        File(skillsDir, "$name.md").writeText(content.trim())
    }

    /** Install a versioned built-in procedure without overwriting a skill Amara has
     * already refined on-device. */
    fun installBundledIfMissing(name: String, assetPath: String): Boolean {
        if (has(name)) return false
        val content = context.assets.open(assetPath).bufferedReader().use { it.readText() }
        save(name, content)
        return true
    }

    /**
     * Delete a skill.
     */
    fun delete(name: String) {
        File(skillsDir, "$name.md").delete()
    }

    /**
     * List all available skills.
     */
    fun list(): List<String> {
        return skillsDir.listFiles()
            ?.filter { it.extension == "md" }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }

    /**
     * Load multiple skills and join them.
     */
    fun loadAll(names: List<String>): String {
        return names.mapNotNull { load(it) }.joinToString("\n\n---\n\n")
    }

    /**
     * Get file path for ADB editing.
     */
    fun getDirPath(): String = skillsDir.absolutePath
}

/**
 * Self-improving skill that tracks success/failure and refines itself.
 */
class SelfImprovingSkill(
    private val name: String,
    private val context: Context
) {
    private val skillLoader = SkillLoader(context)
    private val analytics = SkillAnalytics(name, context)

    /**
     * Execute the skill instructions.
     */
    fun execute(): String? {
        return skillLoader.load(name)
    }

    /**
     * Record a successful execution.
     */
    fun recordSuccess() {
        analytics.recordAttempt(true)
    }

    /**
     * Record a failed execution.
     */
    fun recordFailure() {
        analytics.recordAttempt(false)
    }

    /**
     * Check if this skill is failing too much (>30% failure rate).
     */
    fun needsImprovement(): Boolean {
        return analytics.failureRate() > 0.3 && analytics.totalAttempts() >= 5
    }

    /**
     * Get improvement suggestion based on failure history.
     */
    fun getImprovementPrompt(): String? {
        if (!needsImprovement()) return null
        val currentSkill = skillLoader.load(name) ?: return null
        val recentFailures = analytics.getRecentFailures(5)
        return """
            |This skill has failed ${analytics.failureRate() * 100}% of the time recently.
            |Current instructions:
            |$currentSkill
            |
            |Recent failures:
            |${recentFailures.joinToString("\n") { "- $it" }}
            |
            |Suggest improvements to make this skill more reliable.
        """.trimMargin()
    }
}

/**
 * Analytics for skill performance.
 */
class SkillAnalytics(
    private val name: String,
    private val context: Context
) {
    private val prefs = context.getSharedPreferences("skill_analytics_$name", Context.MODE_PRIVATE)

    fun recordAttempt(success: Boolean) {
        val total = prefs.getInt("total_attempts", 0) + 1
        val successes = prefs.getInt("successes", 0) + if (success) 1 else 0
        prefs.edit()
            .putInt("total_attempts", total)
            .putInt("successes", successes)
            .apply()
    }

    fun totalAttempts(): Int = prefs.getInt("total_attempts", 0)
    fun successes(): Int = prefs.getInt("successes", 0)
    fun failures(): Int = totalAttempts() - successes()
    fun failureRate(): Double = if (totalAttempts() == 0) 0.0 else failures().toDouble() / totalAttempts()

    fun getRecentFailures(count: Int): List<String> {
        return prefs.getStringSet("recent_failures", emptySet())
            ?.toList()
            ?.takeLast(count) ?: emptyList()
    }

    fun addRecentFailure(description: String) {
        val current = prefs.getStringSet("recent_failures", emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(description)
        if (current.size > 20) {
            // Keep only recent 20
            val toRemove = current.size - 20
            repeat(toRemove) { current.remove(current.first()) }
        }
        prefs.edit().putStringSet("recent_failures", current).apply()
    }
}
