package app.walkquest.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Дизайн-токены WalkQuest — цвета (DESIGN.md, блок colors, строки 8–38).
 *
 * Трёхслойная архитектура (primitive → semantic → component):
 * 1. [WqPrimitive] — сырые hex из DESIGN.md, по одному на роль и тему.
 * 2. [WqColors] — семантические роли (surface/ink/accent/fog/border + роли карты).
 *    Все экраны и компоненты берут цвета ТОЛЬКО отсюда — ни одного hex вне
 *    файла токенов (дженерик-системы типа MaterialTheme.colorScheme в экранах
 *    не используются).
 * 3. Производные роли компонентов (accentSoft/accentSoftBorder — card-active
 *    «accent @ 8% / @ 35% alpha») вычисляются из семантики в фабриках тем.
 *
 * Дополнительные роли карты (fogCovered/fogGlow/cursor), не названные в YAML-
 * шапке DESIGN.md, но утверждённые разбором владельца 2026-10-05/06 — здесь же,
 * чтобы MapScreen не держал собственных hex:
 *  - fogCovered: цвет закрытого тумана (светлая — чистый fog; тёмная — композит
 *    fog@30% поверх map-base-dark #514568, «свеча v3»);
 *  - fogGlow: свечение просветов (светлая #FBF7EF; тёмная #6B5A88);
 *  - cursor: кольцо маркера «я» (токен cursor-dark #9379E0; светлая #7A5FC0
 *    из прототипа — отдельного токена cursor-light в DESIGN нет).
 * Ошибка: в DESIGN нет — взяты M3-дефолты как примитивы (запрещено ломать
 * существующее поведение диалогов/полей).
 */
object WqPrimitive {
    // Светлая тема.
    const val SURFACE_BASE_LIGHT = 0xFFF6F3FA
    const val SURFACE_RAISED_LIGHT = 0xFFFFFFFF
    const val SURFACE_SUNKEN_LIGHT = 0xFFEFEAF6
    const val SURFACE_NAV_LIGHT = 0xFFFBF9FD
    const val MAP_BASE_LIGHT = 0xFFE3DCF0
    const val MAP_BASE_2_LIGHT = 0xFFD5CBE8
    const val FOG_LIGHT = 0xFFB29ED1
    const val INK_PRIMARY_LIGHT = 0xFF241C33
    const val INK_HEADLINE_LIGHT = 0xFF4A3F66
    const val INK_SECONDARY_LIGHT = 0xFF5C506E
    const val INK_DIM_LIGHT = 0xFF6E6384
    const val ACCENT_LIGHT = 0xFF7A9A1F // только графика, не текст (DESIGN Colors)
    const val ACCENT_INK_LIGHT = 0xFFFFFFFF
    const val ACCENT_TEXT_LIGHT = 0xFF587110 // акцент текстом в светлой (5.05:1)
    const val BORDER_HAIRLINE_LIGHT = 0xFFE6DFF2
    const val BORDER_STRONG_LIGHT = 0xFF9A86C4

    // Тёмная тема.
    const val SURFACE_BASE_DARK = 0xFF191423
    const val SURFACE_RAISED_DARK = 0xFF231B33
    const val SURFACE_SUNKEN_DARK = 0xFF2C2248
    const val SURFACE_NAV_DARK = 0xFF141020
    const val MAP_BASE_DARK = 0xFF241C36
    const val MAP_BASE_2_DARK = 0xFF2C2240
    const val FOG_DARK = 0xFFB29ED1 // тот же фирменный туман
    const val INK_PRIMARY_DARK = 0xFFEFEAF7
    const val INK_SECONDARY_DARK = 0xFFB3A6C9
    const val INK_DIM_DARK = 0xFF8D83A6
    const val ACCENT_DARK = 0xFFC6E26B
    const val ACCENT_INK_DARK = 0xFF22290A
    const val BORDER_HAIRLINE_DARK = 0xFF342A4D
    const val BORDER_STRONG_DARK = 0xFF7564A6
    const val CURSOR_DARK = 0xFF9379E0

    // Карта (разбор владельца 2026-10-05/06; см. док-блок выше).
    const val FOG_COVERED_DARK = 0xFF514568
    const val FOG_GLOW_LIGHT = 0xFFFBF7EF
    const val FOG_GLOW_DARK = 0xFF6B5A88
    const val CURSOR_LIGHT = 0xFF7A5FC0 // из прототипа (токена cursor-light в DESIGN нет)

    // Error — M3-дефолты как примитивы (в DESIGN.md не задан).
    const val ERROR_LIGHT = 0xFFB3261E
    const val ERROR_DARK = 0xFFF2B8B5
}

/**
 * Семантические цветовые роли WalkQuest. Неизменяемый снимок: экраны читают
 * роли через [WqTheme.colors], провайдится [WqTheme]-ом (см. Theme.kt).
 */
@Immutable
class WqColors(
    /** Базовый фон экранов (sheet панели, скроллящиеся вкладки). */
    val surfaceBase: Color,
    /** Приподнятые поверхности: карточки, плашки, FAB. */
    val surfaceRaised: Color,
    /** Утопленные поверхности (тональная глубина без теней). */
    val surfaceSunken: Color,
    /** Фон нижней навигации. */
    val surfaceNav: Color,
    /** База карты — всегда чуть светлее фона приложения. */
    val mapBase: Color,
    /** Вторая ступень базы карты. */
    val mapBase2: Color,
    /** Фирменный лавандовый туман — только слой карты и бренд-элементы. */
    val fog: Color,
    /** Основной текст. */
    val inkPrimary: Color,
    /** Заголовки (светлая — смягчённый сливовый; тёмная — ink-primary). */
    val inkHeadline: Color,
    /** Вторичный текст. */
    val inkSecondary: Color,
    /** Приглушённые подписи (dim: контраст ≥4.5:1 обе темы). */
    val inkDim: Color,
    /** Акцент: действие и прогресс (светлая — только графика). */
    val accent: Color,
    /** Текст/значки поверх акцентной заливки. */
    val accentInk: Color,
    /** Акцент текстом (светлая — только #587110; тёмная — accent-dark). */
    val accentText: Color,
    /** Тонкая рамка карточек. */
    val borderHairline: Color,
    /** Сильная рамка ghost-кнопок и FAB. */
    val borderStrong: Color,
    /** Кольцо маркера «я» на карте. */
    val cursor: Color,
    /** Ошибки (диалоги, поля) — M3-дефолты. */
    val error: Color,
    /** Цвет закрытого тумана (непрозрачный слой поверх карты). */
    val fogCovered: Color,
    /** Свечение просветов тумана вдоль тропы. */
    val fogGlow: Color,
    /** card-active: заливка accent @ 8% alpha (резолвится от accent темы). */
    val accentSoft: Color,
    /** card-active: рамка accent @ 35% alpha (резолвится от accent темы). */
    val accentSoftBorder: Color,
) {
    /** POI-маяк — акцентом (графика; DESIGN разрешает accent в светлой). */
    val poiMarker: Color get() = accent
    /** Белое ядро маркера «я» и колец — общий для тем. */
    val white: Color get() = Color.White

    companion object {
        /** Светлая тема (полноценная вторая, не инверсия тёмной). */
        fun light(): WqColors = WqColors(
            surfaceBase = Color(WqPrimitive.SURFACE_BASE_LIGHT),
            surfaceRaised = Color(WqPrimitive.SURFACE_RAISED_LIGHT),
            surfaceSunken = Color(WqPrimitive.SURFACE_SUNKEN_LIGHT),
            surfaceNav = Color(WqPrimitive.SURFACE_NAV_LIGHT),
            mapBase = Color(WqPrimitive.MAP_BASE_LIGHT),
            mapBase2 = Color(WqPrimitive.MAP_BASE_2_LIGHT),
            fog = Color(WqPrimitive.FOG_LIGHT),
            inkPrimary = Color(WqPrimitive.INK_PRIMARY_LIGHT),
            inkHeadline = Color(WqPrimitive.INK_HEADLINE_LIGHT),
            inkSecondary = Color(WqPrimitive.INK_SECONDARY_LIGHT),
            inkDim = Color(WqPrimitive.INK_DIM_LIGHT),
            accent = Color(WqPrimitive.ACCENT_LIGHT),
            accentInk = Color(WqPrimitive.ACCENT_INK_LIGHT),
            accentText = Color(WqPrimitive.ACCENT_TEXT_LIGHT),
            borderHairline = Color(WqPrimitive.BORDER_HAIRLINE_LIGHT),
            borderStrong = Color(WqPrimitive.BORDER_STRONG_LIGHT),
            cursor = Color(WqPrimitive.CURSOR_LIGHT),
            error = Color(WqPrimitive.ERROR_LIGHT),
            fogCovered = Color(WqPrimitive.FOG_LIGHT), // «свеча v3»: чистый токен fog
            fogGlow = Color(WqPrimitive.FOG_GLOW_LIGHT),
            accentSoft = Color(WqPrimitive.ACCENT_LIGHT).copy(alpha = 0.08f),
            accentSoftBorder = Color(WqPrimitive.ACCENT_LIGHT).copy(alpha = 0.35f),
        )

        /** Тёмная тема — основная (dark-first). */
        fun dark(): WqColors = WqColors(
            surfaceBase = Color(WqPrimitive.SURFACE_BASE_DARK),
            surfaceRaised = Color(WqPrimitive.SURFACE_RAISED_DARK),
            surfaceSunken = Color(WqPrimitive.SURFACE_SUNKEN_DARK),
            surfaceNav = Color(WqPrimitive.SURFACE_NAV_DARK),
            mapBase = Color(WqPrimitive.MAP_BASE_DARK),
            mapBase2 = Color(WqPrimitive.MAP_BASE_2_DARK),
            fog = Color(WqPrimitive.FOG_DARK),
            inkPrimary = Color(WqPrimitive.INK_PRIMARY_DARK),
            inkHeadline = Color(WqPrimitive.INK_PRIMARY_DARK),
            inkSecondary = Color(WqPrimitive.INK_SECONDARY_DARK),
            inkDim = Color(WqPrimitive.INK_DIM_DARK),
            accent = Color(WqPrimitive.ACCENT_DARK),
            accentInk = Color(WqPrimitive.ACCENT_INK_DARK),
            accentText = Color(WqPrimitive.ACCENT_DARK),
            borderHairline = Color(WqPrimitive.BORDER_HAIRLINE_DARK),
            borderStrong = Color(WqPrimitive.BORDER_STRONG_DARK),
            cursor = Color(WqPrimitive.CURSOR_DARK),
            error = Color(WqPrimitive.ERROR_DARK),
            fogCovered = Color(WqPrimitive.FOG_COVERED_DARK),
            fogGlow = Color(WqPrimitive.FOG_GLOW_DARK),
            accentSoft = Color(WqPrimitive.ACCENT_DARK).copy(alpha = 0.08f),
            accentSoftBorder = Color(WqPrimitive.ACCENT_DARK).copy(alpha = 0.35f),
        )
    }
}

/** CompositionLocal семантических цветов; провайдит [WqTheme]. */
internal val LocalWqColors = staticCompositionLocalOf { WqColors.dark() }
