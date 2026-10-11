package app.walkquest.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Умные напоминания по цели (история 3.5, FR-14): напоминание уходит ТОЛЬКО
 * при отставании от цели. Решение и текст — здесь, в domain (AD-4): Android-
 * слой только планирует вечерний будильник и шлёт уведомление, если политика
 * вернула текст. Чистый Kotlin (AD-10): JVM-тестируемо, пояс инжектируется.
 *
 * Семантика «дня» для MINUTES/KM_PER_DAY — локальная дата (конвенции: день =
 * календарный день устройства); для v1 сравнение по сегодняшнему дню, недельное
 * накопление — к полевой калибровке (deferred-work).
 */
class GoalReminderPolicy(
    private val history: WalkHistory,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /**
     * null — отставания нет (напоминание не нужно); иначе — текст для
     * тихого уведомления (FR-9), готовый к показу.
     */
    fun evaluate(goal: WalkGoal, nowEpochMillis: Long): String? = when (goal.type) {
        GoalType.WALKS_PER_WEEK -> {
            val fact = history.walksInWeekOf(nowEpochMillis).size
            val remaining = goal.value - fact
            if (remaining <= 0) null
            else "Неделя подходит к концу, а до плана $remaining " +
                plural(remaining, "прогулка", "прогулки", "прогулок") + ". Погуляем?"
        }
        GoalType.MINUTES_PER_DAY -> {
            val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowEpochMillis), zone)
            val factMinutes = history.walksOn(today).sumOf { it.activeMillis } / 60_000L
            val target = goal.value.toLong()
            if (factMinutes >= target) null
            else "Сегодня гуляли $factMinutes " +
                plural(factMinutes.toInt(), "минута", "минуты", "минут") +
                ", а цель — $target. Погуляем вечером?"
        }
        GoalType.KM_PER_DAY -> {
            val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowEpochMillis), zone)
            val factKm = history.walksOn(today).sumOf { it.distanceMeters } / 1000.0
            val targetKm = goal.kmValue()
            if (factKm >= targetKm) null
            else "Сегодня ${"%.1f".format(factKm).replace('.', ',')} км " +
                "при цели ${goal.describe()}. Погуляем вечером?"
        }
    }

    private fun plural(n: Int, one: String, few: String, many: String): String {
        val mod100 = n % 100
        val mod10 = n % 10
        return when {
            mod100 in 11..19 -> many
            mod10 == 1 -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }
}
