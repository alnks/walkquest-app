package app.walkquest.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.walkquest.ui.theme.WalkQuestTheme
import app.walkquest.ui.theme.WqTheme

/**
 * Сегмент-контроль WalkQuest (DESIGN: сегмент-контрол ≥48dp, шрифт ≥13px).
 *
 * Используется на вкладке Прогресс (Статистика / Достижения / Задания).
 * Выбранный сегмент: тёмная тема — заливка accent + текст accentInk;
 * светлая — заливка accent-text #587110 + белый текст (залитая #7A9A1F с белым
 * запрещена контрастом 2.96:1). Невыбранный — прозрачный, рамка border-strong,
 * текст ink-dim. Текст metaLarge (13sp), одна строка с многоточием.
 */

@Composable
fun WqSegmentPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Surface(
            onClick = onClick,
            shape = WqTheme.shapes.full,
            color = if (isSystemInDarkTheme()) {
                WqTheme.colors.accent
            } else {
                WqTheme.colors.accentText
            },
            modifier = modifier.heightIn(min = 48.dp),
        ) {
            Text(
                text = label,
                color = if (isSystemInDarkTheme()) {
                    WqTheme.colors.accentInk
                } else {
                    WqTheme.colors.white
                },
                style = WqTheme.typography.metaLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            )
        }
    } else {
        Surface(
            onClick = onClick,
            shape = WqTheme.shapes.full,
            color = androidx.compose.ui.graphics.Color.Transparent,
            border = BorderStroke(1.dp, WqTheme.colors.borderStrong),
            modifier = modifier.heightIn(min = 48.dp),
        ) {
            Text(
                text = label,
                color = WqTheme.colors.inkDim,
                style = WqTheme.typography.metaLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            )
        }
    }
}

@Preview(name = "Светлая", showBackground = true)
@Preview(name = "Тёмная", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WqSegmentPillPreview() {
    WalkQuestTheme {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WqSegmentPill(label = "Статистика", selected = true, onClick = {}, modifier = Modifier.weight(1f))
                WqSegmentPill(label = "Достижения", selected = false, onClick = {}, modifier = Modifier.weight(1f))
                WqSegmentPill(label = "Задания", selected = false, onClick = {}, modifier = Modifier.weight(1f))
            }
        }
    }
}
