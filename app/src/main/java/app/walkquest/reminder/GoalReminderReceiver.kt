package app.walkquest.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.walkquest.R
import app.walkquest.WalkQuestApplication
import app.walkquest.domain.GoalReminderPolicy
import java.util.Calendar

/**
 * Вечерняя проверка умного напоминания (история 3.5, FR-14): приёмник будильника
 * считает отставание от цели (domain GoalReminderPolicy) и шлёт тихое уведомление
 * ТОЛЬКО если отставание есть. Отдельная настройка goalRemindersEnabled независима
 * от уведомлений трекинга (FR-14). Канал — walk-reminders (FR-9), как у 2.5/2.6.
 */
class GoalReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as WalkQuestApplication).container
        val settings = container.settingsRepository
        if (!settings.goalRemindersEnabled()) return
        val goal = settings.walkGoal() ?: return
        val text = container.goalReminderPolicy.evaluate(goal, System.currentTimeMillis()) ?: return
        showNotification(context, text)
    }

    private fun showNotification(context: Context, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Прогулки", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("WalkQuest")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "walk-reminders"
        private const val NOTIFICATION_ID = 1004
        private const val ACTION_GOAL_REMINDER = "app.walkquest.reminder.GOAL_REMINDER"

        /**
         * Ежедневный неточный будильник на [hour]:00 (сдвиг до Android подберёт
         * ближайшее окно батарейной оптимизации — напоминание не точный аларм).
         * Вызывается на старте приложения; перестановка идемпотентна.
         */
        fun schedule(context: Context, hour: Int) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val intent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, GoalReminderReceiver::class.java).setAction(ACTION_GOAL_REMINDER),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val first = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }
            alarmManager.setInexactRepeating(
                AlarmManager.RTC,
                first.timeInMillis,
                AlarmManager.INTERVAL_DAY,
                intent,
            )
        }
    }
}
