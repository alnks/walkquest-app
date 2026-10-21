package app.walkquest.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Общие векторные иконки приложения (DESIGN: «векторные иконки с подписями»,
 * эмодзи — только плейсхолдеры макетов). Рисуются 24dp-путями в стиле material
 * icons: tint задаёт вызывающий компонент. Тон задаёт NavigationBarItem / Icon.
 */
object WalkQuestIcons {

    /** Пешеход (режим «пешком»). */
    val Walk: ImageVector = pathIcon(
        "ic-walk",
        "M13.5 5.5c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zM9.8 8.9L7 23h2.1l1.8-8 " +
            "2.1 2v6h2v-7.5l-2.1-2 .6-3c1.1 1.3 3.1 2.3 5.1 2.3v-2c-1.9 0-3.5-1-4.3-2.4l-1-1.6c-.4-.6-1-1-1.7-1-.3 0-.5.1-.8.1L6 " +
            "8.3V13h2V9.6l1.8-.7z",
    )

    /** Велосипед (режим «вело»). */
    val Bike: ImageVector = pathIcon(
        "ic-bike",
        "M15.5 5.5c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zM5 12c-2.8 0-5 2.2-5 5s2.2 5 5 5 5-2.2 " +
            "5-5-2.2-5-5-5zm0 8.5c-1.9 0-3.5-1.6-3.5-3.5s1.6-3.5 3.5-3.5 3.5 1.6 3.5 3.5-1.6 3.5-3.5 3.5zm5.8-10l2.4-2.4.8.8c1.3 " +
            "1.3 3 2.1 4.9 2.1V6c-1.4 0-2.6-.6-3.5-1.5L14.1 3.2c-.4-.4-1-.6-1.5-.6s-1.1.2-1.4.6L7.8 8.4c-.3.4-.5.9-.5 1.4 0 .8.3 " +
            "1.5.8 2L11 14v5h2v-6.2l-2.1-2.1 1.9-1.9c.5.6 1.2 1.5 2.2 2.2V14c1.2.5 2.3 1.3 3.1 2.4l1.2 1.6c.2.3.5.4.9.4.3 0 " +
            ".6-.1.8-.3l2.4-1.9c.3-.2.4-.5.4-.8 0-.4-.2-.7-.5-.9l-2.1-1.1c-.5-.3-1.1-.4-1.6-.1l-1.3.6-1.6-2.2c-1-1.4-2.6-2.2-4.3-2.2h-.8zM19 " +
            "12c-2.8 0-5 2.2-5 5s2.2 5 5 5 5-2.2 5-5-2.2-5-5-5zm0 8.5c-1.9 0-3.5-1.6-3.5-3.5s1.6-3.5 3.5-3.5 3.5 1.6 3.5 " +
            "3.5-1.6 3.5-3.5 3.5z",
    )

    /** Шестерёнка настроек (DESIGN: рисуется вектором — эмодзи ⚙ читался как метка локации). */
    val Gear: ImageVector = pathIcon(
        "ic-gear",
        "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 " +
            "0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 " +
            "1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 " +
            "1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 " +
            "3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z",
    )

    /** Плюс («+ Задание», планировщик). */
    val Plus: ImageVector = pathIcon(
        "ic-plus",
        "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
    )

    /** Туман (онбординг: фирменная завеса; tint — токен fog). Три волнистые линии,
     *  как в макете onboarding-duo.html — рисуется штрихом, не заливкой. */
    val Fog: ImageVector = pathIcon(
        "ic-fog",
        "M3 7 Q6 4.3 9 7 T15 7 T21 7 " +
            "M3 12 Q6 9.3 9 12 T15 12 T21 12 " +
            "M3 17 Q6 14.3 9 17 T15 17 T21 17",
        strokeWidth = 2f,
    )

    /** Гео-пин (онбординг: регион, доступ к геолокации; tint — акцент). */
    val Pin: ImageVector = pathIcon(
        "ic-pin",
        "M12 2C8.13 2 5 5.13 5 8.5c0 5.25 7 13 7 13s7-7.75 7-13C19 5.13 15.87 2 12 2zm0 " +
            "9.5c-1.66 0-3-1.34-3-3s1.34-3 3-3 3 1.34 3 3-1.34 3-3 3z",
    )

    /** Часы (тип цели «Минут в день»). */
    val Clock: ImageVector = pathIcon(
        "ic-clock",
        "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 " +
            "2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm.5-13H11v6l5.25 " +
            "3.15.75-1.23-4.5-2.67z",
    )

    /** Линейка (тип цели «Километров в день»). */
    val Ruler: ImageVector = pathIcon(
        "ic-ruler",
        "M21 6H3c-1.1 0-2 .9-2 2v8c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm0 " +
            "10H3V8h2v4h2V8h2v4h2V8h2v4h2V8h2v4h2V8h2v8z",
    )

    /** Кубок (новое достижение на финальном экране; tint — акцент). */
    val Trophy: ImageVector = pathIcon(
        "ic-trophy",
        "M19 5h-2V3H7v2H5c-1.1 0-2 .9-2 2v1c0 2.55 1.92 4.63 4.39 4.94.63 1.5 1.98 " +
            "2.63 3.61 2.96V19H7v2h10v-2h-4v-3.1c1.63-.33 2.98-1.46 3.61-2.96C19.08 " +
            "12.63 21 10.55 21 8V7c0-1.1-.9-2-2-2zM5 8V7h2v3.82C5.84 10.4 5 9.3 5 8zm14 " +
            "0c0 1.3-.84 2.4-2 2.82V7h2v1z",
    )

    /** Карты веером («Вытянуть карту» — раздача случайного задания; tint — ink). */
    val Cards: ImageVector = pathIcon(
        "ic-cards",
        "M2.53 19.65l1.34.56v-9.03l-2.73 7.35c-.4 1.13.06 2.31 1.05 2.92zM22.03 " +
            "15.95L17.07 3.98c-.31-.75-1.04-1.24-1.86-1.24-.49 0-.97.18-1.35.53L4.03 " +
            "13.05c-.74.68-.92 1.74-.49 2.6l3.45 7.76c.39.88 1.27 1.45 2.24 1.45h9.85c1.1 " +
            "0 2-.9 2-2 0-.45-.15-.88-.4-1.21zM12 9.55l5.91 5.41-3.24 1.53-2.67-5.99V9.55z",
    )

    /** Сетка 2×3 («Выбрать из 6» — шесть предложенных заданий; tint — ink). */
    val GridSix: ImageVector = pathIcon(
        "ic-grid-six",
        "M3 2h8v5H3zM13 2h8v5h-8zM3 9h8v5H3zM13 9h8v5h-8zM3 16h8v5H3zM13 16h8v5h-8z",
    )

    private fun pathIcon(name: String, pathData: String, strokeWidth: Float = 0f): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = if (strokeWidth > 0f) null else SolidColor(Color.Black),
            stroke = if (strokeWidth > 0f) SolidColor(Color.Black) else null,
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()
}
