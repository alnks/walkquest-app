package app.walkquest.domain

import app.walkquest.tracking.WalkHistoryStore
import app.walkquest.tracking.WalkRecord
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM-тесты умных напоминаний (история 3.5, FR-14): напоминание ТОЛЬКО при
 * отставании от цели; тексты по типам цели.
 */
class GoalReminderPolicyTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun policy(): Pair<GoalReminderPolicy, WalkHistoryStore> {
        val store = WalkHistoryStore(File(temp.root, "walks/walks.jsonl"))
        val history = WalkHistory(store, ZoneOffset.UTC)
        return GoalReminderPolicy(history, ZoneOffset.UTC) to store
    }

    private fun walkOn(date: LocalDate, id: Long, distanceMeters: Double = 3_000.0, activeMillis: Long = 1_800_000L) =
        WalkRecord(
            sessionId = id,
            startedAtEpochMillis = date.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
            finishedAtEpochMillis = date.atTime(12, 30).toInstant(ZoneOffset.UTC).toEpochMilli(),
            distanceMeters = distanceMeters,
            activeMillis = activeMillis,
            acceptedCount = 30,
        )

    private fun noon(date: LocalDate) =
        date.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun `walks per week no reminder when plan met`() {
        val (policy, store) = policy()
        val wednesday = LocalDate.of(2026, 10, 7)
        store.append(walkOn(LocalDate.of(2026, 10, 5), 1))
        store.append(walkOn(LocalDate.of(2026, 10, 6), 2))
        store.append(walkOn(LocalDate.of(2026, 10, 7), 3))
        assertNull(policy.evaluate(WalkGoal(GoalType.WALKS_PER_WEEK, 3), noon(wednesday)))
    }

    @Test
    fun `walks per week reminder shows remaining count`() {
        val (policy, store) = policy()
        val wednesday = LocalDate.of(2026, 10, 7)
        store.append(walkOn(LocalDate.of(2026, 10, 6), 1))
        val text = policy.evaluate(WalkGoal(GoalType.WALKS_PER_WEEK, 3), noon(wednesday))
        assertTrue(text != null && text.contains("2 прогулки"))
        assertTrue(text!!.contains("Погуляем"))
    }

    @Test
    fun `walks per week counts only current week`() {
        val (policy, store) = policy()
        val wednesday = LocalDate.of(2026, 10, 7)
        // Прошлая неделя не засчитывается.
        store.append(walkOn(LocalDate.of(2026, 10, 4), 1))
        val text = policy.evaluate(WalkGoal(GoalType.WALKS_PER_WEEK, 1), noon(wednesday))
        assertTrue(text != null)
    }

    @Test
    fun `minutes per day compares today only`() {
        val (policy, store) = policy()
        val today = LocalDate.of(2026, 10, 9)
        // Вчера 60 минут — не засчитывается.
        store.append(walkOn(LocalDate.of(2026, 10, 8), 1, activeMillis = 3_600_000L))
        assertNull(policy.evaluate(WalkGoal(GoalType.MINUTES_PER_DAY, 30), noon(today))?.let { null })
        val text = policy.evaluate(WalkGoal(GoalType.MINUTES_PER_DAY, 30), noon(today))
        assertTrue(text != null && text.contains("цель"))
        // Сегодня 30 минут — план выполнен.
        store.append(walkOn(today, 2, activeMillis = 1_800_000L))
        assertNull(policy.evaluate(WalkGoal(GoalType.MINUTES_PER_DAY, 30), noon(today)))
    }

    @Test
    fun `km per day compares today distance`() {
        val (policy, store) = policy()
        val today = LocalDate.of(2026, 10, 9)
        store.append(walkOn(today, 1, distanceMeters = 1_200.0))
        // Цель 2 км: сегодня 1,2 км — напоминание.
        val text = policy.evaluate(WalkGoal(GoalType.KM_PER_DAY, 4), noon(today))
        assertTrue(text != null && text.contains("1,2 км"))
        // Цель 1 км — выполнено.
        assertNull(policy.evaluate(WalkGoal(GoalType.KM_PER_DAY, 2), noon(today)))
    }
}
