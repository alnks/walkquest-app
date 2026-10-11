package app.walkquest.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.walkquest.viewmodel.StatsViewModel
import java.util.Locale

/**
 * Экран статистики (история 3.4, FR-13): серия, число прогулок, суммарное
 * расстояние, проценты посещённых регионов. Все числа — готовые из viewmodel
 * (domain, AD-4): здесь только рендер. Источник истины — история на устройстве.
 */
@Composable
fun StatsScreen(viewModel: StatsViewModel, embedded: Boolean = false) {
    val ui by viewModel.uiState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var showPlanPicker by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(if (embedded) Modifier else Modifier.statusBarsPadding())
            .verticalScroll(rememberScrollState())
            .padding(if (embedded) 16.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!embedded) {
            Text(
                text = "Статистика",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        ui.goalText?.let {
            Text(
                text = "Цель: $it",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (ui.loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            return@Column
        }
        StatRow(label = "Серия", value = pluralDays(ui.streakDays))
        StatRow(label = "Прогулок", value = ui.totalWalks.toString())
        StatRow(
            label = "Расстояние",
            value = "%.1f км".format(ui.totalDistanceMeters / 1000.0),
        )
        if (ui.regions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Регионы",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            ui.regions.forEach { region ->
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(text = region.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = formatPercent(region.fraction),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { region.fraction.toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                    )
                }
            }
        }
        // Планировщик (3.6, FR-17): планы + создание через DatePicker/TimePicker.
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Планы",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            TextButton(onClick = { showPlanPicker = true }) {
                Text(text = "Запланировать")
            }
        }
        ui.plans.forEach { plan ->
            val status = when {
                plan.fulfilled -> "✓ выполнен"
                plan.past -> "пропущен (без наказания)"
                else -> "ожидает"
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${formatPlanDate(plan.plannedAtEpochMillis)} — $status",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!plan.past) {
                    TextButton(onClick = {
                        viewModel.cancelPlan(plan.id)
                        app.walkquest.ui.common.cancelPlanAlarm(context, plan.id)
                    }) { Text(text = "Отмена") }
                }
            }
        }
    }

    if (showPlanPicker) {
        app.walkquest.ui.common.PlanDateTimePicker(
            onDismiss = { showPlanPicker = false },
            onPlan = { atMillis ->
                showPlanPicker = false
                val planId = viewModel.planWalk(atMillis)
                if (planId != null) {
                    app.walkquest.reminder.PlanReminderReceiver.schedule(
                        context,
                        planId,
                        atMillis,
                        viewModel.planReminderLeadMinutes(),
                    )
                }
            },
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Text(text = value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
    }
}

/** «0 дней» / «1 день» / «2 дня» / «5 дней». */
private fun pluralDays(n: Int): String {
    val mod100 = n % 100
    val mod10 = n % 10
    val word = when {
        mod100 in 11..19 -> "дней"
        mod10 == 1 -> "день"
        mod10 in 2..4 -> "дня"
        else -> "дней"
    }
    return "$n $word"
}

/** Процент с русской запятой и 1 знаком (как formatPercent карты). */
private fun formatPercent(fraction: Double): String =
    String.format(Locale("ru", "RU"), "%.1f%%", fraction * 100.0)

private fun formatPlanDate(epochMillis: Long): String =
    java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale("ru", "RU"))
        .format(java.util.Date(epochMillis))
