package co.sanaa.agent.core

import android.content.Context

/**
 * AmaraSettings — Unified owner-controllable settings.
 * All configurable parameters in one place.
 * 
 * The Flutter UI reads/writes these via MethodChannel.
 * Settings are persisted in SecureConfig.
 */
class AmaraSettings(context: Context) {

    private val config = SecureConfig(context)

    // --- TikTok Settings ---
    
    enum class TikTokInterval(val minutes: Long, val label: String) {
        MIN_10(10, "10 min"),
        MIN_30(30, "30 min"),
        HOUR_1(60, "1 hour"),
        HOUR_2(120, "2 hours"),
        HOUR_4(240, "4 hours"),
        HOUR_8(480, "8 hours");

        companion object {
            fun fromMinutes(m: Long) = entries.firstOrNull { it.minutes == m } ?: HOUR_4
        }
    }

    var tikTokEnabled: Boolean
        get() = config.tikTokTestMode
        set(value) { config.tikTokTestMode = value }

    var tikTokInterval: TikTokInterval
        get() = TikTokInterval.fromMinutes(config.tikTokPostIntervalMinutes)
        set(value) { config.tikTokPostIntervalMinutes = value.minutes }

    var tikTokDailyCap: Int
        get() = config.tikTokDailyCap
        set(value) { config.tikTokDailyCap = value.coerceIn(1, 144) }

    var tikTokAlwaysOn: Boolean
        get() = config.tikTokAlwaysOn
        set(value) { config.tikTokAlwaysOn = value }

    var tikTokSocialEnabled: Boolean
        get() = config.tikTokSocialEnabled
        set(value) { config.tikTokSocialEnabled = value }

    var tikTokCommentsEnabled: Boolean
        get() = config.tikTokCommentsEnabled
        set(value) { config.tikTokCommentsEnabled = value }

    // --- WhatsApp Settings ---

    var whatsAppEnabled: Boolean
        get() = config.whatsAppAutomationEnabled
        set(value) { config.whatsAppAutomationEnabled = value }

    var whatsAppFollowUpDays: Int
        get() = config.whatsAppFollowUpDays
        set(value) { config.whatsAppFollowUpDays = value.coerceIn(1, 30) }

    var whatsAppInboundEnabled: Boolean
        get() = config.whatsAppInboundEnabled
        set(value) { config.whatsAppInboundEnabled = value }

    var whatsAppFollowUpsEnabled: Boolean
        get() = config.whatsAppFollowUpsEnabled
        set(value) { config.whatsAppFollowUpsEnabled = value }

    var whatsAppGroupsEnabled: Boolean
        get() = config.whatsAppGroupsEnabled
        set(value) { config.whatsAppGroupsEnabled = value }

    var whatsAppAlwaysOn: Boolean
        get() = config.whatsAppAlwaysOn
        set(value) { config.whatsAppAlwaysOn = value }

    var memoryBackupEnabled: Boolean
        get() = config.memoryBackupEnabled
        set(value) { config.memoryBackupEnabled = value }

    var memoryAutoRestoreEnabled: Boolean
        get() = config.memoryAutoRestoreEnabled
        set(value) { config.memoryAutoRestoreEnabled = value }

    // --- Soko Settings ---

    var sokoAutoSync: Boolean
        get() = config.sokoAutoSync
        set(value) { config.sokoAutoSync = value }

    // --- Jiji/Market Settings ---

    var jijiScrapingEnabled: Boolean
        get() = config.jijiScrapingEnabled
        set(value) { config.jijiScrapingEnabled = value }

    var jijiScrapeIntervalHours: Int
        get() = config.jijiScrapeIntervalHours
        set(value) { config.jijiScrapeIntervalHours = value.coerceIn(1, 24) }

    var jumiaIntelligenceEnabled: Boolean
        get() = config.jumiaIntelligenceEnabled
        set(value) { config.jumiaIntelligenceEnabled = value }

    /** Explicit privacy consent required before Jumia screenshots leave the device. */
    var visionConsent: Boolean
        get() = config.visionConsent
        set(value) { config.visionConsent = value }

    // --- Broadcast Settings ---

    var morningBroadcastEnabled: Boolean
        get() = config.morningBroadcastEnabled
        set(value) { config.morningBroadcastEnabled = value }

    var broadcastTime: String
        get() = config.broadcastTime
        set(value) { config.broadcastTime = value }

    // --- General Settings ---

    var maxScreenMinutesPerDay: Int
        get() = config.maxAgentScreenMinutesPerDay
        set(value) { config.maxAgentScreenMinutesPerDay = value.coerceIn(10, 1440) }

    var quietHoursStart: String
        get() = config.quietHoursStart
        set(value) { config.quietHoursStart = value }

