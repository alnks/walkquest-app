package app.walkquest.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.walkquest.ui.theme.WalkQuestTheme
import app.walkquest.ui.theme.WqTheme

/**
 * Карточки WalkQuest (DESIGN.md Components: card / card-active, stat-line).
 *
 * [WqCard] — базовая карточка: фон surface-raised, рамка border-hairline,
 * радиус md. Глубина тональная, без теней (Elevation & Depth: тени — только у
 * парящих над картой элементов).
 *
 * [WqCardActive] — выбранное/активное состояние (card-task, строка рейтинга
 * текущего пользователя): заливка accent @ 8% alpha, рамка accent @ 35% alpha —
 * вычисляется токенами accentSoft/accentSoftBorder от акцента активной темы.
 *
 * [WqStatColumn] — мини-колонка «значение над подписью» (stat-line): значение
 * Nunito 800 акцентом, подпись meta ink-dim. БЕЗ разделителей, подложек и рамок
 * (Don't: плашки-подложки, «таблица») — ритм держится отступами и воздухом.
 */

@Composable
fun WqCard(
    modifier: Modifier = Modifier,
    tonalElevation: Dp = 0.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spec = Modifier
        .fillMaxWidth()
        .then(modifier)
    val shape = WqTheme.shapes.medium
    val color = WqTheme.colors.surfaceRaised
    val border = BorderStroke(1.dp, WqTheme.colors.borderHairline)
    if (onClick != null) {
        Surface(onClick = onClick, modifier = spec, shape = shape, color = color, border = border) {
            Column(content = content)
        }
    } else {
        Surface(modifier = spec, shape = shape, color = color, border = border) {
            Column(content = content)
        }
    }
}

@Composable
fun WqCardActive(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spec = Modifier
        .fillMaxWidth()
        .then(modifier)
    val shape = WqTheme.shapes.medium
    val color = WqTheme.colors.accentSoft
    val border = BorderStroke(1.dp, WqTheme.colors.accentSoftBorder)
    if (onClick != null) {
        Surface(onClick = onClick, modifier = spec, shape = shape, color = color, border = border) {
            Column(content = content)
        }
    } else {
        Surface(modifier = spec, shape = shape, color = color, border = border) {
            Column(content = content)
        }
    }
}

/**
 * Мини-колонка статистики: значение акцентом (Nunito 800) над подписью meta.
 * [valueColor] по умолчанию accentText (светлая — #587110, тёмная — accent).
 */
@Composable
fun WqStatColumn(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = WqTheme.colors.accentText,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = valueColor,
            style = WqTheme.typography.headingSection,
        )
        Text(
            text = label,
            color = WqTheme.colors.inkDim,
            style = WqTheme.typography.meta,
        )
    }
}

@Preview(name = "Светлая", showBackground = true)
@Preview(name = "Тёмная", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WqCardsPreview() {
    WalkQuestTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WqCard {
                Text(
                    text = "Карточка планировщика",
                    color = WqTheme.colors.inkSecondary,
                    style = WqTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
            WqCardActive {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Активное задание",
                        color = WqTheme.colors.inkDim,
                        style = WqTheme.typography.meta,
                    )
                    Text(
                        text = "Пять фиолетовых вещей",
                        color = WqTheme.colors.inkHeadline,
                        style = WqTheme.typography.headingCard,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                WqStatColumn(value = "17,4%", label = "открыто")
                WqStatColumn(value = "3 дня", label = "серия")
                WqStatColumn(value = "12,3 км", label = "за неделю")
            }
        }
    }
}
