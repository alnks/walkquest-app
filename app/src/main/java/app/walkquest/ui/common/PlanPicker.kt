package app.walkquest.ui.common

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Диалог планирования прогулки (3.6, FR-17): сначала дата, затем время.
 * Общий для экрана статистики (сегмент «Статистика» вкладки Прогресс) и
 * карточки планировщика статичной панели главного экрана (DESIGN sheet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanDateTimePicker(
    onDismiss: () -> Unit,
    onPlan: (Long) -> Unit,
) {
    var stage by remember { mutableStateOf(PlanPickerStage.DATE) }
    var pickedDateMillis by remember { mutableLongStateOf(0L) }
    val dateState = rememberDatePickerState()
    val timeState = rememberTimePickerState()

    when (stage) {
        PlanPickerStage.DATE -> DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    val picked = dateState.selectedDateMillis ?: return@TextButton
                    pickedDateMillis = picked
                    stage = PlanPickerStage.TIME
                }) { Text(text = "Дальше") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(text = "Отмена") } },
        ) { DatePicker(state = dateState) }
        PlanPickerStage.TIME -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    // DatePicker отдаёт UTC-полночь: локальная дата = picked + смещение зоны.
                    val zone = java.time.ZoneId.systemDefault()
                    val offsetMillis = zone.rules.getOffset(java.time.Instant.ofEpochMilli(pickedDateMillis))
                        .totalSeconds * 1000L
                    val localMidnight = pickedDateMillis + offsetMillis
                    val atMillis = localMidnight +
                        timeState.hour * 3_600_000L + timeState.minute * 60_000L
                    if (atMillis > System.currentTimeMillis()) onPlan(atMillis) else onDismiss()
                }) { Text(text = "Запланировать") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(text = "Отмена") } },
            text = { TimePicker(state = timeState) },
        )
    }
}

private enum class PlanPickerStage { DATE, TIME }

/** Отмена будильника плана (3.6) — зеркало PlanReminderReceiver.schedule. */
fun cancelPlanAlarm(context: Context, planId: Long) {
    val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
    val intent = PendingIntent.getBroadcast(
        context,
        planId.toInt(),
        Intent(context, app.walkquest.reminder.PlanReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    alarmManager.cancel(intent)
}
