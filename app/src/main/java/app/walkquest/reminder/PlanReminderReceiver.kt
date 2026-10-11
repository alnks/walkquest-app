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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Напоминание о запланированной прогулке (история 3.6, FR-17): тихое
 * уведомление «Пора собираться» за lead минут до времени плана. Одноразовый
 * будильник ставится при создании плана (PlanPlanner); приёмник шлёт
 * уведомление и помечает план напоминённым (однократность).
 */
class PlanReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val planId = intent.getLongExtra(EXTRA_PLAN_ID, -1L)
        if (planId < 0) return
        val container = (context.applicationContext as WalkQuestApplication).container
        val plan = container.plannedWalksStore.plans().firstOrNull { it.id == planId } ?: return
        if (plan.reminded) return
        container.plannedWalksStore.markReminded(planId)
        val time = SimpleDateFormat("HH:mm", Locale("ru", "RU")).format(Date(plan.plannedAtEpochMillis))
        showNotification(context, planId, "Пора собираться: прогулка в $time")
    }

    private fun showNotification(context: Context, planId: Long, text: String) {
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
        manager.notify(NOTIFICATION_ID_BASE + planId.toInt().mod(1000), notification)
    }

    companion object {
        private const val CHANNEL_ID = "walk-reminders"
        private const val NOTIFICATION_ID_BASE = 2000
        private const val ACTION_PLAN_REMINDER = "app.walkquest.reminder.PLAN_REMINDER"
        const val EXTRA_PLAN_ID = "planId"

        /**
         * Одноразовый будильник за [leadMinutes] до времени плана. Неточный
         * (set, не exact): опоздание на минуты для «пора собираться» допустимо,
         * exact-алармы требуют отдельного разрешения на 31+.
         */
        fun schedule(context: Context, planId: Long, planAtEpochMillis: Long, leadMinutes: Int) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val triggerAt = planAtEpochMillis - leadMinutes * 60_000L
            if (triggerAt <= System.currentTimeMillis()) return // уже поздно
            val intent = PendingIntent.getBroadcast(
                context,
                planId.toInt(),
                Intent(context, PlanReminderReceiver::class.java)
                    .setAction(ACTION_PLAN_REMINDER)
                    .putExtra(EXTRA_PLAN_ID, planId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, intent)
        }
    }
}
