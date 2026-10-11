package app.walkquest.ui.quests

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.walkquest.ui.theme.AccentTextColor
import app.walkquest.ui.theme.DimTextColor
import app.walkquest.ui.theme.HeadlineColor
import app.walkquest.ui.theme.InkSecondaryColor
import app.walkquest.ui.theme.WalkQuestGhostButton
import app.walkquest.ui.theme.WalkQuestPrimaryButton
import app.walkquest.viewmodel.MapViewModel

/**
 * Поток заданий прогулки (4.5, FR-16; EXPERIENCE): активное задание одно за раз,
 * выполнение на честном слове одним нажатием, «Вытянуть карту» — раздача случайного,
 * «Выбрать из 6» — сетка предложенных. Используется в двух местах утверждённой
 * навигации: сегмент «Задания» вкладки Прогресс и оверлей «+ Задание» поверх
 * прогулки (запись не останавливается).
 *
 * [onClose] — null на сегменте Прогресса (выхода не нужно); на оверлее прогулки
 * возвращает именно на экран прогулки, а не во вкладку.
 */
@Composable
fun QuestsContent(viewModel: MapViewModel, onClose: (() -> Unit)? = null) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Задания",
                color = HeadlineColor,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.weight(1f),
            )
            onClose?.let { close ->
                TextButton(onClick = close) { Text("Готово", color = AccentTextColor) }
            }
        }

        if (!uiState.showQuestButton) {
            Text(
                text = "Кнопка заданий на прогулке выключена в настройках.",
                color = InkSecondaryColor,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { viewModel.onShowQuestButtonChanged(true) }) {
                Text("Включить", color = AccentTextColor)
            }
        } else {
            val active = uiState.questActive
            if (active != null) {
                // card-task (DESIGN): card-active + метка «Активное задание».
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AccentTextColor.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, AccentTextColor.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Активное задание",
                            color = DimTextColor,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            text = "${active.icon} ${active.name}",
                            color = HeadlineColor,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = active.description,
                            color = InkSecondaryColor,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = viewModel::onQuestCompleted) {
                                Text("Выполнено", color = AccentTextColor)
                            }
                            TextButton(onClick = viewModel::onQuestDeclined) {
                                Text("Отказаться", color = InkSecondaryColor)
                            }
                            TextButton(onClick = { viewModel.onQuestRandom() }) {
                                Text("Другое", color = InkSecondaryColor)
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = "Задания помогут разнообразить вашу прогулку. " +
                        "Их выполнение необязательно :)",
                    color = InkSecondaryColor,
                    style = MaterialTheme.typography.bodyMedium,
                )
                // «Вытянуть карту» — акцентная CTA в нижней зоне пальца.
                WalkQuestPrimaryButton(
                    onClick = viewModel::onQuestRandom,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(text = "Вытянуть карту") }
                WalkQuestGhostButton(
                    onClick = viewModel::onQuestPickRequested,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(text = "Выбрать из 6") }
            }
        }

        // Выбор из 6 (4.5): предложенные карточки прямо в потоке.
        if (uiState.questPickOptions.isNotEmpty()) {
            Text(
                text = "Выберите задание",
                color = HeadlineColor,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            uiState.questPickOptions.forEach { option ->
                Surface(
                    onClick = { viewModel.onQuestPicked(option.id) },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "${option.icon} ${option.name}",
                            color = HeadlineColor,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = option.description,
                            color = InkSecondaryColor,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            TextButton(onClick = viewModel::onQuestPickDismissed) {
                Text("Отмена", color = InkSecondaryColor)
            }
        }

        // Выполненные (история заданий из QuestState.doneIds).
        Text(
            text = "Выполненные · ${uiState.questDone.size}",
            color = DimTextColor,
            style = MaterialTheme.typography.labelSmall,
        )
        if (uiState.questDone.isEmpty()) {
            Text(
                text = "Пока нет выполненных заданий.",
                color = DimTextColor,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            uiState.questDone.forEach { done ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "${done.icon} ${done.name}",
                        color = InkSecondaryColor,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(text = "✓", color = AccentTextColor)
                }
            }
        }
    }

    // Подтверждение замены активного задания (прежнее снимается без штрафа).
    uiState.questReplacePending?.let {
        AlertDialog(
            onDismissRequest = viewModel::onQuestReplaceCancelled,
            confirmButton = {
                TextButton(onClick = viewModel::onQuestReplaceConfirmed) { Text("Взять новое") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onQuestReplaceCancelled) { Text("Оставить текущее") }
            },
            title = { Text("У вас уже есть задание") },
            text = { Text("Прежнее задание снимется без штрафа.") },
        )
    }
}
