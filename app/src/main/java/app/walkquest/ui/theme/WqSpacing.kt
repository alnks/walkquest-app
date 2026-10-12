package app.walkquest.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Дизайн-токены WalkQuest — отступы (DESIGN.md spacing: 4/8/12/16/24/32).
 * Поля экранов — одна колонка, 16dp по бокам (DESIGN Layout & Spacing).
 */
@Immutable
class WqSpacing(
    /** 4 — микро-зазоры. */
    val xs: Dp = 4.dp,
    /** 8 — зазор между элементами (минимум между тач-зонами 8dp). */
    val sm: Dp = 8.dp,
    /** 12 — внутренние отступы карточек. */
    val md: Dp = 12.dp,
    /** 16 — поля экрана, стандартный внешний отступ. */
    val lg: Dp = 16.dp,
    /** 24 — крупные блоки, экраны-центровки. */
    val xl: Dp = 24.dp,
    /** 32 — разделение смысловых секций. */
    val xxl: Dp = 32.dp,
) {
    companion object {
        val default = WqSpacing()
    }
}

/** CompositionLocal отступов; провайдит [WqTheme]. */
internal val LocalWqSpacing = staticCompositionLocalOf { WqSpacing.default }
