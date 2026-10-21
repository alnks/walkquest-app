package app.walkquest.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.walkquest.ui.theme.WalkQuestTheme
import app.walkquest.ui.theme.WqTheme

/**
 * Строка списка со значением-пилюлей (макеты remaining-screens «Профиль»/«Настройки»:
 * `.pr-row` + `.pill`). Группа строк живёт внутри [WqCard]; разделитель между строками
 * — тональная линия surface-sunken (макет: #2C2248 тёмная / #EFEAf6 светлая), без
 * рамок-«таблицы» — ритм держится отступами (DESIGN Do's and Don'ts).
 *
 * Доступность и эргономика (дизайн-интеллект ui-ux-pro-max): tap-area ≥48dp;
 * подпись строки сжимается (weight), пилюля значения целиком в одну строку
 * (maxLines = 1) — значение не обрезается молча, подпись уходит в многоточие.
 *
 * Только токены WqTheme — ни одного hex вне ui/theme.
 */

/**
 * Строка «подпись слева — значение-пилюля справа».
 *
 * @param showDivider линия-разделитель СВЕРХУ строки (первая строка группы — false,
 *  макет `.pr-row + .pr-row { border-top }`).
 * @param enabled false — тап игнорируется (busy-состояния; визуально без изменений,
 *  как busy-кнопки спеки §8).
 */
@Composable
fun WqValueRow(
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showDivider: Boolean = true,
    pill: @Composable RowScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (showDivider) {
            HorizontalDivider(color = WqTheme.colors.surfaceSunken)
        }
        val row: @Composable () -> Unit = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(vertical = 12.dp),
            ) {
                Text(
                    text = label,
                    color = WqTheme.colors.inkSecondary,
                    style = WqTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                pill()
            }
        }
        if (onClick != null) {
            Surface(onClick = onClick, enabled = enabled, content = row)
        } else {
            row()
        }
    }
}

/**
 * Пилюля-значение (макет `.pill`): meta 12sp (минимальный кегль), одна строка.
 * [accent] = активное/заданное значение (`.pill.on`): заливка accentSoft, рамка
 * accentSoftBorder, текст accentText — токены card-active (DESIGN Components).
 * Нейтральная: заливка surface-sunken (тёмная) / surface-base с рамкой hairline
 * (светлая), текст ink-secondary — по макету.
 */
@Composable
fun WqValuePill(
    text: String,
    accent: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    val color = when {
        accent -> WqTheme.colors.accentSoft
        dark -> WqTheme.colors.surfaceSunken
        else -> WqTheme.colors.surfaceBase
    }
    val border = when {
        accent -> BorderStroke(1.dp, WqTheme.colors.accentSoftBorder)
        dark -> null
        else -> BorderStroke(1.dp, WqTheme.colors.borderHairline)
    }
    Surface(
        modifier = modifier,
        shape = WqTheme.shapes.full,
        color = color,
        border = border,
    ) {
        Text(
            text = text,
            color = if (accent) WqTheme.colors.accentText else WqTheme.colors.inkSecondary,
            style = WqTheme.typography.meta,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 4.dp),
        )
    }
}

@Preview(name = "Светлая", showBackground = true)
@Preview(name = "Тёмная", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WqListRowsPreview() {
    WalkQuestTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            WqCard {
                Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                    WqValueRow(label = "Ник в рейтинге", onClick = {}, showDivider = false) {
                        WqValuePill(text = "olya_walks", accent = true)
                    }
                    WqValueRow(label = "Моя цель", onClick = {}) {
                        WqValuePill(text = "3 прогулки в неделю →")
                    }
                    WqValueRow(label = "Версия", onClick = null) {
                        WqValuePill(text = "1.0")
                    }
                }
            }
        }
    }
}