    var quietHoursEnd: String
        get() = config.quietHoursEnd
        set(value) { config.quietHoursEnd = value }

    /**
     * Get all settings as a map for the Flutter UI.
     */
    fun getAll(): Map<String, Any> = mapOf(
        "tikTokEnabled" to tikTokEnabled,
        "tikTokIntervalMinutes" to tikTokInterval.minutes,
        "tikTokIntervalLabel" to tikTokInterval.label,
        "tikTokDailyCap" to tikTokDailyCap,
        "tikTokAlwaysOn" to tikTokAlwaysOn,
        "tikTokCommentsEnabled" to tikTokCommentsEnabled,
        "tikTokSocialEnabled" to tikTokSocialEnabled,
        "whatsAppEnabled" to whatsAppEnabled,
        "whatsAppInboundEnabled" to whatsAppInboundEnabled,
        "whatsAppFollowUpsEnabled" to whatsAppFollowUpsEnabled,
        "whatsAppGroupsEnabled" to whatsAppGroupsEnabled,
        "whatsAppAlwaysOn" to whatsAppAlwaysOn,
        "whatsAppFollowUpDays" to whatsAppFollowUpDays,
        "memoryBackupEnabled" to memoryBackupEnabled,
        "memoryAutoRestoreEnabled" to memoryAutoRestoreEnabled,
        "lastMemoryBackupAt" to config.lastMemoryBackupAt,
        "sokoAutoSync" to sokoAutoSync,
        "jijiScrapingEnabled" to jijiScrapingEnabled,
        "jijiScrapeIntervalHours" to jijiScrapeIntervalHours,
        "jumiaIntelligenceEnabled" to jumiaIntelligenceEnabled,
        "visionConsent" to visionConsent,
        "groqVisionModel" to config.groqVisionModel,
        "morningBroadcastEnabled" to morningBroadcastEnabled,
        "broadcastTime" to broadcastTime,
        "maxScreenMinutesPerDay" to maxScreenMinutesPerDay,
        "quietHoursStart" to quietHoursStart,
        "quietHoursEnd" to quietHoursEnd,
        "maxRetryCooldownMinutes" to config.maxRetryCooldownMinutes,
    )

    /**
     * Update a setting by key.
     */
    fun set(key: String, value: Any): Boolean {
        return try {
            when (key) {
                "maxRetryCooldownMinutes" -> config.maxRetryCooldownMinutes = (value as Number).toInt()
                "tikTokEnabled" -> tikTokEnabled = value as Boolean
                "tikTokIntervalMinutes" -> tikTokInterval = TikTokInterval.fromMinutes((value as Number).toLong())
                "tikTokDailyCap" -> tikTokDailyCap = (value as Number).toInt()
                "tikTokAlwaysOn" -> tikTokAlwaysOn = value as Boolean
                "tikTokCommentsEnabled" -> tikTokCommentsEnabled = value as Boolean
                "tikTokSocialEnabled" -> tikTokSocialEnabled = value as Boolean
                "whatsAppEnabled" -> whatsAppEnabled = value as Boolean
                "whatsAppInboundEnabled" -> whatsAppInboundEnabled = value as Boolean
                "whatsAppFollowUpsEnabled" -> whatsAppFollowUpsEnabled = value as Boolean
                "whatsAppGroupsEnabled" -> whatsAppGroupsEnabled = value as Boolean
                "whatsAppAlwaysOn" -> whatsAppAlwaysOn = value as Boolean
                "whatsAppFollowUpDays" -> whatsAppFollowUpDays = (value as Number).toInt()
                "memoryBackupEnabled" -> memoryBackupEnabled = value as Boolean
                "memoryAutoRestoreEnabled" -> memoryAutoRestoreEnabled = value as Boolean
                "sokoAutoSync" -> sokoAutoSync = value as Boolean
                "jijiScrapingEnabled" -> jijiScrapingEnabled = value as Boolean
                "jijiScrapeIntervalHours" -> jijiScrapeIntervalHours = (value as Number).toInt()
                "jumiaIntelligenceEnabled" -> jumiaIntelligenceEnabled = value as Boolean
                "visionConsent" -> visionConsent = value as Boolean
                "groqVisionModel" -> {
                    val model = (value as String).trim()
                    require(model.isEmpty() || model.matches(Regex("[A-Za-z0-9._/-]{1,160}")))
                    config.groqVisionModel = model
                }
                "morningBroadcastEnabled" -> morningBroadcastEnabled = value as Boolean
                "broadcastTime" -> broadcastTime = value as String
                "maxScreenMinutesPerDay" -> maxScreenMinutesPerDay = (value as Number).toInt()
                "quietHoursStart" -> quietHoursStart = value as String
                "quietHoursEnd" -> quietHoursEnd = value as String
                else -> return false
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
