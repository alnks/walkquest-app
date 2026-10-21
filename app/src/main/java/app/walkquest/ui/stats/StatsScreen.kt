package app.walkquest.ui.stats

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.walkquest.ui.components.WqCard
import app.walkquest.ui.theme.WqTheme
import app.walkquest.viewmodel.StatsViewModel
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Сегмент «Статистика» вкладки «Прогресс» (эпик design-alignment, история 7.4,
 * требование AL-4): герой-процент открытия домашнего региона (displaySmall,
 * Nunito 1000, акцентом), сетка 6 карточек метрик (прогулок/км/серия/
 * достижения/задания/регионы), список средних метрик (максимальная серия,
 * средние км и время прогулки) + планировщик (сохранён по AL-4).
 * Все числа — готовые из viewmodel (domain, AD-4): здесь только рендер и
 * форматирование (ru-локаль: запятая в десятичных, как 7.2).
 * Макет: mockups/progress-tasks-duo.html «Прогресс · Статистика».
 */
@Composable
fun StatsScreen(viewModel: StatsViewModel, embedded: Boolean = false) {
    val ui by viewModel.uiState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var showPlanPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(if (embedded) Modifier else Modifier.statusBarsPadding())
            .verticalScroll(rememberScrollState())
            .padding(if (embedded) WqTheme.spacing.lg else WqTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(WqTheme.spacing.lg),
    ) {
        if (!embedded) {
            Text(
                text = "Статистика",
                style = WqTheme.typography.headingScreen,
                color = WqTheme.colors.inkHeadline,
            )
        }
        if (ui.loading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = WqTheme.colors.accent,
                trackColor = WqTheme.colors.inkDim.copy(alpha = 0.3f),
            )
            return@Column
        }
        // Герой: процент открытия домашнего региона (макет .pr-hero). Нет пакета
        // региона — блок скрыт (как «территории открыто» финала, screens-spec §3);
        // чистая установка после онбординга показывает честный «0,0%».
        ui.homeRegionFraction?.let { fraction ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(WqTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatPercent(fraction),
                    style = WqTheme.typography.displaySmall,
                    color = WqTheme.colors.accentText,
                )
                Text(
                    text = "домашнего региона\nоткрыто",
                    style = WqTheme.typography.bodySmall,
                    color = WqTheme.colors.inkSecondary,
                )
            }
        }
        // Сетка 6 карточек метрик (макет .pr-grid: 3 колонки, зазор 7px ≈ sm,
        // паддинг 9px ≈ md, скругление 11px → shapes.medium/WqCard). Число +
        // единица одной строкой (как карточки финала 7.2: headingCard + meta),
        // подпись meta ink-dim.
        StatsCardRow {
            StatCard(
                value = ui.totalWalks.toString(),
                unit = null,
                label = "прогулок всего",
                modifier = Modifier.weight(1f),
            )
            StatCard(
                value = formatKilometers(ui.totalDistanceMeters),
                unit = "км",
                label = "пройдено всего",
                modifier = Modifier.weight(1f),
            )
            StatCard(
                value = ui.streakDays.toString(),
                unit = pluralDaysWord(ui.streakDays),
                label = "текущая серия",
                modifier = Modifier.weight(1f),
            )
        }
        StatsCardRow {
            StatCard(
                value = ui.achievementsEarned.toString(),
                unit = null,
                label = "достижений",
                modifier = Modifier.weight(1f),
            )
            StatCard(
                value = ui.questsCompleted.toString(),
                unit = null,
                label = "заданий выполнено",
                modifier = Modifier.weight(1f),
            )
            StatCard(
                value = ui.regions.size.toString(),
                unit = null,
                label = "региона посещено",
                modifier = Modifier.weight(1f),
            )
        }
        // Список средних метрик (макет .numlist): label bodySmall ink-secondary,
        // значение headingCard ink-headline; разделитель снизу — тональная линия
        // (макет: surface-raised тёмная / surface-sunken светлая). Пока прогулок
        // не было — честный прочерк «—» (как панель карты, spec §1).
        AvgRow(
            label = "Максимальная серия",
            value = pluralDays(ui.maxStreakDays),
        )
        AvgRow(
            label = "В среднем за прогулку",
            value = if (ui.totalWalks > 0) {
                formatKilometers(ui.totalDistanceMeters / ui.totalWalks) + " км"
            } else {
                "—"
            },
        )
        AvgRow(
            label = "Среднее время прогулки",
            value = if (ui.totalWalks > 0) {
                "${pluralMinutes(ui.totalActiveMillis / ui.totalWalks)} мин"
            } else {
                "—"
            },
        )
        // Планировщик (3.6, FR-17; сохранён по AL-4): планы + создание
        // через DatePicker/TimePicker.
        Spacer(modifier = Modifier.height(WqTheme.spacing.xs))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Планы",
                style = WqTheme.typography.headingSection,
                color = WqTheme.colors.inkHeadline,
            )
            TextButton(onClick = { showPlanPicker = true }) {
                Text(
                    text = "Запланировать",
                    color = WqTheme.colors.accentText,
                    style = WqTheme.typography.metaLarge,
                )
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
                    style = WqTheme.typography.body,
                    color = if (plan.past) WqTheme.colors.inkDim else WqTheme.colors.inkPrimary,
                )
                if (!plan.past) {
                    TextButton(onClick = {
                        viewModel.cancelPlan(plan.id)
                        app.walkquest.ui.common.cancelPlanAlarm(context, plan.id)
                    }) { Text(text = "Отмена", color = WqTheme.colors.inkSecondary) }
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

/** Ряд из 3 карточек метрик (макет .pr-grid: зазор 7px ≈ spacing.sm). */
@Composable
private fun StatsCardRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(WqTheme.spacing.sm),
        content = content,
    )
}

