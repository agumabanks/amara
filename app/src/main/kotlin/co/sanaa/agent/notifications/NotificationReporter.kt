package co.sanaa.agent.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import co.sanaa.agent.MainActivity
import co.sanaa.agent.R

class NotificationReporter(private val context: Context, private val vault: co.sanaa.agent.core.CredentialVault? = null) {
    enum class Priority(val channel: String, val importance: Int) {
        INFO("agent_reports", NotificationManager.IMPORTANCE_DEFAULT),
        ACTION_NEEDED("agent_action", NotificationManager.IMPORTANCE_HIGH),
        URGENT("agent_urgent", NotificationManager.IMPORTANCE_HIGH),
    }

    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        Priority.entries.forEach { manager.createNotificationChannel(NotificationChannel(it.channel, it.name.replace('_', ' '), it.importance)) }
    }

    /**
     * A notification renders on the lock screen and in the shade, so any literal
     * credential inside a model reply, an error string, or a screen observation
     * would be readable by anyone holding the device. Redact every configured
     * secret before the text leaves this process. Shape-based redaction
     * ([co.sanaa.agent.core.Redactor]) is deliberately NOT used here: it would
     * mangle legitimate owner-facing content such as product titles and prices.
     * Literal vault secrets are precise and are the actual risk here.
     */
    private fun safe(text: String): String {
        val base = co.sanaa.agent.core.Redactor.redactForExport(text)
        return vault?.redactKnownSecrets(base) ?: base
    }

    fun report(title: String, message: String, priority: Priority = Priority.INFO,
               notificationId: Int = (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
               ongoing: Boolean = false) {
        val safeTitle = safe(title)
        val safeMessage = safe(message)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, priority.channel)
            .setSmallIcon(R.drawable.ic_agent).setContentTitle(safeTitle).setContentText(safeMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(safeMessage)).setContentIntent(open)
            .setAutoCancel(!ongoing).setOngoing(ongoing)
            .setPriority(if (priority == Priority.INFO) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH).build()
        context.getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }
}
