package app.walkquest.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * Дизайн-токены WalkQuest — скругления (DESIGN.md rounded: 8/12/16/20/9999).
 * Полная пилюля в Compose — RoundedCornerShape(percent = 50), эквивалент 9999px.
 */
@Immutable
class WqShapes(
    /** sm — мелкие плашки, чипы внутри карточек. */
    val small: RoundedCornerShape = RoundedCornerShape(8.dp),
    /** md — карточки (card, card-task). */
    val medium: RoundedCornerShape = RoundedCornerShape(12.dp),
    /** lg — главная кнопка, карточка POI. */
    val large: RoundedCornerShape = RoundedCornerShape(16.dp),
    /** xl — верхние углы нижней панели (sheet). */
    val extraLarge: RoundedCornerShape = RoundedCornerShape(20.dp),
    /** full — чипы, тоглы, ghost-кнопки, FAB. */
    val full: RoundedCornerShape = RoundedCornerShape(percent = 50),
) {
    /** Скругление только сверху для статичной нижней панели (sheet, DESIGN). */
    val sheet: RoundedCornerShape get() = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)

    companion object {
        val default = WqShapes()
    }
}

/** CompositionLocal скруглений; провайдит [WqTheme]. */
internal val LocalWqShapes = staticCompositionLocalOf { WqShapes.default }
