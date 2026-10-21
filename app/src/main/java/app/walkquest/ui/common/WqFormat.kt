package app.walkquest.ui.common

import java.util.Locale

/**
 * Общее форматирование чисел для UI (7.12, AL-12): ЕДИНСТВЕННОЕ место, где
 * метры → «км» и доля → «%» с русской запятой. До 7.12 панели собирали строки
 * через `"% .1f".format(...)` (JVM-локаль, точка) — «0.0» вместо «0,0».
 *
 * Чистый JVM-объект (без Compose/Android) — юнит-тестируется напрямую.
 */
object WqFormat {
    private val RU: Locale = Locale.forLanguageTag("ru-RU")

    /** Метры → «3,6» (один знак, ru-запятая). Единица добавляется колонкой. */
    fun km(meters: Double): String = String.format(RU, "%.1f", meters / 1000.0)

    /** Доля 0..1 → «17,4%»; первые тайлы — честное «<0,1%» вместо «0,0%». */
    fun percent(fraction: Double): String {
        val percent = fraction * 100.0
        if (percent > 0.0 && percent < PERCENT_LOW_THRESHOLD) return "<0,1%"
        return String.format(RU, "%.1f%%", percent)
    }

    /**
     * Прирост процента за прогулку для третьей колонки панели (макет map-duo
     * «+0,4%» над подписью «открыто»). null (прогресс не считается) → «—»;
     * положительный прирост ниже порога отображения — «+<0,1%»; отрицательный
     * не бывает (открытия необратимы), зажимаем в ноль.
     */
    fun walkGain(fraction: Double?): String {
        if (fraction == null) return "—"
        val gain = (fraction * 100.0).coerceAtLeast(0.0)
        if (gain > 0.0 && gain < PERCENT_LOW_THRESHOLD) return "+<0,1%"
        return String.format(RU, "+%.1f%%", gain)
    }

    /** Порог «<0,1%»: всё ниже 0,05% округлялось бы «%.1f» в «0,0%». */
    private const val PERCENT_LOW_THRESHOLD = 0.05
}
