package app.walkquest.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.walkquest.logging.wqLogGestures
import app.walkquest.ui.theme.WalkQuestIcons
import app.walkquest.ui.theme.WalkQuestTheme
import app.walkquest.ui.theme.WqTheme

/**
 * Кнопки WalkQuest (DESIGN.md Components: button-primary / button-ghost).
 *
 * [WqPrimaryButton] — единственная акцентная кнопка экрана (Do: «одна акцентная
 * заливка на экран»), живёт в нижней трети, высота ≥48dp, радиус lg:
 *  - тёмная тема — заливка accent + текст accentInk;
 *  - светлая — «призрак»: белая заливка, рамка 1.5dp и текст accent-text
 *    (залитая #7A9A1F кнопка в светлой теме запрещена — белый текст не читается).
 *
 * [WqGhostButton] — вторичное действие (обрыв сессии, «Ещё раз»): прозрачная,
 * рамка border-strong, текст ink-secondary, радиус full (пилюля), высота ≥48dp.
 * Частое действие («Пауза») — primary; обрыв сессии («Завершить») — ghost
 * с подтверждением (Don't: одинаковые кнопки рядом / случайный тап).
 */

@Composable
fun WqPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    if (isSystemInDarkTheme()) {
        Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp).wqLogGestures("primary-button"),
            enabled = enabled,
            shape = WqTheme.shapes.large,
            colors = ButtonDefaults.buttonColors(
                containerColor = WqTheme.colors.accent,
                contentColor = WqTheme.colors.accentInk,
                disabledContainerColor = WqTheme.colors.accent.copy(alpha = 0.4f),
                disabledContentColor = WqTheme.colors.accentInk.copy(alpha = 0.6f),
            ),
            content = content,
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp).wqLogGestures("primary-button"),
            enabled = enabled,
            shape = WqTheme.shapes.large,
            border = BorderStroke(1.5.dp, WqTheme.colors.accentText),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = WqTheme.colors.surfaceRaised,
                contentColor = WqTheme.colors.accentText,
            ),
            content = content,
        )
    }
}

@Composable
fun WqGhostButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = WqTheme.shapes.full,
        border = BorderStroke(1.5.dp, WqTheme.colors.borderStrong),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentColor = WqTheme.colors.inkSecondary,
        ),
        content = content,
    )
}

/**
 * Кнопка выдачи задания (сегмент «Задания», макет progress-tasks-duo.html
 * `.tbtn`): иконка над подписью в колонке, заливка taskButtonBg (светлая —
 * белая surfaceRaised, тёмная — surfaceSunken), рамка border-strong 1.5dp,
 * радиус md; в ряду две такие кнопки weight(1f).
 * Высота ≥48dp (тач-зона), в макете ~64dp с учётом иконки. Эмодзи 🎲/🃏
 * макета — плейсхолдеры: в продукте векторные иконки (WalkQuestIcons),
 * tint — токен inkHeadline. Не акцентная заливка (Do: одна акцентная
 * кнопка на экран; раздача — поверхностное действие).
 */
@Composable
fun WqTaskActionButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 64.dp),
        shape = WqTheme.shapes.medium,
        color = WqTheme.colors.taskButtonBg,
        border = BorderStroke(1.5.dp, WqTheme.colors.borderStrong),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = WqTheme.colors.inkHeadline,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = label,
                color = WqTheme.colors.inkHeadline,
                style = WqTheme.typography.bodySmall,
            )
        }
    }
}

@Preview(name = "Светлая", showBackground = true)
@Preview(name = "Тёмная", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WqButtonsPreview() {
    WalkQuestTheme {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WqPrimaryButton(onClick = {}) { Text("Начать прогулку") }
            WqGhostButton(onClick = {}) { Text("Завершить") }
            WqPrimaryButton(onClick = {}, enabled = false) { Text("Загружается…") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WqTaskActionButton(
                    onClick = {},
                    icon = WalkQuestIcons.Cards,
                    label = "Вытянуть карту",
                    modifier = Modifier.weight(1f),
                )
                WqTaskActionButton(
                    onClick = {},
                    icon = WalkQuestIcons.GridSix,
                    label = "Выбрать из 6",
                    modifier = Modifier.weight(1f),
                )
            }
            // Стиль текста кнопок M3 тоже из токенов (label/metaLarge ≥13px).
            Text("Контроль текста: Прогулка 17,4%", style = WqTheme.typography.metaLarge)
        }
    }
}
