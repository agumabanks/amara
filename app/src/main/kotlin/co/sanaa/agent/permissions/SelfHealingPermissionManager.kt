package co.sanaa.agent.permissions

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager

data class PermissionDiagnosis(
    val key: String,
    val label: String,
    val status: PermissionStatus,
    val fixIntent: Intent?,
    val description: String,
)

enum class PermissionStatus {
    READY,
    NEEDS_ACTION,
    BLOCKED,
}

class SelfHealingPermissionManager(private val context: Context) {

    fun diagnoseAll(): List<PermissionDiagnosis> {
        return listOf(
            diagnoseAccessibility(),
            diagnoseOverlay(),
            diagnoseBattery(),
            diagnoseNotificationAccess(),
            diagnoseNotificationPermission(),
            diagnosePhoneState(),
            diagnoseContacts(),
            diagnoseStorage(),
        )
    }

    fun diagnoseAccessibility(): PermissionDiagnosis {
        val enabled = isAccessibilityEnabledSetting()
        val bound = co.sanaa.agent.services.AccessibilityAgentService.isBound()
        val wasAlive = wasAccessibilityServiceAlive()
        val status = when {
            enabled && bound -> PermissionStatus.READY
            // The service WAS bound in this app's lifetime but is no longer enabled:
            // this is the post-ColorOS-force-stop / post-APK-replace signature, where
            // the OS reset the enabled flag. Treat as BLOCKED (crashed-services
            // reconciliation), not merely "needs action" — the owner has to re-enable
            // and we owe them a non-dismissable card.
            !enabled && wasAlive -> PermissionStatus.BLOCKED
            else -> PermissionStatus.NEEDS_ACTION
        }
        return PermissionDiagnosis(
            key = "accessibility",
            label = "Accessibility",
            status = status,
            fixIntent = accessibilityFixIntent(enabled),
            description = when {
                enabled && bound -> "Active — Amara can read and operate screens"
                !enabled && wasAlive -> "Disabled by Android — open settings to re-enable Sanaa Agent's phone control"
                else -> "Required — allows Amara to see and operate the phone"
            },
        )
    }

