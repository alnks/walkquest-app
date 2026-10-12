package app.walkquest.ui.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.walkquest.ui.achievements.AchievementsScreen
import app.walkquest.ui.components.WqSegmentPill
import app.walkquest.ui.quests.QuestsContent
import app.walkquest.ui.stats.StatsScreen
import app.walkquest.ui.theme.WqTheme
import app.walkquest.viewmodel.AchievementsViewModel
import app.walkquest.viewmodel.MapViewModel
import app.walkquest.viewmodel.StatsViewModel

/**
 * Вкладка «Прогресс» (EXPERIENCE: 4 вкладки Карта·Прогресс·Рейтинг·Профиль):
 * сегменты Статистика / Достижения / Задания под общим заголовком. Каждый вход
 * во вкладку открывает Статистику — выбранный сегмент между заходами не
 * запоминается (переключение вкладок пересоздаёт композицию).
 * Спецификация: screens-spec.md §5.
 */
@Composable
fun ProgressScreen(
    statsViewModel: StatsViewModel,
    achievementsViewModel: AchievementsViewModel,
    mapViewModel: MapViewModel,
) {
    var segment by rememberSaveable { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WqTheme.colors.surfaceBase)
            .statusBarsPadding(),
    ) {
        Text(
            text = "Прогресс",
            color = WqTheme.colors.inkHeadline,
            style = WqTheme.typography.headingScreen,
            modifier = Modifier.padding(
                start = WqTheme.spacing.lg,
                end = WqTheme.spacing.lg,
                top = WqTheme.spacing.lg,
                bottom = 10.dp,
            ),
        )
        // Сегмент-контрол (DESIGN: ≥48dp, шрифт ≥13px — WqSegmentPill).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = WqTheme.spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(WqTheme.spacing.sm),
        ) {
            SEGMENTS.forEachIndexed { index, label ->
                WqSegmentPill(
                    label = label,
                    selected = segment == index,
                    onClick = { segment = index },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        when (segment) {
            0 -> StatsScreen(viewModel = statsViewModel, embedded = true)
            1 -> AchievementsScreen(viewModel = achievementsViewModel, embedded = true)
            else -> QuestsContent(viewModel = mapViewModel)
        }
    }
}

private val SEGMENTS = listOf("Статистика", "Достижения", "Задания")
