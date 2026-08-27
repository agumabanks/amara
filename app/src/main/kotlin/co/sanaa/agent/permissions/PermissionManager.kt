package co.sanaa.agent.permissions

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import co.sanaa.agent.services.AccessibilityAgentService

class PermissionManager(private val activity: Activity) {
    fun statusMap(): Map<String, Boolean> = mapOf(
        "accessibility" to isAccessibilityEnabled(),
        "battery" to isBatteryExempt(),
        "overlay" to Settings.canDrawOverlays(activity),
        "notificationAccess" to NotificationManagerCompat.getEnabledListenerPackages(activity).contains(activity.packageName),
        "notifications" to (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    )

    fun openAccessibilitySettings() = open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun requestBatteryExemption() {
        if (!isBatteryExempt()) {
            open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}")))
        } else open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    fun openOverlaySettings() = open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}")))
    fun openNotificationAccess() = open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 501)
    }

    fun requestContactsPermission(): Boolean {
        val granted = ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        if (!granted) ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.READ_CONTACTS), 503)
        return granted
    }

    fun isContactsGranted(): Boolean =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Runtime request for the media-reader permission used by approved attachments. */
    fun requestStoragePermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        val granted = ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
        if (!granted) ActivityCompat.requestPermissions(activity, arrayOf(permission), 502)
        return granted
    }

    fun isStorageGranted(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun open(intent: Intent) = activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    private fun isBatteryExempt() = (activity.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(activity.packageName)

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(activity, AccessibilityAgentService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(activity.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
