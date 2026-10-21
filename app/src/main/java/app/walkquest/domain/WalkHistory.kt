package app.walkquest.domain

import app.walkquest.tracking.WalkHistoryStore
import app.walkquest.tracking.WalkRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Агрегаты истории прогулок (история 3.1, FR-13) — единственное чтение
 * истории из domain (AD-4): UI берёт готовые числа. Дни и недели — ЛОКАЛЬНЫЙ
 * календарь устройства (конвенции архитектуры): прогулка засчитывается в дату
 * своего старта по часовому поясу устройства, неделя — пн–вс (ISO).
 *
 * Чистый Kotlin (AD-10): JVM-тестируемо; часовой пояс инжектируется (тесты
 * не зависят от машины, боевой код передаёт ZoneId.systemDefault()).
 */
class WalkHistory(
    private val store: WalkHistoryStore,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /** Все завершённые прогулки, от старых к новым. */
    fun records(): List<WalkRecord> = store.read().records

    /** Число завершённых прогулок. */
    fun totalWalks(): Int = records().size

    /** Суммарная дистанция по принятым точкам, метры. */
    fun totalDistanceMeters(): Double = records().sumOf { it.distanceMeters }

    /**
     * Суммарное чистое (активное, без пауз) время прогулок, мс —
     * для среднего времени прогулки (эпик design-alignment 7.4).
     */
    fun totalActiveMillis(): Long = records().sumOf { it.activeMillis }

    /** Прогулки, СТАРТОВАВШИЕ в локальную дату [date]. */
    fun walksOn(date: LocalDate): List<WalkRecord> =
        records().filter { it.startedAtLocalDate() == date }

    /** Локальные даты всех прогулок (множество — для серий, FR-13). */
    fun walkDates(): Set<LocalDate> =
        records().map { it.startedAtLocalDate() }.toSet()

    /**
     * Прогулки текущей недели (пн–вс локального календаря), к которой принадлежит
     * момент [nowEpochMillis]: отставание от цели «N прогулок в неделю»
     * считается по ним (FR-14).
     */
    fun walksInWeekOf(nowEpochMillis: Long): List<WalkRecord> {
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowEpochMillis), zone)
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val sunday = monday.plusDays(6)
        return records().filter {
            val date = it.startedAtLocalDate()
            !date.isBefore(monday) && !date.isAfter(sunday)
        }
    }

    private fun WalkRecord.startedAtLocalDate(): LocalDate =
        LocalDate.ofInstant(Instant.ofEpochMilli(startedAtEpochMillis), zone)
}
