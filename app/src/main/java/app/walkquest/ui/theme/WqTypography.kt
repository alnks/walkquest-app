package app.walkquest.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.walkquest.R

/**
 * Дизайн-токены WalkQuest — типографика (DESIGN.md, блок typography).
 *
 * Роли (контраст характеров DESIGN: спокойный текст против плотных игривых цифр):
 *  - display — Nunito 1000, герой-цифры (процент региона, крупные метрики), 38–58px;
 *  - heading — Nunito 800, заголовки экранов и названия карточек, 16–24px;
 *  - body — гротеск текст (см. замену ниже), весь читаемый текст, 14–16px;
 *  - meta — гротеск 500, подписи/метки/чипы, МИНИМУМ 12px (мельше нельзя);
 *  - celebration — Rubik Bubbles, ТОЛЬКО редкие праздники (закрытый район, юбилей).
 *
 * ЗАМЕНА ШРИФТА ТЕКСТА (зафиксировано 2026-10-12): DESIGN.md назначает body/meta
 * шрифту DM Sans со словами «отлично держит кириллицу» — фактически у DM Sans
 * кириллицы НЕТ (проверено по cmap: google/fonts ofl/dmsans, подтв. issues
 * google/fonts#6193 и практика — весь русский текст уходил бы в системный
 * фолбэк). Ближайший OFL-аналог того же характера (нейтральный геометрический
 * гротеск для UI, отличная кириллица) — Manrope. Замена согласована характером
 * роли body («спокойный, нейтральный, держит кириллицу и длинные слова»), не
 * дизайн-решением; кандидат на пересмотр со стороны дизайна — deferred-work.
 *
 * Все семейства — ресурсы res/font с фолбэком на системный гротеск: если файл
 * повреждён/не читается, приложение остаётся рабочим ([safeFamily]).
 * Лицензии: Nunito/Manrope/Rubik Bubbles — SIL OFL 1.1.
 */
object WqFonts {
    /** Nunito 800 — заголовки. */
    val nunitoHeading: FontFamily = safeFamily(
        Font(R.font.nunito_extrabold, FontWeight.ExtraBold),
    )

    /** Nunito 1000 — герой-цифры (weight=1000 — верхняя граница Compose). */
    val nunitoDisplay: FontFamily = safeFamily(
        Font(R.font.nunito_extrablack, FontWeight(1000)),
    )

    /** Manrope 400/500 — текст и метки (замена DM Sans, см. док-блок выше). */
    val body: FontFamily = safeFamily(
        Font(R.font.manrope_regular, FontWeight.Normal),
        Font(R.font.manrope_medium, FontWeight.Medium),
    )

    /** Rubik Bubbles — только праздники. */
    val celebration: FontFamily = safeFamily(
        Font(R.font.rubik_bubbles_regular, FontWeight.Normal),
    )

    /**
     * FontFamily из res/font с фолбэком на системный гротек при ошибке загрузки
     * файла (требование кодовой системы: приложение не падает из-за шрифта).
     */
    private fun safeFamily(vararg fonts: Font): FontFamily = runCatching {
        FontFamily(*fonts)
    }.getOrElse { FontFamily.SansSerif }
}

/**
 * Семантические текстовые стили WalkQuest. Правила DESIGN:
 * минимальный кегль 12px (labelSmall M3 по умолчанию 11sp — поэтому вся шкала
 * задана явно); подписи нижней навигации = 12px; сегмент-контрол ≥13px.
 * Динамический размер шрифта системы поддерживается стандартно (sp).
 */
