package app.walkquest.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.walkquest.ui.theme.WalkQuestIcons
import app.walkquest.ui.theme.WalkQuestTheme
import app.walkquest.ui.theme.WqTheme

/**
 * Круглая иконка-кнопка WalkQuest («FAB», DESIGN: icon-btn прототипа —
 * круг 48dp, заливка surface-raised, рамка border-strong, тень 4dp).
 *
 * Парящие над картой кнопки: «к моей точке», шестерёнка настроек, «+ Задание»,
 * debug-тогл. Hit-area всегда ≥48dp (Accessibility Floor: компактный глиф не
 * означает компактную тач-зону). Без фикса позиции — [enabled] = false,
 * тон ink-dim (Don't: кнопка-ловушка без состояния).
 */

@Composable
fun WqIconFab(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = WqTheme.colors.inkSecondary,
    disabledTint: Color = WqTheme.colors.inkDim,
    size: Dp = 48.dp,
    border: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(size),
        shape = CircleShape,
        color = WqTheme.colors.surfaceRaised,
        border = if (border) BorderStroke(1.dp, WqTheme.colors.borderStrong) else null,
        shadowElevation = 4.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) tint else disabledTint,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Preview(name = "Светлая", showBackground = true)
@Preview(name = "Тёмная", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WqIconFabPreview() {
    WalkQuestTheme {
        Column(modifier = Modifier.size(120.dp)) {
            Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                WqIconFab(
                    icon = WalkQuestIcons.Plus,
                    contentDescription = "Задание на прогулку",
                    onClick = {},
                    tint = WqTheme.colors.accentText,
                )
            }
            Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                WqIconFab(
                    icon = WalkQuestIcons.Gear,
                    contentDescription = "Настройки",
                    onClick = {},
                    enabled = false,
                )
            }
        }
    }
}
