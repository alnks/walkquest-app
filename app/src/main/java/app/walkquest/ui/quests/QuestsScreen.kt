package app.walkquest.ui.quests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.walkquest.ui.components.WqCard
import app.walkquest.ui.components.WqQuestCard
import app.walkquest.ui.components.WqTaskActionButton
import app.walkquest.ui.theme.WalkQuestIcons
import app.walkquest.ui.theme.WqTheme
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.walkquest.viewmodel.MapViewModel

/**
 * Поток заданий прогулки (4.5, FR-16; EXPERIENCE): активное задание одно за раз,
 * выполнение на честном слове одним нажатием, «Вытянуть карту» — раздача случайного,
 * «Выбрать из 6» — сетка предложенных. Используется в двух местах утверждённой
 * навигации: сегмент «Задания» вкладки Прогресс и оверлей «+ Задание» поверх
 * прогулки (запись не останавливается). Спецификация: screens-spec.md §6.
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
            .padding(WqTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(WqTheme.spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Задания",
                color = WqTheme.colors.inkHeadline,
                style = WqTheme.typography.headingScreen,
                modifier = Modifier.weight(1f),
            )
            onClose?.let { close ->
                TextButton(onClick = close) { Text("Готово", color = WqTheme.colors.accentText) }
            }
        }

        if (!uiState.showQuestButton) {
            Text(
                text = "Кнопка заданий на прогулке выключена в настройках.",
                color = WqTheme.colors.inkSecondary,
                style = WqTheme.typography.body,
            )
            TextButton(onClick = { viewModel.onShowQuestButtonChanged(true) }) {
                Text("Включить", color = WqTheme.colors.accentText)
            }
        } else {
            // Инструкция — плашка task-note из макета (показывается всегда,
            // и с активным заданием тоже).
            WqCard {
                Text(
                    text = "Задания помогут разнообразить вашу прогулку. " +
                        "Их выполнение необязательно :)",
                    color = WqTheme.colors.inkSecondary,
                    style = WqTheme.typography.bodySmall,
                    modifier = Modifier.padding(WqTheme.spacing.md),
                )
            }

            val active = uiState.questActive
            if (active != null) {
                // card-task (DESIGN): WqQuestCard(active) — WqCardActive
                // (accentSoft-заливка/рамка) + метка-пилюля «Активное задание».
                WqQuestCard(
                    title = "${active.icon} ${active.name}",
                    description = active.description,
                    badge = "Активное задание",
                    actions = {
                        TextButton(onClick = viewModel::onQuestCompleted) {
                            Text("Выполнено", color = WqTheme.colors.accentText)
                        }
                        TextButton(onClick = viewModel::onQuestDeclined) {
                            Text("Отказаться", color = WqTheme.colors.inkSecondary)
                        }
                        // «Другое» — только в сегменте вкладки (screens-spec §6);
                        // на оверлее прогулки действий два.
                        if (onClose == null) {
                            TextButton(onClick = { viewModel.onQuestRandom() }) {
                                Text("Другое", color = WqTheme.colors.inkSecondary)
                            }
                        }
                    },
                )
            }

            // Кнопки выдачи — пара .tbtn из макета; видны и с активным
            // заданием (новое берётся через диалог подтверждения замены).
            Row(
                horizontalArrangement = Arrangement.spacedBy(WqTheme.spacing.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                WqTaskActionButton(
                    onClick = viewModel::onQuestRandom,
                    icon = WalkQuestIcons.Cards,
                    label = "Вытянуть карту",
                    modifier = Modifier.weight(1f),
                )
                WqTaskActionButton(
                    onClick = viewModel::onQuestPickRequested,
                    icon = WalkQuestIcons.GridSix,
                    label = "Выбрать из 6",
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Выбор из 6 (4.5): предложенные карточки прямо в потоке.
        if (uiState.questPickOptions.isNotEmpty()) {
            Text(
                text = "Выберите задание",
                color = WqTheme.colors.inkHeadline,
                style = WqTheme.typography.headingSection,
            )
            uiState.questPickOptions.forEach { option ->
                WqCard(
                    onClick = { viewModel.onQuestPicked(option.id) },
                    tonalElevation = 1.dp,
                ) {
                    Column(modifier = Modifier.padding(WqTheme.spacing.md)) {
                        Text(
                            text = "${option.icon} ${option.name}",
                            color = WqTheme.colors.inkHeadline,
                            style = WqTheme.typography.headingCard,
                        )
                        Text(
                            text = option.description,
                            color = WqTheme.colors.inkSecondary,
                            style = WqTheme.typography.bodySmall,
                        )
                    }
                }
            }
            TextButton(onClick = viewModel::onQuestPickDismissed) {
                Text("Отмена", color = WqTheme.colors.inkSecondary)
            }
        }

        // Выполненные (история заданий из QuestState.doneIds).
        Text(
            text = "Выполненные · ${uiState.questDone.size}",
            color = WqTheme.colors.inkDim,
            style = WqTheme.typography.meta,
        )
        if (uiState.questDone.isEmpty()) {
            Text(
                text = "Пока нет выполненных заданий.",
                color = WqTheme.colors.inkDim,
                style = WqTheme.typography.bodySmall,
            )
        } else {
            uiState.questDone.forEach { done ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "${done.icon} ${done.name}",
                        color = WqTheme.colors.inkSecondary,
                        style = WqTheme.typography.body,
                        modifier = Modifier.weight(1f),
                    )
                    Text(text = "✓", color = WqTheme.colors.accentText)
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
