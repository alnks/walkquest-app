package app.walkquest.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.walkquest.ui.theme.WalkQuestTheme
import app.walkquest.ui.theme.WqTheme

/**
 * Карточка задания WalkQuest (DESIGN.md Components: card-task, quest-card).
 *
 * Активное задание (card-task): [WqCardActive] + метка «Активное задание» +
 * название + описание + действия («Выполнено» / «Отказаться») — выполнение
 * на честном слове одним нажатием (EXPERIENCE: без принудительных механик).
 * Случайное задание (quest-card): та же карточка крупнее — появляется
 * анимацией «раздачи» (~350мс) вместо кнопки «Вытянуть карту»; действия —
 * «Ещё раз» (пересдача) и «Принять задание» (акцентная CTA в нижней зоне).
 */

@Composable
fun WqQuestCard(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    /** Метка над названием («Активное задание»); null — без метки. */
    badge: String? = null,
    /** true — активное состояние (card-active), false — обычная карточка. */
    active: Boolean = true,
    onClick: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val content: @Composable ColumnScope.() -> Unit = {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            badge?.let {
                Text(
                    text = it,
                    color = WqTheme.colors.inkDim,
                    style = WqTheme.typography.meta,
                )
            }
            Text(
                text = title,
                color = WqTheme.colors.inkHeadline,
                style = if (active) {
                    WqTheme.typography.headingCard
                } else {
                    WqTheme.typography.headingSection
                },
            )
            Text(
                text = description,
                color = WqTheme.colors.inkSecondary,
                style = WqTheme.typography.bodySmall,
            )
            if (actions != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    actions()
                }
            }
        }
    }
    if (active) {
        WqCardActive(modifier = modifier, onClick = onClick, content = content)
    } else {
        WqCard(modifier = modifier, onClick = onClick, content = content)
    }
}

@Preview(name = "Светлая", showBackground = true)
@Preview(name = "Тёмная", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WqQuestCardPreview() {
    WalkQuestTheme {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            WqQuestCard(
                title = "Пять фиолетовых вещей",
                description = "Найдите вокруг пять фиолетовых предметов и отметьте выполнение.",
                badge = "Активное задание",
                actions = {
                    Text(
                        text = "Выполнено",
                        color = WqTheme.colors.accentText,
                        style = WqTheme.typography.metaLarge,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    Text(
                        text = "Отказаться",
                        color = WqTheme.colors.inkSecondary,
                        style = WqTheme.typography.metaLarge,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                },
            )
            WqQuestCard(
                title = "Новое задание раздачи",
                description = "Крупная карточка случайного задания.",
                active = false,
            )
        }
    }
}
