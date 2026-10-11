package app.walkquest.domain

import app.walkquest.tracking.WalkHistoryStore
import app.walkquest.tracking.WalkRecord
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM-тесты агрегатов истории (история 3.1): недельная выборка (пн–вс, локальный
 * календарь через инжектируемый пояс) и суммарные агрегаты.
 */
class WalkHistoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** Фиксированный пояс UTC — даты считаем в нём явно. */
    private fun history(): Pair<WalkHistory, WalkHistoryStore> {
        val store = WalkHistoryStore(File(temp.root, "walks/walks.jsonl"))
        return WalkHistory(store, ZoneOffset.UTC) to store
    }

    /** Запись с локальной датой старта [date] (по UTC) в [hour]:00. */
    private fun recordOn(date: LocalDate, hour: Long, id: Long) = WalkRecord(
        sessionId = id,
        startedAtEpochMillis = date.atTime(hour.toInt(), 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
        finishedAtEpochMillis = date.atTime(hour.toInt(), 30).toInstant(ZoneOffset.UTC).toEpochMilli(),
        distanceMeters = 4_000.0,
        activeMillis = 1_800_000L,
        acceptedCount = 50,
    )

    @Test
    fun `aggregates sum over all records`() {
        val (history, store) = history()
        store.append(recordOn(LocalDate.of(2026, 10, 6), 10, 1))
        store.append(recordOn(LocalDate.of(2026, 10, 7), 11, 2))
        assertEquals(2, history.totalWalks())
        assertEquals(8_000.0, history.totalDistanceMeters(), 0.0001)
    }

    @Test
    fun `week selection monday to sunday by local date`() {
        val (history, store) = history()
        // Неделя 5–11 октября 2026 (пн–вс): воскресенье 4-го и понедельник 12-го
        // — соседние недели, не должны попасть.
        store.append(recordOn(LocalDate.of(2026, 10, 4), 12, 1)) // вс, прошлая неделя
        store.append(recordOn(LocalDate.of(2026, 10, 5), 9, 2)) // пн
        store.append(recordOn(LocalDate.of(2026, 10, 8), 20, 3)) // чт
        store.append(recordOn(LocalDate.of(2026, 10, 11), 23, 4)) // вс, 23:00 — этой недели
        store.append(recordOn(LocalDate.of(2026, 10, 12), 1, 5)) // пн, следующая
        val wednesday = LocalDate.of(2026, 10, 7).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val week = history.walksInWeekOf(wednesday)
        assertEquals(listOf(2L, 3L, 4L), week.map { it.sessionId })
    }

    @Test
    fun `walk counts to start date not finish date`() {
        val (history, store) = history()
        // Прогулка началась в 23:00 и закончилась после полуночи — день та,
        // что была у СТАРТА (конвенция: день прогулки = дата начала).
        val start = LocalDate.of(2026, 10, 9).atTime(23, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        store.append(
            WalkRecord(1, start, start + 3_600_000L, 2_000.0, 3_000_000L, 20),
        )
        assertEquals(1, history.walksOn(LocalDate.of(2026, 10, 9)).size)
        assertTrue(history.walksOn(LocalDate.of(2026, 10, 10)).isEmpty())
    }

    @Test
    fun `empty history has zero aggregates`() {
        val (history, _) = history()
        assertEquals(0, history.totalWalks())
        assertEquals(0.0, history.totalDistanceMeters(), 0.0001)
        assertTrue(history.records().isEmpty())
    }
}
