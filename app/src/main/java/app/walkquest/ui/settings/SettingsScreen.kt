package app.walkquest.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.walkquest.tracking.WalkMode
import app.walkquest.ui.components.WqCard
import app.walkquest.ui.components.WqPrimaryButton
import app.walkquest.ui.theme.WqTheme
import app.walkquest.viewmodel.MapViewModel

/**
 * Настройки (DESIGN remaining-screens «Настройки (шестерёнка с главного)»):
 * оверлей первого уровня над главным экраном. Стрелок «назад» нет — выход
 * явной кнопкой «Готово» внизу (ergonomics: важные действия в нижней трети).
 * Источник состояния — общий MapViewModel: те же переключатели, что раньше
 * жили прототипными текстовыми кнопками посреди карты.
 * Спецификация: screens-spec.md §10.
 */
@Composable
fun SettingsScreen(viewModel: MapViewModel, onClose: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WqTheme.colors.surfaceBase)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(WqTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(WqTheme.spacing.md),
    ) {
        Text(
            text = "Настройки",
            color = WqTheme.colors.inkHeadline,
            style = WqTheme.typography.headingScreen,
        )

        Text(
            text = "ПРОГУЛКА",
            color = WqTheme.colors.inkDim,
            style = WqTheme.typography.meta,
        )
        WqCard(tonalElevation = 1.dp) {
            Column(modifier = Modifier.padding(horizontal = WqTheme.spacing.md, vertical = WqTheme.spacing.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Режим по умолчанию",
                        color = WqTheme.colors.inkSecondary,
                        style = WqTheme.typography.body,
                        modifier = Modifier.weight(1f),
                    )
                    WalkMode.entries.forEach { mode ->
                        val label = when (mode) {
                            WalkMode.WALKING -> "Пешком"
                            WalkMode.CYCLING -> "Вело"
                        }
                        val selected = uiState.walkMode == mode
                        TextButton(onClick = { viewModel.onWalkModeSelected(mode) }) {
                            Text(
                                text = label,
                                color = if (selected) WqTheme.colors.accentText else WqTheme.colors.inkSecondary,
                                style = if (selected) WqTheme.typography.metaLarge else WqTheme.typography.body,
                            )
                        }
                    }
                }
                SwitchRow(
                    title = "Автопауза при остановке",
                    subtitle = "Прогулка сама встанет на паузу после 15 минут неподвижности",
                    checked = uiState.autoPauseEnabled,
                    onCheckedChange = { viewModel.onAutoPauseToggled() },
                )
                SwitchRow(
                    title = "Напоминание о забытой прогулке",
                    subtitle = "Спросим, если вы остановились и забыли завершить запись",
                    checked = uiState.forgottenReminderEnabled,
                    onCheckedChange = { viewModel.onForgottenReminderToggled() },
                )
                SwitchRow(
                    title = "Кнопка «Задания» на прогулке",
                    subtitle = "Показывать «+ Задание» во время записи",
                    checked = uiState.showQuestButton,
                    onCheckedChange = { viewModel.onShowQuestButtonChanged(it) },
                )
            }
        }

        Text(
            text = "Настройки применяются ко всем прогулкам и хранятся на устройстве.",
            color = WqTheme.colors.inkDim,
            style = WqTheme.typography.bodySmall,
        )

        // Явный выход внизу экрана (DESIGN: никаких стрелок «назад» в шапках).
        WqPrimaryButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text(text = "Готово")
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = WqTheme.colors.inkSecondary,
                style = WqTheme.typography.body,
            )
            Text(
                text = subtitle,
                color = WqTheme.colors.inkDim,
                style = WqTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = WqTheme.colors.accent,
                checkedThumbColor = WqTheme.colors.white,
            ),
        )
    }
}
