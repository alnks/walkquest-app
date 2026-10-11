package app.walkquest.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Токены — _bmad-output/initiative-walkquest/ux-walkquest/DESIGN.md (colors, строки 8–38).
private val LightColors = lightColorScheme(
    primary = Color(0xFF7A9A1F), // accent (только графика, не текст — DESIGN)
    onPrimary = Color(0xFFFFFFFF), // accent-ink
    background = Color(0xFFF6F3FA), // surface-base
    onBackground = Color(0xFF241C33), // ink-primary
    surface = Color(0xFFFFFFFF), // surface-raised
    onSurface = Color(0xFF241C33), // ink-primary
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC6E26B), // accent-dark
    onPrimary = Color(0xFF22290A), // accent-ink-dark
    background = Color(0xFF191423), // surface-base-dark
    onBackground = Color(0xFFEFEAF7), // ink-primary-dark
    surface = Color(0xFF231B33), // surface-raised-dark
    onSurface = Color(0xFFEFEAF7), // ink-primary-dark
)

/** Лавандовый туман — фирменный цвет (токен fog), одинаков в обеих темах. */
val FogColor = Color(0xFFB29ED1)

/**
 * Цвет закрытого тумана на карте (разбор владельца 2026-10-05, дефект 1):
 * туман должен СКРЫВАТЬ базовую карту, а не полупрозрачной вуалью читаться
 * сквозь улицы. Слой рисуется непрозрачным (FOG_OPACITY 1.0).
 * «Свеча v3» (разбор владельца 2026-10-06 по скриншотам fix8): композит
 * прототипа #CBBEE1 сливался с бледно-лавандовой базой карты (#E3DCF0 и др.) —
 * открытая тропа почти не отличалась от завесы. Светлая завеса — чистый
 * фирменный токен fog #B29ED1 полной непрозрачности: плотнее и темнее базы,
 * открытая карта читается светлым просветом (контраст по яркости ~1,8:1 +
 * насыщенность). Тёмная #514568 (композит прототипа rgba(178,158,209,0.30)
 * поверх map-base-dark) не менялась — там контраст с тёмной базой уже хороший.
 */
val FogCoveredColor: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF514568) else Color(0xFFB29ED1)

/**
 * Свечение вокруг просветов тумана («свеча в тёмной комнате», разбор владельца
 * 2026-10-05 + 2026-10-06: убрать зелено-жёлтый оттенок бус fix7 — цвет в
 * сторону светлого тёплого, почти белого, в духе «цвета открытой карты»):
 * blur-полоса вдоль тропы и blur-линия по кромке воды.
 * Светлая #FBF7EF (тёплый почти белый поверх лавандовой завесы),
 * тёмная #6B5A88 (светлее закрытого тумана #514568 — свечение светит,
 * а не затеняет).
 */
val FogGlowColor: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF6B5A88) else Color(0xFFFBF7EF)

/**
 * Маркер POI-маяка (токен poi-marker: «точка с мягким свечением», background accent-dark):
 * dark #C6E26B (accent-dark), light #7A9A1F (accent — графика, не текст, DESIGN разрешает).
 */
val PoiMarkerColor: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFFC6E26B) else Color(0xFF7A9A1F)

/**
 * Акцент для значений метрик текстом (DESIGN: «значения метрик» акцентом):
 * dark #C6E26B (accent-dark), light #587110 (accent-text — заливка #7A9A1F текстом запрещена).
 */
val AccentTextColor: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFFC6E26B) else Color(0xFF587110)

/**
 * Приглушённый лавандовый для процентов чужих (не домашних) регионов
 * (макет remaining-screens.html «Регионы»: .rg-pct #B3A6C9 тёмная / #8D83A6 светлая).
 */
val MutedLavenderTextColor: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFFB3A6C9) else Color(0xFF8D83A6)

/** Заголовки (токен ink-headline): светлая — смягчённый сливовый #4A3F66, тёмная — ink-primary. */
val HeadlineColor: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFFEFEAF7) else Color(0xFF4A3F66)

/** Вторичный текст (токен ink-secondary): #B3A6C9 тёмная / #5C506E светлая. */
val InkSecondaryColor: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFFB3A6C9) else Color(0xFF5C506E)

/**
 * Приглушённые подписи (токен ink-dim): #8D83A6 тёмная / #6E6384 светлая.
 * Минимум 12px и контраст ≥4.5:1 — пол замены «onSurface с alpha».
 */
val DimTextColor: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF8D83A6) else Color(0xFF6E6384)

/** Нижняя навигация (токен surface-nav): #141020 тёмная / #FBF9FD светлая. */
val NavSurfaceColor: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF141020) else Color(0xFFFBF9FD)

/** Рамка ghost-кнопок (токен border-strong): #7564A6 тёмная / #9A86C4 светлая. */
val BorderStrongColor: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF7564A6) else Color(0xFF9A86C4)

/**
 * Кольцо маркера «я» на карте (токен cursor-dark #9379E0; в светлой — #7A5FC0,
 * значение из прототипа .app.light --cursor: отдельного токена cursor-light в DESIGN нет).
 */
val MeCursorColor: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF9379E0) else Color(0xFF7A5FC0)

@Composable
fun WalkQuestTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

/**
 * Главная кнопка экрана (токен button-primary, DESIGN «Do's and Don'ts»):
 * тёмная тема — заливка accent-dark + текст accent-ink-dark; светлая — «призрак»:
 * белая заливка, рамка 1.5dp и текст accent-text #587110 (залитая зелёная кнопка
 * в светлой теме запрещена: белый текст на #7A9A1F не читается). Высота ≥48dp,
 * радиус rounded.lg. Единственная акцентная кнопка на экране.
 */
@Composable
fun WalkQuestPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    if (isSystemInDarkTheme()) {
        Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp),
            enabled = enabled,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            content = content,
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp),
            enabled = enabled,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.5.dp, AccentTextColor),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = AccentTextColor,
            ),
            content = content,
        )
    }
}

/**
 * Вторичная кнопка (токен button-ghost): прозрачная, рамка border-strong,
 * текст ink-secondary, радиус rounded.full. Высота ≥48dp.
 */
@Composable
fun WalkQuestGhostButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(percent = 50),
        border = BorderStroke(1.5.dp, BorderStrongColor),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = InkSecondaryColor,
        ),
        content = content,
    )
}
