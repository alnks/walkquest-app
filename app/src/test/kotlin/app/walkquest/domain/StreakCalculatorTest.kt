package app.walkquest.domain

import app.walkquest.tracking.WalkHistoryStore
import app.walkquest.tracking.WalkRecord
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM-тесты серии прогулок (история 3.4, FR-13): подряд идущие локальные дни
 * с ≥1 прогулкой; сегодняшний день не обрывает серию до его конца.
 */
class StreakCalculatorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun streak(): Pair<StreakCalculator, WalkHistoryStore> {
        val store = WalkHistoryStore(File(temp.root, "walks/walks.jsonl"))
        val history = WalkHistory(store, ZoneOffset.UTC)
        return StreakCalculator(history, ZoneOffset.UTC) to store
    }

    private fun walkOn(date: LocalDate, id: Long) = WalkRecord(
        sessionId = id,
        startedAtEpochMillis = date.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
        finishedAtEpochMillis = date.atTime(12, 30).toInstant(ZoneOffset.UTC).toEpochMilli(),
        distanceMeters = 3_000.0,
        activeMillis = 1_800_000L,
        acceptedCount = 30,
    )

    private fun noon(date: LocalDate) =
        date.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun `empty history has zero streak`() {
        val (streak, _) = streak()
        assertEquals(0, streak.currentStreak(noon(LocalDate.of(2026, 10, 9))))
    }

    @Test
    fun `consecutive days build streak`() {
        val (streak, store) = streak()
        store.append(walkOn(LocalDate.of(2026, 10, 7), 1))
        store.append(walkOn(LocalDate.of(2026, 10, 8), 2))
        store.append(walkOn(LocalDate.of(2026, 10, 9), 3))
        assertEquals(3, streak.currentStreak(noon(LocalDate.of(2026, 10, 9))))
    }

    @Test
    fun `gap breaks streak at last consecutive run`() {
        val (streak, store) = streak()
        store.append(walkOn(LocalDate.of(2026, 10, 5), 1))
        store.append(walkOn(LocalDate.of(2026, 10, 6), 2))
        store.append(walkOn(LocalDate.of(2026, 10, 9), 3))
        assertEquals(1, streak.currentStreak(noon(LocalDate.of(2026, 10, 9))))
    }

    @Test
    fun `day without walk yet does not break streak`() {
        val (streak, store) = streak()
        store.append(walkOn(LocalDate.of(2026, 10, 8), 1))
        store.append(walkOn(LocalDate.of(2026, 10, 9), 2))
        // Сегодня 10-е, прогулки ещё не было (полдень): серия живёт со вчера.
        assertEquals(2, streak.currentStreak(noon(LocalDate.of(2026, 10, 10))))
    }

    @Test
    fun `streak dies only after full day without walks`() {
        val (streak, store) = streak()
        store.append(walkOn(LocalDate.of(2026, 10, 8), 1))
        // Послезавтра без прогулок — вчера (9-е) пусто: серия 0.
        assertEquals(0, streak.currentStreak(noon(LocalDate.of(2026, 10, 10))))
    }

    @Test
    fun `multiple walks same day count once`() {
        val (streak, store) = streak()
        store.append(walkOn(LocalDate.of(2026, 10, 9), 1))
        store.append(walkOn(LocalDate.of(2026, 10, 9), 2))
        assertEquals(1, streak.currentStreak(noon(LocalDate.of(2026, 10, 9))))
    }
}
