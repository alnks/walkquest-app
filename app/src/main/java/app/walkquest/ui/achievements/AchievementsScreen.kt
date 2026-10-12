package app.walkquest.ui.achievements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.walkquest.ui.theme.WqTheme
import app.walkquest.viewmodel.AchievementsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экран наград (история 4.4): 38 достижений по категориям; у неполученных —
 * условие, у близких — полоска прогресса; rare помечены. Перепроверка по
 * истории происходит во viewmodel при входе (FR-15: задним числом).
 * Спецификация: screens-spec.md §5b.
 */
@Composable
fun AchievementsScreen(viewModel: AchievementsViewModel, embedded: Boolean = false) {
    val rows by viewModel.uiState.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(if (embedded) Modifier else Modifier.statusBarsPadding())
            .verticalScroll(rememberScrollState())
            .padding(if (embedded) WqTheme.spacing.lg else WqTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(WqTheme.spacing.md),
    ) {
        if (!embedded) {
            Text(
                text = "Награды",
                style = WqTheme.typography.headingScreen,
                color = WqTheme.colors.inkHeadline,
            )
        }
        var lastCategory: String? = null
        rows.forEach { row ->
            if (row.category != lastCategory) {
                lastCategory = row.category
                Text(
                    text = row.category,
                    style = WqTheme.typography.headingCard,
                    color = WqTheme.colors.inkHeadline,
                    modifier = Modifier.padding(top = WqTheme.spacing.sm),
                )
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = row.icon,
                        style = WqTheme.typography.headingSection,
                        modifier = Modifier.padding(end = WqTheme.spacing.md),
                    )
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = row.name,
                                style = WqTheme.typography.body,
                                color = if (row.earnedAt != null) {
                                    WqTheme.colors.inkPrimary
                                } else {
                                    WqTheme.colors.inkSecondary
                                },
                            )
                            if (row.rare) {
                                Text(
                                    text = " ✦",
                                    color = WqTheme.colors.accentText,
                                    style = WqTheme.typography.body,
                                )
                            }
                        }
                        val subtitle = when {
                            row.earnedAt != null ->
                                "Получено " + SimpleDateFormat("dd.MM.yyyy", Locale("ru", "RU"))
                                    .format(Date(row.earnedAt))
                            else -> row.conditionText
                        }
                        Text(
                            text = subtitle,
                            style = WqTheme.typography.meta,
                            color = WqTheme.colors.inkDim,
                        )
                    }
                }
                row.progress?.let { (current, target) ->
                    LinearProgressIndicator(
                        progress = { (current / target).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .padding(start = 36.dp),
                        color = WqTheme.colors.accent,
                        trackColor = WqTheme.colors.inkDim.copy(alpha = 0.3f),
                    )
                }
            }
        }
    }
}
