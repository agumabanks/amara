package co.sanaa.agent.core

import android.content.Context
import org.json.JSONObject

/** Private durable routing context; a manager answer cannot choose a different customer. */
class ManagerConsultations(context: Context) {
    private val prefs = context.getSharedPreferences("manager_consultations", Context.MODE_PRIVATE)
    @Synchronized fun open(source: String, scope: String, contactId: String, target: String, question: String): String {
        require(source.isNotBlank() && scope.isNotBlank() && contactId.isNotBlank())
        val ref = "ASK-" + ContentHashing.hash("$scope:$source").take(16).uppercase()
        if (!prefs.contains(ref)) check(prefs.edit().putString(ref, JSONObject()
            .put("scope", scope).put("contactId", contactId).put("target", target)
            .put("question", question.take(2000)).put("state", "WAITING")
            .put("createdAt", System.currentTimeMillis()).toString()).commit())
        return ref
    }
    fun reference(message: String): String? = Regex("\\bASK-[A-Fa-f0-9]{16}\\b", RegexOption.IGNORE_CASE).find(message)?.value?.uppercase()
    fun get(ref: String): JSONObject? = prefs.getString(ref, null)?.let(::JSONObject)
    fun waitingReferences(scope: String): List<String> = prefs.all.entries.mapNotNull { (ref, value) ->
        val row = (value as? String)?.let { runCatching { JSONObject(it) }.getOrNull() }
        ref.takeIf { scope.isNotBlank() && row?.optString("state") == "WAITING" && row.optString("scope") == scope }
    }.sorted()
    @Synchronized fun claim(ref: String, inboundKey: String, answer: String): JSONObject? {
        val row = get(ref) ?: return null
        if (row.optString("state") != "WAITING") return null
        row.put("state", "CLAIMED").put("answerWorkKey", inboundKey).put("answer", answer)
        check(prefs.edit().putString(ref, row.toString()).commit())
        return row
    }
    @Synchronized fun finish(ref: String, state: String) {
        val row = get(ref) ?: return
        row.put("state", state)
        check(prefs.edit().putString(ref, row.toString()).commit())
    }
}
