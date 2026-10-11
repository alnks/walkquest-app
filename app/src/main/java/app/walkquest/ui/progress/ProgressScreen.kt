package app.walkquest.ui.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.walkquest.ui.achievements.AchievementsScreen
import app.walkquest.ui.quests.QuestsContent
import app.walkquest.ui.stats.StatsScreen
import app.walkquest.ui.theme.AccentTextColor
import app.walkquest.ui.theme.BorderStrongColor
import app.walkquest.ui.theme.DimTextColor
import app.walkquest.ui.theme.HeadlineColor
import app.walkquest.viewmodel.AchievementsViewModel
import app.walkquest.viewmodel.MapViewModel
import app.walkquest.viewmodel.StatsViewModel

/**
 * Вкладка «Прогресс» (EXPERIENCE: 4 вкладки Карта·Прогресс·Рейтинг·Профиль):
 * сегменты Статистика / Достижения / Задания под общим заголовком. Каждый вход
 * во вкладку открывает Статистику — выбранный сегмент между заходами не
 * запоминается (переключение вкладок пересоздаёт композицию).
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
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Text(
            text = "Прогресс",
            color = HeadlineColor,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp),
        )
        // Сегмент-контрол (DESIGN: ≥13px при высоте ≥48dp, пилюли).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SEGMENTS.forEachIndexed { index, label ->
                SegmentPill(
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

/** Пилюля сегмента: выбранная — заливка акцентом (тёмная) / рамка+текст (светлая). */
@Composable
private fun SegmentPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(percent = 50),
            color = AccentTextColor,
            modifier = modifier,
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.surface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 12.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    } else {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(percent = 50),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderStrongColor),
            modifier = modifier,
        ) {
            Text(
                text = label,
                color = DimTextColor,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 12.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
