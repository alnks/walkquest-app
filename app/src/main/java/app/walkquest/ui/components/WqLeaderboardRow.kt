package app.walkquest.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
 * Строка лидерборда (DESIGN Components: leaderboard-row — «ранг, ник, значение
 * метрики; текущий пользователь подсвечен card-active»; макет
 * remaining-screens.html .lb-row/.me-row, обе темы).
 *
 * Состав по макету: ранг (Nunito 1000, токен typography.rank), ник (metaLarge),
 * значение метрики справа (metaLarge); медалей/аватаров в утверждённом макете
 * нет. Ранг и значение лидера (место 1) и своей строки — accentText, остальные
 * — inkPrimary (сэмплировано с рендера макета, обе темы). Текущий пользователь:
 * [WqCardActive] + чип «вы» (.me-chip).
 *
 * Чип «вы»: тёмная тема — заливка accent + текст accentInk; светлая — белая
 * заливка, рамка 1.5dp и текст accentText (.l .me-chip). Кегль meta (12sp):
 * минимальный кегль приложения (DESIGN Typography) — макетные 9px увеличены.
 *
 * Только токены WqTheme — ни одного hex вне ui/theme.
 */
@Composable
fun WqLeaderboardRow(
    rank: Int,
    nickname: String,
    value: String,
    isCurrentUser: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = isCurrentUser || rank == 1
    val rowContent: @Composable ColumnScope.() -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WqTheme.spacing.md),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = WqTheme.spacing.lg, vertical = WqTheme.spacing.md),
        ) {
            // Фиксированная колонка ранга, как .lb-rank (width 26px в макете) —
            // ники разных строк выровнены друг под другом.
            Text(
                text = rank.toString(),
                style = WqTheme.typography.rank,
                color = if (accent) WqTheme.colors.accentText else WqTheme.colors.inkPrimary,
                modifier = Modifier.width(32.dp),
            )
            // Колонка ника: чип «вы» сразу после ника (макет .me-row
            // «olya_walks [вы]»); значение метрики — у правого края.
            Box(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = nickname,
                        style = WqTheme.typography.metaLarge,
                        color = if (isCurrentUser) WqTheme.colors.inkHeadline else WqTheme.colors.inkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isCurrentUser) {
                        Spacer(modifier = Modifier.width(WqTheme.spacing.sm))
                        WqMeChip()
                    }
                }
            }
            Text(
                text = value,
                style = WqTheme.typography.metaLarge,
                color = if (accent) WqTheme.colors.accentText else WqTheme.colors.inkPrimary,
                maxLines = 1,
            )
        }
    }
    if (isCurrentUser) {
        WqCardActive(modifier = modifier, content = rowContent)
    } else {
        WqCard(modifier = modifier, content = rowContent)
    }
}

/** Чип «вы» на строке текущего пользователя (макет .me-chip). */
@Composable
private fun WqMeChip() {
    val dark = isSystemInDarkTheme()
    Surface(
        shape = WqTheme.shapes.full,
        color = if (dark) WqTheme.colors.accent else WqTheme.colors.surfaceRaised,
        border = if (dark) null else BorderStroke(1.5.dp, WqTheme.colors.accentText),
    ) {
        Text(
            text = "вы",
            style = WqTheme.typography.meta,
            color = if (dark) WqTheme.colors.accentInk else WqTheme.colors.accentText,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Preview(name = "Светлая", showBackground = true)
@Preview(name = "Тёмная", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WqLeaderboardRowPreview() {
    WalkQuestTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WqLeaderboardRow(rank = 1, nickname = "FogLady", value = "41%", isCurrentUser = false)
            WqLeaderboardRow(rank = 2, nickname = "walkspb", value = "38%", isCurrentUser = false)
            WqLeaderboardRow(rank = 7, nickname = "olya_walks", value = "17%", isCurrentUser = true)
        }
    }
}
