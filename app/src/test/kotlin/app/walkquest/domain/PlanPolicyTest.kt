package app.walkquest.domain

import app.walkquest.data.plans.PlannedWalk
import app.walkquest.tracking.WalkHistoryStore
import app.walkquest.tracking.WalkRecord
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM-тесты выполнения плана (история 3.6, FR-17): прогулка в день плана =
 * выполнен; пропуск не наказывается (серия не затрагивается — см. 3.4).
 */
class PlanPolicyTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun policy(): Pair<PlanPolicy, WalkHistoryStore> {
        val store = WalkHistoryStore(File(temp.root, "walks/walks.jsonl"))
        val history = WalkHistory(store, ZoneOffset.UTC)
        return PlanPolicy(history, ZoneOffset.UTC) to store
    }

    private fun planOn(date: LocalDate, id: Long) = PlannedWalk(
        id = id,
        plannedAtEpochMillis = date.atTime(18, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
    )

    private fun walkOn(date: LocalDate, id: Long) = WalkRecord(
        sessionId = id,
        startedAtEpochMillis = date.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
        finishedAtEpochMillis = date.atTime(12, 30).toInstant(ZoneOffset.UTC).toEpochMilli(),
        distanceMeters = 3_000.0,
        activeMillis = 1_800_000L,
        acceptedCount = 30,
    )

    @Test
    fun `plan fulfilled when walk on planned day`() {
        val (policy, store) = policy()
        val day = LocalDate.of(2026, 10, 10)
        store.append(walkOn(day, 1))
        assertTrue(policy.isFulfilled(planOn(day, 100)))
    }

    @Test
    fun `plan not fulfilled without walk on that day`() {
        val (policy, store) = policy()
        store.append(walkOn(LocalDate.of(2026, 10, 9), 1))
        assertFalse(policy.isFulfilled(planOn(LocalDate.of(2026, 10, 10), 100)))
    }

    @Test
    fun `plan not fulfilled on empty history`() {
        val (policy, _) = policy()
        assertFalse(policy.isFulfilled(planOn(LocalDate.of(2026, 10, 10), 100)))
    }
}
