package co.sanaa.agent.core.context

import android.content.Context
import java.io.File

/**
 * ContextLoader — Loads per-task instruction files.
 * When Amara does TikTok work, she loads contexts/tiktok.md.
 * When she does WhatsApp, she loads contexts/whatsapp.md.
 * Saves tokens by only loading what's needed.
 * 
 * Location: /data/data/co.sanaa.agent/files/contexts/
 */
class ContextLoader(private val context: Context) {

    private val contextsDir = File(context.filesDir, "contexts").apply { mkdirs() }

    /**
     * Load a context file by name. Returns empty string if not found.
     */
    fun load(name: String): String {
        val file = File(contextsDir, "$name.md")
        return if (file.exists()) file.readText().trim() else ""
    }

    /**
     * Check if a context file exists.
     */
    fun has(name: String): Boolean {
        return File(contextsDir, "$name.md").exists()
    }

    /**
     * Save/update a context file.
     */
    fun save(name: String, content: String) {
        File(contextsDir, "$name.md").writeText(content.trim())
    }

    /**
     * Delete a context file.
     */
    fun delete(name: String) {
        File(contextsDir, "$name.md").delete()
    }

    /**
     * List all available context names.
     */
    fun list(): List<String> {
        return contextsDir.listFiles()
            ?.filter { it.extension == "md" }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }

    /**
     * Load multiple contexts and join them.
     */
    fun loadAll(names: List<String>): String {
        return names.map { load(it) }.filter { it.isNotBlank() }.joinToString("\n\n")
    }

    /**
     * Get file path for ADB editing.
     */
    fun getDirPath(): String = contextsDir.absolutePath
}
