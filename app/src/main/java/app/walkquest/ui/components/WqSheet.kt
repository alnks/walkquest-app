package app.walkquest.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.walkquest.ui.theme.WalkQuestTheme
import app.walkquest.ui.theme.WqTheme

/**
 * Статичная нижняя панель WalkQuest (DESIGN.md Components: sheet).
 *
 * Главный экран и экран прогулки: статистика + планировщик + CTA (или
 * агрегаты прогулки + управление записью). Панель СТАТИЧНА — не выдвигается,
 * ручки-grab нет; подробности живут во вкладках (Don't: дублирующая вкладки
 * выдвижная панель). Фон — surface-base, скругление только сверху 20 (xl),
 * тень 8dp — парит над картой (Elevation: тени только у элементов «над землёй»).
 * Скруглённые углы прозрачны до карты, а не до фона приложения.
 */
@Composable
fun WqSheet(
    modifier: Modifier = Modifier,
    shadowElevation: Dp = 8.dp,
    contentPadding: PaddingValues = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = WqTheme.shapes.sheet,
        color = WqTheme.colors.surfaceBase,
        shadowElevation = shadowElevation,
    ) {
        Column(modifier = Modifier.padding(contentPadding)) {
            content()
        }
    }
}

@Preview(name = "Светлая", showBackground = true, backgroundColor = 0xFFB29ED1)
@Preview(name = "Тёмная", showBackground = true, backgroundColor = 0xFF514568, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WqSheetPreview() {
    WalkQuestTheme {
        WqSheet {
            androidx.compose.material3.Text(
                text = "Панель: статистика · планировщик · CTA",
                color = WqTheme.colors.inkSecondary,
                style = WqTheme.typography.bodySmall,
            )
        }
    }
}
