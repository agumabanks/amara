package co.sanaa.agent.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import co.sanaa.agent.MainActivity
import co.sanaa.agent.R

class NotificationReporter(private val context: Context) {
    enum class Priority(val channel: String, val importance: Int) {
        INFO("agent_reports", NotificationManager.IMPORTANCE_DEFAULT),
        ACTION_NEEDED("agent_action", NotificationManager.IMPORTANCE_HIGH),
        URGENT("agent_urgent", NotificationManager.IMPORTANCE_HIGH),
    }

    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        Priority.entries.forEach { manager.createNotificationChannel(NotificationChannel(it.channel, it.name.replace('_', ' '), it.importance)) }
    }

    fun report(title: String, message: String, priority: Priority = Priority.INFO) {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, priority.channel)
            .setSmallIcon(R.drawable.ic_agent).setContentTitle(title).setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message)).setContentIntent(open).setAutoCancel(true)
            .setPriority(if (priority == Priority.INFO) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH).build()
        context.getSystemService(NotificationManager::class.java).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }
}
