package app.walkquest.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Текущая серия прогулок (история 3.4, FR-13): число подряд идущих локальных
 * дней с ≥1 завершённой прогулкой. День серии = дата СТАРТА прогулки (конвенции
 * архитектуры). День ещё не закончился: если сегодня прогулки ещё не было,
 * серия не обрывается — считаем от вчера. Чистый Kotlin (AD-10).
 */
class StreakCalculator(
    private val history: WalkHistory,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /** Серия на момент [nowEpochMillis] (0 — ни одного дня с прогулкой). */
    fun currentStreak(nowEpochMillis: Long): Int {
        val days = history.walkDates()
        if (days.isEmpty()) return 0
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowEpochMillis), zone)
        // Сегодня уже гуляли — серия живёт; нет — считаем от вчера (день не прошёл).
        var cursor = if (today in days) today else today.minusDays(1)
        var streak = 0
        while (cursor in days) {
            streak += 1
            cursor = cursor.minusDays(1)
        }
        return streak
    }
}