@Immutable
class WqTypography(
    /** Герой-цифра крупная (финал прогулки, юбилейные метрики). */
    val displayLarge: TextStyle,
    /** Герой-цифра средняя. */
    val displayMedium: TextStyle,
    /** Герой-цифра компактная (панель, плашка процента). */
    val displaySmall: TextStyle,
    /** Заголовок экрана (22, Nunito 800). */
    val headingScreen: TextStyle,
    /** Заголовок экрана-шага онбординга (24, Nunito 800) — по макету onboarding-duo.html
     * (.ttl 19px в телефоне 262px ≈ 27sp на эталонном устройстве; верх диапазона
     * heading DESIGN «16–24px», макетная крупность сохранена максимально близко). */
    val headingOnboarding: TextStyle,
    /** Герой-цифра компактная (степпер значения цели в онбординге; макет .val Nunito 1000
     * 18px в телефоне 262px ≈ 27sp — ближайшая кегль шкалы display). */
    val displayCompact: TextStyle,
    /** Заголовок секции (18, Nunito 800). */
    val headingSection: TextStyle,
    /** Название карточки (16, Nunito 800). */
    val headingCard: TextStyle,
    /** Ранг строки лидерборда (макет remaining-screens.html .lb-rank: Nunito 1000, 15px). */
    val rank: TextStyle,
    /** Основной текст (16/24). */
    val body: TextStyle,
    /** Вторичный текст (14/20). */
    val bodySmall: TextStyle,
    /** Метки, подписи, чипы (12/16) — МИНИМАЛЬНЫЙ кегль приложения. */
    val meta: TextStyle,
    /** Крупные метки: сегмент-контрол, кнопки-призраки (13/18). */
    val metaLarge: TextStyle,
    /** Праздничный стиль (Rubik Bubbles) — только редкие триумфы. */
    val celebration: TextStyle,
) {
    companion object {
        /** Единая шкала для обеих тем (типографика не меняется между темами). */
        val default: WqTypography
            get() = WqTypography(
                displayLarge = TextStyle(
                    fontFamily = WqFonts.nunitoDisplay,
                    fontWeight = FontWeight(1000),
                    fontSize = 58.sp,
                    lineHeight = 60.sp,
                ),
                displayMedium = TextStyle(
                    fontFamily = WqFonts.nunitoDisplay,
                    fontWeight = FontWeight(1000),
                    fontSize = 48.sp,
                    lineHeight = 50.sp,
                ),
                displaySmall = TextStyle(
                    fontFamily = WqFonts.nunitoDisplay,
                    fontWeight = FontWeight(1000),
                    fontSize = 38.sp,
                    lineHeight = 40.sp,
                ),
                headingScreen = TextStyle(
                    fontFamily = WqFonts.nunitoHeading,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                ),
                headingOnboarding = TextStyle(
                    fontFamily = WqFonts.nunitoHeading,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 24.sp,
                    lineHeight = 30.sp,
                ),
                displayCompact = TextStyle(
                    fontFamily = WqFonts.nunitoDisplay,
                    fontWeight = FontWeight(1000),
                    fontSize = 28.sp,
                    lineHeight = 32.sp,
                ),
                headingSection = TextStyle(
                    fontFamily = WqFonts.nunitoHeading,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                ),
                headingCard = TextStyle(
                    fontFamily = WqFonts.nunitoHeading,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                ),
                rank = TextStyle(
                    fontFamily = WqFonts.nunitoDisplay,
                    fontWeight = FontWeight(1000),
                    fontSize = 18.sp,
                    lineHeight = 22.sp,
                ),
                body = TextStyle(
                    fontFamily = WqFonts.body,
                    fontWeight = FontWeight.Normal,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                ),
                bodySmall = TextStyle(
                    fontFamily = WqFonts.body,
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                ),
                meta = TextStyle(
                    fontFamily = WqFonts.body,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                ),
                metaLarge = TextStyle(
                    fontFamily = WqFonts.body,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                ),
                celebration = TextStyle(
                    fontFamily = WqFonts.celebration,
                    fontWeight = FontWeight.Normal,
                    fontSize = 28.sp,
                    lineHeight = 34.sp,
                ),
            )
    }
}

/** CompositionLocal типографики; провайдит [WqTheme]. */
internal val LocalWqTypography = staticCompositionLocalOf { WqTypography.default }
