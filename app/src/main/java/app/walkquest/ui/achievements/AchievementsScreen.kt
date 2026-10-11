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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.walkquest.viewmodel.AchievementsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экран наград (история 4.4): 38 достижений по категориям; у неполученных —
 * условие, у близких — полоска прогресса; rare помечены. Перепроверка по
 * истории происходит во viewmodel при входе (FR-15: задним числом).
 */
@Composable
fun AchievementsScreen(viewModel: AchievementsViewModel, embedded: Boolean = false) {
    val rows by viewModel.uiState.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(if (embedded) Modifier else Modifier.statusBarsPadding())
            .verticalScroll(rememberScrollState())
            .padding(if (embedded) 16.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!embedded) {
            Text(
                text = "Награды",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        var lastCategory: String? = null
        rows.forEach { row ->
            if (row.category != lastCategory) {
                lastCategory = row.category
                Text(
                    text = row.category,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = row.icon, style = MaterialTheme.typography.headlineSmall)
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = row.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (row.earnedAt != null) FontWeight.Bold else FontWeight.Normal,
                            )
                            if (row.rare) {
                                Text(
                                    text = " ✦",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodyLarge,
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
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    )
                }
            }
        }
    }
}
