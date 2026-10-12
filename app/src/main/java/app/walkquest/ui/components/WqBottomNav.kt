package app.walkquest.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.walkquest.ui.theme.WalkQuestIcons
import app.walkquest.ui.theme.WalkQuestTheme
import app.walkquest.ui.theme.WqTheme

/**
 * Нижняя навигация WalkQuest (DESIGN.md Components: bottom-nav).
 *
 * 4 вкладки: Карта · Прогресс · Рейтинг · Профиль. Иконка + подпись; активная
 * вкладка — акцентом (акцент текстом: светлая #587110, тёмная accent-dark),
 * неактивные — ink-dim. Подпись 12sp (meta, минимальный кегль) в одну строку
 * без переносов. Фон — surface-nav. Экран прогулки полностью управляем
 * большим пальцем — навигация внизу, недостижимая зона сверху пустая.
 */

/** Одна вкладка нижней навигации. */
data class WqNavItem(
    val label: String,
    val icon: ImageVector,
    val contentDescription: String = label,
)

@Composable
fun WqBottomNav(
    items: List<WqNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier,
        containerColor = WqTheme.colors.surfaceNav,
    ) {
        items.forEachIndexed { index, item ->
            NavigationBarItem(
                selected = selectedIndex == index,
                onClick = { onSelect(index) },
                icon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.contentDescription,
                        modifier = Modifier.size(24.dp),
                    )
                },
                label = {
                    Text(
                        text = item.label,
                        style = WqTheme.typography.meta,
                        maxLines = 1,
                        softWrap = false,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = WqTheme.colors.accentText,
                    selectedTextColor = WqTheme.colors.accentText,
                    unselectedIconColor = WqTheme.colors.inkDim,
                    unselectedTextColor = WqTheme.colors.inkDim,
                ),
            )
        }
    }
}

@Preview(name = "Светлая", showBackground = true)
@Preview(name = "Тёмная", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WqBottomNavPreview() {
    WalkQuestTheme {
        Row(modifier = Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WqBottomNav(
                items = listOf(
                    WqNavItem("Карта", WalkQuestIcons.Walk),
                    WqNavItem("Прогресс", WalkQuestIcons.Plus),
                    WqNavItem("Рейтинг", WalkQuestIcons.Gear),
                    WqNavItem("Профиль", WalkQuestIcons.Bike),
                ),
                selectedIndex = 0,
                onSelect = {},
                modifier = Modifier.weight(1f),
            )
        }
    }
}
