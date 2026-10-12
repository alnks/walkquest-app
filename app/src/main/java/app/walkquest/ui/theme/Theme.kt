package app.walkquest.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Точка входа дизайн-системы WalkQuest: `WalkQuestTheme { ... }`.
 *
 * Провайдит семантические токены (цвета/типографику/скругления/отступы) через
 * CompositionLocal-ы и заодно собирает MaterialTheme из тех же токенов — чтобы
 * внутренности M3-компонентов (диалоги, поля, переключатели) не скакали на
 * дефолтной палитре/шрифтах. Экраны обязаны брать значения из [WqTheme], а не
 * из MaterialTheme напрямую: MaterialTheme — мост для M3-инфраструктуры.
 *
 * Источник истины — _bmad-output/initiative-walkquest/ux-walkquest/DESIGN.md;
 * три слоя токенов: WqPrimitive (hex) → WqColors/WqTypography/WqShapes/WqSpacing
 * (роли) → спецификации компонентов (ui/components/). Deprecated-шимы эпохи
 * прототипа (AccentTextColor и т.п., WalkQuestPrimaryButton) удалены в Шаге 4:
 * весь UI на WqTheme + ui/components.
 */
@Composable
fun WalkQuestTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) WqColors.dark() else WqColors.light()
    val typography = WqTypography.default
    val shapes = WqShapes.default
    val spacing = WqSpacing.default

    CompositionLocalProvider(
        LocalWqColors provides colors,
        LocalWqTypography provides typography,
        LocalWqShapes provides shapes,
        LocalWqSpacing provides spacing,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) wqDarkColorScheme else wqLightColorScheme,
            typography = wqMaterialTypography,
            shapes = wqMaterialShapes,
            content = content,
        )
    }
}

/** Доступ к токенам дизайн-системы из любого экрана/компонента. */
object WqTheme {
    val colors: WqColors
        @Composable @ReadOnlyComposable get() = LocalWqColors.current

    val typography: WqTypography
        @Composable @ReadOnlyComposable get() = LocalWqTypography.current

    val shapes: WqShapes
        @Composable @ReadOnlyComposable get() = LocalWqShapes.current

    val spacing: WqSpacing
        @Composable @ReadOnlyComposable get() = LocalWqSpacing.current
}

// --- Мост в Material 3: собираем colorScheme/typography/shapes из тех же токенов ---

private val wqLightColorScheme = lightColorScheme(
    primary = Color(WqPrimitive.ACCENT_LIGHT),
    onPrimary = Color(WqPrimitive.ACCENT_INK_LIGHT),
    primaryContainer = Color(WqPrimitive.ACCENT_LIGHT).copy(alpha = 0.08f),
    onPrimaryContainer = Color(WqPrimitive.ACCENT_TEXT_LIGHT),
    secondary = Color(WqPrimitive.BORDER_STRONG_LIGHT),
    onSecondary = Color(WqPrimitive.SURFACE_RAISED_LIGHT),
    background = Color(WqPrimitive.SURFACE_BASE_LIGHT),
    onBackground = Color(WqPrimitive.INK_PRIMARY_LIGHT),
    surface = Color(WqPrimitive.SURFACE_RAISED_LIGHT),
    onSurface = Color(WqPrimitive.INK_PRIMARY_LIGHT),
    surfaceVariant = Color(WqPrimitive.SURFACE_SUNKEN_LIGHT),
    onSurfaceVariant = Color(WqPrimitive.INK_SECONDARY_LIGHT),
    surfaceContainer = Color(WqPrimitive.SURFACE_NAV_LIGHT),
    outline = Color(WqPrimitive.BORDER_HAIRLINE_LIGHT),
    outlineVariant = Color(WqPrimitive.BORDER_HAIRLINE_LIGHT),
    error = Color(WqPrimitive.ERROR_LIGHT),
    inverseSurface = Color(WqPrimitive.INK_PRIMARY_LIGHT),
    inverseOnSurface = Color(WqPrimitive.SURFACE_BASE_LIGHT),
    inversePrimary = Color(WqPrimitive.ACCENT_TEXT_LIGHT),
)

private val wqDarkColorScheme = darkColorScheme(
    primary = Color(WqPrimitive.ACCENT_DARK),
    onPrimary = Color(WqPrimitive.ACCENT_INK_DARK),
    primaryContainer = Color(WqPrimitive.ACCENT_DARK).copy(alpha = 0.08f),
    onPrimaryContainer = Color(WqPrimitive.ACCENT_DARK),
    secondary = Color(WqPrimitive.BORDER_STRONG_DARK),
    onSecondary = Color(WqPrimitive.SURFACE_RAISED_DARK),
    background = Color(WqPrimitive.SURFACE_BASE_DARK),
    onBackground = Color(WqPrimitive.INK_PRIMARY_DARK),
    surface = Color(WqPrimitive.SURFACE_RAISED_DARK),
    onSurface = Color(WqPrimitive.INK_PRIMARY_DARK),
    surfaceVariant = Color(WqPrimitive.SURFACE_SUNKEN_DARK),
    onSurfaceVariant = Color(WqPrimitive.INK_SECONDARY_DARK),
    surfaceContainer = Color(WqPrimitive.SURFACE_NAV_DARK),
    outline = Color(WqPrimitive.BORDER_HAIRLINE_DARK),
    outlineVariant = Color(WqPrimitive.BORDER_HAIRLINE_DARK),
    error = Color(WqPrimitive.ERROR_DARK),
    inverseSurface = Color(WqPrimitive.INK_PRIMARY_DARK),
    inverseOnSurface = Color(WqPrimitive.SURFACE_BASE_DARK),
    inversePrimary = Color(WqPrimitive.ACCENT_DARK),
)

/**
 * Типографика M3-инфраструктуры из токенов WalkQuest: весь M3-кегль ≥12sp
 * (M3 по умолчанию тянет labelSmall 11sp — DESIGN запрещает кегль мельче 12px).
 */
private val wqMaterialTypography = Typography(
    displayLarge = TextStyle(fontFamily = WqFonts.nunitoDisplay, fontWeight = FontWeight(1000), fontSize = 57.sp, lineHeight = 64.sp),
    displayMedium = TextStyle(fontFamily = WqFonts.nunitoDisplay, fontWeight = FontWeight(1000), fontSize = 45.sp, lineHeight = 52.sp),
    displaySmall = TextStyle(fontFamily = WqFonts.nunitoDisplay, fontWeight = FontWeight(1000), fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = WqFonts.nunitoHeading, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = WqFonts.nunitoHeading, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = WqFonts.nunitoHeading, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = WqFonts.nunitoHeading, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = WqFonts.nunitoHeading, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = WqFonts.nunitoHeading, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = WqFonts.body, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = WqFonts.body, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = WqFonts.body, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = WqFonts.body, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = WqFonts.body, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = WqFonts.body, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)

/** Скругления M3-инфраструктуры из токенов (8/12/16/20, пилюли — percent 50). */
private val wqMaterialShapes = Shapes(
    extraSmall = WqShapes.default.small,
    small = WqShapes.default.medium,
    medium = WqShapes.default.large,
    large = WqShapes.default.extraLarge,
    extraLarge = WqShapes.default.extraLarge,
)
