package co.sanaa.agent.core

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

class SecureConfig(context: Context, useEncryptedPrefs: Boolean = true) {
    private val masterKey = if (!useEncryptedPrefs) null else MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val prefs = if (!useEncryptedPrefs) {
        // Test/JVM mode only: production always constructs with encryption enabled.
        context.getSharedPreferences("sanaa_agent_secrets_test", Context.MODE_PRIVATE)
    } else {
        androidx.security.crypto.EncryptedSharedPreferences.create(
            context, "sanaa_agent_secrets", masterKey!!,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var groqApiKey: String by stringPreference("groq_api_key")
    var groqEndpoint: String by stringPreference("groq_endpoint", DEFAULT_ENDPOINT)
    var groqModel: String by stringPreference("groq_model", DEFAULT_MODEL)
    var groqVisionModel: String by stringPreference("groq_vision_model")
    var agentToken: String by stringPreference("agent_token")
    var deviceId: String by stringPreference("device_id")
    var sokoApiToken: String by stringPreference("soko_api_token")
    var sokoBaseUrl: String by stringPreference("soko_base_url", DEFAULT_SOKO_URL)
    var broadcastTime: String by stringPreference("broadcast_time", "07:00")
    var agentName: String by stringPreference("agent_name", "Amara")
    var businessName: String by stringPreference("business_name", "Sanaa Media")
    var ownerPhone: String by stringPreference("owner_phone")
    var whatsAppGroupsJson: String by stringPreference("whatsapp_groups", "[]")
    var monitoredWhatsAppJson: String by stringPreference("monitored_whatsapp", "[]")
    var contactPermissionsJson: String by stringPreference("contact_permissions", "[]")
    var backendUrl: String by stringPreference("backend_url", DEFAULT_BACKEND_URL)
    /** Stored encrypted on-device after the owner supplies it. */
    var sokoTerminalPin: String by stringPreference("soko_terminal_pin")
    var quietHoursStart: String by stringPreference("quiet_hours_start", "22:00")
    var quietHoursEnd: String by stringPreference("quiet_hours_end", "06:30")

    var proactiveReadOnlyAudits: Boolean
        get() = prefs.getBoolean("proactive_read_only_audits", false)
        set(value) { prefs.edit().putBoolean("proactive_read_only_audits", value).apply() }

    /**
     * Explicit owner opt-in for operational telemetry export (backend log()).
     * Default is OFF: operational telemetry stays on the device until enabled here.
     */
    var telemetryOptIn: Boolean
        get() = prefs.getBoolean("telemetry_opt_in", false)
        set(value) { prefs.edit().putBoolean("telemetry_opt_in", value).apply() }

    /**
     * Independent control for configuration sync (register/config/status with the
     * owner's own backend). Default ON because registration is required for operation,
     * but always disclosed in DATA_EGRESS_INVENTORY.md and owner-toggleable.
     */
    var configSyncEnabled: Boolean
        get() = prefs.getBoolean("config_sync_enabled", true)
        set(value) { prefs.edit().putBoolean("config_sync_enabled", value).apply() }

    /** Listing/ad data may only be uploaded for ad generation after explicit opt-in. */
    var artifactUploadOptIn: Boolean
        get() = prefs.getBoolean("artifact_upload_opt_in", false)
        set(value) { prefs.edit().putBoolean("artifact_upload_opt_in", value).apply() }

    /** Screenshots/images may only be sent to the vision provider after explicit consent. */
    var visionConsent: Boolean
        get() = prefs.getBoolean("vision_consent", false)
        set(value) { prefs.edit().putBoolean("vision_consent", value).apply() }

    /**
     * Explicit owner policy authorizing durable retention of unmonitored-contact
     * notification events. Default OFF: unmonitored contacts produce only a minimal
     * redacted notification count and nothing else about them is stored.
     */
    var retainUnmonitoredContactEvents: Boolean
        get() = prefs.getBoolean("retain_unmonitored_contact_events", false)
        set(value) { prefs.edit().putBoolean("retain_unmonitored_contact_events", value).apply() }

    /** Retention window for recorded business activity, in days (owner-adjustable). */
    var retentionDays: Int
        get() = prefs.getInt("retention_days", 90)
        set(value) { prefs.edit().putInt("retention_days", value.coerceIn(7, 730)).apply() }

    var orderThresholdUgx: Long
        get() = prefs.getLong("order_threshold_ugx", 500_000L)
        set(value) { prefs.edit().putLong("order_threshold_ugx", value).apply() }

    fun saveRemoteConfig(json: JSONObject) {
        json.nonBlank("groq_endpoint")?.let { groqEndpoint = it }
        json.nonBlank("groq_model")?.let { groqModel = it }
        json.nonBlank("groq_vision_model")?.let { groqVisionModel = it }
        json.nonBlank("soko_base_url")?.let { sokoBaseUrl = it }
        json.nonBlank("broadcast_time")?.let { broadcastTime = it }
        json.nonBlank("agent_name")?.let { agentName = it }
        json.nonBlank("business_name")?.let { businessName = it }
        json.nonBlank("owner_phone")?.let { ownerPhone = it }
        if (json.has("order_threshold_ugx")) orderThresholdUgx = json.optLong("order_threshold_ugx", 500_000L)
        json.optJSONArray("whatsapp_groups")?.let { whatsAppGroupsJson = it.toString() }
        json.optJSONArray("monitored_whatsapp")?.let { monitoredWhatsAppJson = it.toString() }
    }

    fun monitoredWhatsAppTargets(): Set<String> = runCatching {
        val array = org.json.JSONArray(monitoredWhatsAppJson)
        buildSet { for (index in 0 until array.length()) add(array.optString(index).trim()) }.filter(String::isNotBlank).toSet()
    }.getOrDefault(emptySet())

    private fun stringPreference(key: String, default: String = "") = object : kotlin.properties.ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = prefs.getString(key, default) ?: default
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: String) {
            prefs.edit().putString(key, value).apply()
        }
    }

    private fun JSONObject.nonBlank(key: String) = optString(key).takeIf { it.isNotBlank() }

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
        const val DEFAULT_MODEL = "openai/gpt-oss-120b"
        const val DEFAULT_SOKO_URL = "https://soko24.co/api"
        const val DEFAULT_BACKEND_URL = "https://cards.sanaa.ug/api/agent"
    }
}
