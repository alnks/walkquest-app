package app.walkquest.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.walkquest.data.settings.ThemeMode
import app.walkquest.logging.ScreenLogEffect
import app.walkquest.tracking.WalkMode
import app.walkquest.ui.components.WqCard
import app.walkquest.ui.components.WqPrimaryButton
import app.walkquest.ui.components.WqValuePill
import app.walkquest.ui.components.WqValueRow
import app.walkquest.ui.theme.WqTheme
import app.walkquest.viewmodel.MapViewModel

/**
 * Настройки (DESIGN remaining-screens «Настройки (шестерёнка с главного)»):
 * оверлей первого уровня над главным экраном. Стрелок «назад» нет — выход
 * явной кнопкой «Готово» внизу (ergonomics: важные действия в нижней трети).
 * Источник состояния — общий MapViewModel: те же переключатели, что раньше
 * жили прототипными текстовыми кнопками посреди карты.
 * Состав групп — макет remaining-screens v3 + история 7.8 (AL-8):
 * «Цель» (текущая цель), «Прогулки» (режим, автопауза, напоминание, «Задания»),
 * «Уведомления» (системные каналы, действия нет), «Внешний вид» (тема —
 * ручной выбор из трёх вариантов, 7.16; дефолт — системная).
 * Спецификация: screens-spec.md §10 (каркас и «Готово»).
 */
@Composable
fun SettingsScreen(viewModel: MapViewModel, onClose: () -> Unit) {
    ScreenLogEffect("SettingsScreen")
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var themePickerVisible by remember { mutableStateOf(false) }

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

        // Цель (макет v3): текущая цель значением-пилюлей; редактирование —
        // в онбординге/профиле, здесь только сводка (action не утверждён).
        Text(
            text = "Цель",
            color = WqTheme.colors.inkDim,
            style = WqTheme.typography.meta,
        )
        WqCard(tonalElevation = 1.dp) {
            Column(modifier = Modifier.padding(horizontal = WqTheme.spacing.md, vertical = WqTheme.spacing.sm)) {
                WqValueRow(label = "Моя цель", onClick = null, showDivider = false) {
                    WqValuePill(text = uiState.walkGoal?.describe() ?: "—", accent = true)
                }
            }
        }

        // Прогулки (spec §10 + макет v3): действующие настройки записи.
        Text(
            text = "Прогулки",
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

        // Уведомления (макет v3): собственных каналов в приложении нет —
        // пилюля-значение без действия (7.8: настройки в коде нет).
        Text(
            text = "Уведомления",
            color = WqTheme.colors.inkDim,
            style = WqTheme.typography.meta,
        )
        WqCard(tonalElevation = 1.dp) {
            Column(modifier = Modifier.padding(horizontal = WqTheme.spacing.md, vertical = WqTheme.spacing.sm)) {
                WqValueRow(label = "Уведомления", onClick = null, showDivider = false) {
                    WqValuePill(text = "по умолчанию системные")
                }
            }
        }

        // Внешний вид (7.16): ручной выбор темы — тап по строке открывает
        // диалог из трёх вариантов; выбор применяется на лету (MainActivity
        // подписан на uiState) и персистится (дефолт — системная).
        Text(
            text = "Внешний вид",
            color = WqTheme.colors.inkDim,
            style = WqTheme.typography.meta,
        )
        WqCard(tonalElevation = 1.dp) {
            Column(modifier = Modifier.padding(horizontal = WqTheme.spacing.md, vertical = WqTheme.spacing.sm)) {
                WqValueRow(label = "Тема", onClick = { themePickerVisible = true }, showDivider = false) {
                    WqValuePill(text = themeModeLabel(uiState.themeMode))
                }
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

    if (themePickerVisible) {
        ThemePickerDialog(
            selected = uiState.themeMode,
            onSelect = { mode ->
                viewModel.onThemeModeSelected(mode)
                themePickerVisible = false
            },
            onDismiss = { themePickerVisible = false },
        )
    }
}

/** Подпись режима темы — единственные копии значений (7.16). */
private fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "системная"
    ThemeMode.LIGHT -> "светлая"
    ThemeMode.DARK -> "тёмная"
}

/**
 * Диалог выбора темы (7.16): три варианта радио-списком на M3-AlertDialog
 * (мост MaterialTheme собран из токенов WqTheme — диалог не скакал бы на
 * дефолтной палитре). Строки — Surface(onClick) с tap-area ≥48dp (WqValueRow
 * тот же контракт), выбранный вариант — accentText + векторная галочка;
 * только токены WqTheme, без hex.
 */
@Composable
private fun ThemePickerDialog(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Тема", color = WqTheme.colors.inkHeadline, style = WqTheme.typography.headingCard)
        },
        text = {
            Column {
                ThemeMode.entries.forEach { mode ->
                    val isSelected = mode == selected
                    Surface(
                        onClick = { onSelect(mode) },
                        color = Color.Transparent,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .padding(vertical = WqTheme.spacing.xs),
                        ) {
                            Text(
                                text = themeModeLabel(mode),
                                color = if (isSelected) WqTheme.colors.accentText else WqTheme.colors.inkSecondary,
                                style = WqTheme.typography.body,
                                modifier = Modifier.weight(1f),
                            )
                            if (isSelected) {
                                Icon(
                                    imageVector = ThemeCheckIcon,
                                    contentDescription = null,
                                    tint = WqTheme.colors.accentText,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = "Готово") }
        },
    )
}

/** Галочка выбранного варианта (24dp-путь, как иконки вкладок MainActivity). */
private val ThemeCheckIcon: ImageVector = ImageVector.Builder(
    name = "theme-check",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = PathParser().parsePathString(
        "M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z",
    ).toNodes(),
    fill = SolidColor(Color.Black),
).build()

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