/** Карточка метрики сетки: строка «число (+единица)» над подписью, контент слева. */
@Composable
private fun StatCard(
    value: String,
    unit: String?,
    label: String,
    modifier: Modifier = Modifier,
) {
    WqCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(WqTheme.spacing.md),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = WqTheme.typography.headingCard,
                    color = WqTheme.colors.inkHeadline,
                )
                if (unit != null) {
                    Spacer(modifier = Modifier.width(WqTheme.spacing.xs))
                    Text(
                        text = unit,
                        style = WqTheme.typography.meta,
                        color = WqTheme.colors.inkHeadline,
                    )
                }
            }
            Text(
                text = label,
                style = WqTheme.typography.meta,
                color = WqTheme.colors.inkDim,
            )
        }
    }
}

/** Строка средних метрик (макет .nl-row): подпись слева, значение справа,
 *  разделитель снизу — тональная линия по макету (raised тёмная / sunken светлая). */
@Composable
private fun AvgRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = WqTheme.spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = WqTheme.typography.bodySmall,
                color = WqTheme.colors.inkSecondary,
            )
            Text(
                text = value,
                style = WqTheme.typography.headingCard,
                color = WqTheme.colors.inkHeadline,
            )
        }
        HorizontalDivider(
            color = if (isSystemInDarkTheme()) {
                WqTheme.colors.surfaceRaised
            } else {
                WqTheme.colors.surfaceSunken
            },
        )
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

/** Только слово: «день» / «дня» / «дней» (единица под числом карточки серии). */
private fun pluralDaysWord(n: Int): String = pluralDays(n).substringAfter(' ')

/** Среднее время, минуты (округление — значение «N мин» списка средних). */
private fun pluralMinutes(avgMillis: Long): Int =
    (avgMillis / 60_000.0).roundToInt()

/** Процент с русской запятой и 1 знаком (как formatPercent карты и финала). */
private fun formatPercent(fraction: Double): String =
    String.format(Locale("ru", "RU"), "%.1f%%", fraction * 100.0)

/** Километры с русской запятой и 1 знаком (как 7.2). */
private fun formatKilometers(meters: Double): String =
    String.format(Locale.forLanguageTag("ru-RU"), "%.1f", meters / 1000.0)

private fun formatPlanDate(epochMillis: Long): String =
    java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale("ru", "RU"))
        .format(java.util.Date(epochMillis))