    /**
     * Picks the deepest, most-accurate fix intent we can resolve. OPPO/ColorOS resets
     * the enabled flag after force-stop and after APK replacement unless autostart
     * is allowed, so we try the OPPO Battery-usage (autostart) component first, fall
     * back to the standard Accessibility screen, and finally to the app details page.
     */
    fun accessibilityFixIntent(preferOppoAutostart: Boolean = true): Intent {
        if (preferOppoAutostart) {
            val oppo = Intent().apply {
                component = ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                )
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intentResolves(oppo)) return oppo
            val oppoAlt = Intent().apply {
                component = ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity",
                )
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intentResolves(oppoAlt)) return oppoAlt
            val oppoEn = Intent().apply {
                component = ComponentName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity",
                )
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intentResolves(oppoEn)) return oppoEn
        }
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    private fun intentResolves(intent: Intent): Boolean = runCatching {
        context.packageManager.resolveActivity(
            intent,
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        ) != null
    }.getOrDefault(false)

    /**
     * Records that the accessibility service has just bound successfully. The watchdog
     * uses the previous value to detect the "enabled previously, disabled now" pattern
     * that ColorOS produces after force-stop / APK replacement. Stored in a private
     * preference file so it survives process death without mixing with the durable
     * runtime state used by other agents.
     */
    fun markAccessibilityServiceAlive() {
        prefs().edit().putLong(KEY_LAST_ALIVE_AT, System.currentTimeMillis()).apply()
    }

    fun wasAccessibilityServiceAlive(): Boolean = prefs().contains(KEY_LAST_ALIVE_AT)

    fun clearAccessibilityServiceAlive() {
        prefs().edit().remove(KEY_LAST_ALIVE_AT).apply()
    }

    private fun isAccessibilityEnabledSetting(): Boolean =
        getEnabledAccessibilityServices().contains(context.packageName + "/" + AccessibilityServiceClass)

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun diagnoseOverlay(): PermissionDiagnosis {
        val granted = Settings.canDrawOverlays(context)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else null
        return PermissionDiagnosis(
            key = "overlay",
            label = "Screen overlay",
            status = if (granted) PermissionStatus.READY else PermissionStatus.NEEDS_ACTION,
            fixIntent = intent,
            description = if (granted) "Active — Amara can show the status widget" else "Required — shows Amara's live status over any app",
        )
    }

    fun diagnoseBattery(): PermissionDiagnosis {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoring = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else true
        return PermissionDiagnosis(
            key = "battery",
            label = "Battery optimization",
            status = if (ignoring) PermissionStatus.READY else PermissionStatus.NEEDS_ACTION,
            fixIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            description = if (ignoring) "Active — scheduled work runs reliably" else "Required — keeps background work alive",
        )
    }

    fun diagnoseNotificationAccess(): PermissionDiagnosis {
        // The setting stores ComponentName flattenings ("pkg/cls"), one per listener;
        // the package is granted when any entry's component prefix IS this package.
        // Exact set membership against a bare package name would report a correctly
        // granted listener as needing action (device-run defect, 2026-08-24).
        val enabled = getEnabledNotificationListeners().any { component ->
            component.substringBefore('/') == context.packageName
        }
        return PermissionDiagnosis(
            key = "notification_access",
            label = "Notification access",
            status = if (enabled) PermissionStatus.READY else PermissionStatus.NEEDS_ACTION,
            fixIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(":settings:fragment_args_key", context.packageName + "/" + NotificationListenerClass)
            },
            description = if (enabled) "Active — Amara notices customer messages" else "Required — detects incoming WhatsApp messages",
        )
    }

    fun diagnoseNotificationPermission(): PermissionDiagnosis {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else null
        return PermissionDiagnosis(
            key = "notification_permission",
            label = "Notifications",
            status = if (granted) PermissionStatus.READY else PermissionStatus.NEEDS_ACTION,
            fixIntent = intent,
            description = if (granted) "Active — Amara reports completed work" else "Required — allows status and work notifications",
        )
    }

    fun diagnosePhoneState(): PermissionDiagnosis {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        return PermissionDiagnosis(
            key = "phone_state",
            label = "Phone state",
            status = if (granted) PermissionStatus.READY else PermissionStatus.NEEDS_ACTION,
            fixIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            description = if (granted) "Active — detects active calls" else "Required — avoids working during calls",
        )
    }

    fun diagnoseStorage(): PermissionDiagnosis {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        return PermissionDiagnosis(
            key = "storage",
            label = "Storage",
            status = if (granted) PermissionStatus.READY else PermissionStatus.NEEDS_ACTION,
            fixIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            description = if (granted) "Active — can share attachments" else "Required — attaches images to WhatsApp",
        )
    }

    fun diagnoseContacts(): PermissionDiagnosis {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        return PermissionDiagnosis(
            key = "contacts",
            label = "Contacts",
            status = if (granted) PermissionStatus.READY else PermissionStatus.NEEDS_ACTION,
            fixIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            description = if (granted) "Active — can discover the address book" else "Optional — enables unified phone and WhatsApp contact discovery",
        )
    }

    fun needsHealing(): Boolean = diagnoseAll().any { it.status != PermissionStatus.READY }

    fun openFix(diagnosis: PermissionDiagnosis) {
        diagnosis.fixIntent?.let {
            try {
                context.startActivity(it)
            } catch (_: Exception) {
                try {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                } catch (_: Exception) {}
            }
        }
    }

    fun fixAll() {
        diagnoseAll().filter { it.status != PermissionStatus.READY }.firstOrNull()?.let { openFix(it) }
    }

    private fun getEnabledAccessibilityServices(): Set<String> {
        val flat = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return emptySet()
        return buildSet {
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(flat)
            for (item in splitter) add(item)
        }
    }

    private fun getEnabledNotificationListeners(): Set<String> {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return emptySet()
        return buildSet {
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(flat)
            for (item in splitter) add(item)
        }
    }

    companion object {
        const val AccessibilityServiceClass = "co.sanaa.agent.services.AccessibilityAgentService"
        const val NotificationListenerClass = "co.sanaa.agent.services.AgentNotificationListenerService"
        private const val PREFS = "sanaa_a11y_recovery"
        private const val KEY_LAST_ALIVE_AT = "accessibility_last_alive_at"
    }
}
