package co.sanaa.agent.core.memory

import android.content.Context
import java.io.File

/**
 * BoundedMemory — Curated memory with hard character limit.
 * When full, Amara must choose what to keep (like Hermes's MEMORY.md).
 * 
 * Location: /data/data/co.sanaa.agent/files/amara_notes.md
 */
class BoundedMemory(
    private val context: Context,
    private val maxChars: Int = 2200
) {

    private val memoryFile = File(context.filesDir, "amara_notes.md")

    companion object {
        const val ENTRY_DELIMITER = "\n§"
        val DEFAULT_NOTES = """
            |These are Amara's curated notes — things she has learned about her work, customers, and business.
            |She updates this herself when she learns something important.
        """.trimMargin()
    }

    /**
     * Load all entries as a list.
     */
    fun loadEntries(): List<String> {
        if (!memoryFile.exists()) {
            memoryFile.writeText(DEFAULT_NOTES)
        }
        val content = memoryFile.readText().trim()
        return content.split(ENTRY_DELIMITER).map { it.trim() }.filter { it.isNotBlank() }
    }

    /**
     * Load full memory as string (for prompt injection).
     */
    fun load(): String {
        if (!memoryFile.exists()) {
            memoryFile.writeText(DEFAULT_NOTES)
        }
        return memoryFile.readText().trim()
    }

    /**
     * Get current usage.
     */
    fun usage(): Pair<Int, Int> {
        val content = load()
        return Pair(content.length, maxChars)
    }

    /**
     * Add a new entry. Returns false if would exceed limit (must compact first).
     */
    fun add(entry: String): Boolean {
        val current = load()
        val newContent = "$current\n§${entry.trim()}"
        if (newContent.length > maxChars) return false
        memoryFile.writeText(newContent)
        return true
    }

    /**
     * Replace an entry at index.
     */
    fun replace(index: Int, newEntry: String): Boolean {
        val entries = loadEntries().toMutableList()
        if (index < 0 || index >= entries.size) return false
        entries[index] = newEntry.trim()
        val rebuilt = entries.joinToString("\n§")
        if (rebuilt.length > maxChars) return false
        memoryFile.writeText(rebuilt)
        return true
    }

    /**
     * Remove an entry by index.
     */
    fun remove(index: Int) {
        val entries = loadEntries().toMutableList()
        if (index < 0 || index >= entries.size) return
        entries.removeAt(index)
        memoryFile.writeText(entries.joinToString("\n§"))
    }

    /**
     * Compact by summarizing existing entries into fewer chars.
     * Returns the new content.
     */
    fun compact(summarizer: (String) -> String): String {
        val current = load()
        val summarized = summarizer(current)
        memoryFile.writeText(summarized)
        return summarized
    }

    /**
     * Check if memory is nearly full (>80%).
     */
    fun isNearlyFull(): Boolean {
        return load().length > (maxChars * 0.8)
    }

    /**
     * Get file path for ADB editing.
     */
    fun getFilePath(): String = memoryFile.absolutePath
}
