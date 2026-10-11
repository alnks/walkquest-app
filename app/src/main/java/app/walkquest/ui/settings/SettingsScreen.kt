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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.walkquest.tracking.WalkMode
import app.walkquest.ui.theme.AccentTextColor
import app.walkquest.ui.theme.DimTextColor
import app.walkquest.ui.theme.HeadlineColor
import app.walkquest.ui.theme.InkSecondaryColor
import app.walkquest.ui.theme.WalkQuestPrimaryButton
import app.walkquest.viewmodel.MapViewModel

/**
 * Настройки (DESIGN remaining-screens «Настройки (шестерёнка с главного)»):
 * оверлей первого уровня над главным экраном. Стрелок «назад» нет — выход
 * явной кнопкой «Готово» внизу (ergonomics: важные действия в нижней трети).
 * Источник состояния — общий MapViewModel: те же переключатели, что раньше
 * жили прототипными текстовыми кнопками посреди карты.
 */
@Composable
fun SettingsScreen(viewModel: MapViewModel, onClose: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Настройки",
            color = HeadlineColor,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
        )

        Text(
            text = "ПРОГУЛКА",
            color = DimTextColor,
            style = MaterialTheme.typography.labelSmall,
        )
        SettingsCard {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Режим по умолчанию",
                    color = InkSecondaryColor,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                WalkMode.entries.forEach { mode ->
                    val label = when (mode) {
                        WalkMode.WALKING -> "Пешком"
                        WalkMode.CYCLING -> "Вело"
                    }
                    if (uiState.walkMode == mode) {
                        WalkQuestPrimaryButton(onClick = { viewModel.onWalkModeSelected(mode) }) {
                            Text(text = label)
                        }
                    } else {
                        TextButton(onClick = { viewModel.onWalkModeSelected(mode) }) {
                            Text(text = label, color = InkSecondaryColor)
                        }
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

        Text(
            text = "Настройки применяются ко всем прогулкам и хранятся на устройстве.",
            color = DimTextColor,
            style = MaterialTheme.typography.bodySmall,
        )

        // Явный выход внизу экрана (DESIGN: никаких стрелок «назад» в шапках).
        WalkQuestPrimaryButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text(text = "Готово")
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            content()
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
                color = InkSecondaryColor,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = subtitle,
                color = DimTextColor,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = AccentTextColor,
            ),
        )
    }
}
