package app.walkquest.ui.components

import android.content.res.Configuration
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.walkquest.ui.theme.WalkQuestIcons
import app.walkquest.ui.theme.WalkQuestTheme
import app.walkquest.ui.theme.WqTheme

/**
 * Тогл режима WalkQuest (DESIGN.md Components: toggle-mode).
 *
 * Только значки без текста (пешком / вело); активная сторона — значок на
 * белом кружке с кольцом акцента (Do: «значок на белом кружке поверх акцента»;
 * Don't: тёмный глиф на акцентной заливке — сливается). При переключении —
 * микро-анимация «всадника»: белый кружок пружинит к выбранной стороне
 * (обучающая анимация правилу скорости, EXPERIENCE; Reduce Motion — Compose
 * пружина всё равно короткая, длинной только «всадник» 2.4с, который здесь
 * не реализован — см. deferred-work).
 * TalkBack: contentDescription каждой стороны — «Режим: пешком / вело».
 */

/** Сторона тогла: иконка + подпись для TalkBack. */
data class WqModeToggleEntry(
    val icon: ImageVector,
    val contentDescription: String,
)

@Composable
fun WqModeToggle(
    entries: List<WqModeToggleEntry>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val innerSize = 40.dp
    Surface(
        modifier = modifier,
        shape = WqTheme.shapes.full,
        color = WqTheme.colors.surfaceRaised,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
    ) {
        Box(modifier = Modifier.padding(4.dp)) {
            // «Всадник»: белый кружок с кольцом акцента, пружинит к выбранной стороне.
            if (selectedIndex in entries.indices) {
                val riderOffset by animateDpAsState(
                    targetValue = innerSize * selectedIndex,
                    animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
                    label = "mode-rider",
                )
                Surface(
                    shape = CircleShape,
                    color = WqTheme.colors.white,
                    border = BorderStroke(1.5.dp, WqTheme.colors.accentText),
                    modifier = Modifier
                        .offset(x = riderOffset)
                        .size(innerSize),
                ) {}
            }
            Row {
                entries.forEachIndexed { index, entry ->
                    val selected = index == selectedIndex
                    Surface(
                        onClick = { onSelect(index) },
                        shape = CircleShape,
                        color = androidx.compose.ui.graphics.Color.Transparent,
                        modifier = Modifier.size(innerSize),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = entry.icon,
                                contentDescription = entry.contentDescription,
                                tint = if (selected) {
                                    WqTheme.colors.accentText
                                } else {
                                    WqTheme.colors.inkDim
                                },
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(name = "Светлая", showBackground = true)
@Preview(name = "Тёмная", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WqModeTogglePreview() {
    WalkQuestTheme {
        Row(modifier = Modifier.padding(16.dp)) {
            WqModeToggle(
                entries = listOf(
                    WqModeToggleEntry(WalkQuestIcons.Walk, "Режим: пешком"),
                    WqModeToggleEntry(WalkQuestIcons.Bike, "Режим: вело"),
                ),
                selectedIndex = 0,
                onSelect = {},
            )
            WqModeToggle(
                entries = listOf(
                    WqModeToggleEntry(WalkQuestIcons.Walk, "Режим: пешком"),
                    WqModeToggleEntry(WalkQuestIcons.Bike, "Режим: вело"),
                ),
                selectedIndex = 1,
                onSelect = {},
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}
