package app.walkquest.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-тесты сессии прогулки (история 2.1, FR-5/FR-6/FR-7): переходы состояний,
 * агрегаты (дистанция только по принятым, чистое время с паузами), восстановление
 * из снимка (как после убийства процесса).
 */
class WalkSessionTest {

    private fun point(lat: Double, lon: Double, t: Long) = TrackPoint(lat, lon, t)

    // Точки ~111 м друг от друга по широте (1° широты ≈ 111 320 м).
    private fun trackPoint(t: Long, index: Int) = point(59.900 + 0.001 * index, 30.300, t)

    // --- Переходы состояний ---

    @Test
    fun `new session is active and identifies itself by start time`() {
        val session = WalkSession(startedAtEpochMillis = 10_000L)
        assertEquals(WalkState.ACTIVE, session.state)
        assertEquals(10_000L, session.sessionId)
        assertEquals(10_000L, session.startedAtEpochMillis)
    }

    @Test
    fun `pause then resume closes pause interval`() {
        val session = WalkSession(startedAtEpochMillis = 0L)
        assertEquals(PauseResult.Ok, session.pause(1_000L))
        assertEquals(WalkState.PAUSED, session.state)
        assertEquals(ResumeResult.Ok, session.resume(61_000L))
        assertEquals(WalkState.ACTIVE, session.state)
        assertEquals(listOf(PauseInterval(1_000L, 61_000L)), session.pauseIntervals())
    }

    @Test
    fun `finish from active and from paused both work`() {
        val active = WalkSession(startedAtEpochMillis = 0L)
        assertEquals(FinishResult.Ok, active.finish(600_000L))
        assertEquals(WalkState.FINISHED, active.state)
        assertEquals(600_000L, active.finishedAtEpochMillis())

        val paused = WalkSession(startedAtEpochMillis = 0L)
        paused.pause(100_000L)
        assertEquals(FinishResult.Ok, paused.finish(160_000L))
        // Открытая пауза закрыта моментом финиша — чистое время не уходит в минус.
        assertEquals(listOf(PauseInterval(100_000L, 160_000L)), paused.pauseIntervals())
    }

    @Test
    fun `invalid transitions do not move state`() {
        val session = WalkSession(startedAtEpochMillis = 0L)
        assertTrue(session.pause(100L) is PauseResult.Ok)
        // Повторная пауза в паузе — отказ, состояние не трогаем.
        assertTrue(session.pause(200L) is PauseResult.Invalid)
        assertEquals(WalkState.PAUSED, session.state)
        assertTrue(session.resume(300L) is ResumeResult.Ok)
        // Возобновление без паузы — отказ.
        assertTrue(session.resume(400L) is ResumeResult.Invalid)
        assertEquals(WalkState.ACTIVE, session.state)
        // Финиш раньше старта — отказ.
        assertTrue(WalkSession(startedAtEpochMillis = 1_000L).finish(0L) is FinishResult.Invalid)
    }

    @Test
    fun `finished session rejects all transitions and points`() {
        val session = WalkSession(startedAtEpochMillis = 0L)
        session.acceptPoint(trackPoint(1_000L, 0))
        assertEquals(FinishResult.Ok, session.finish(2_000L))
        assertTrue(session.acceptPoint(trackPoint(3_000L, 1)) is AcceptResult.Rejected.SessionFinished)
        assertTrue(session.pause(3_000L) is PauseResult.Invalid)
        assertTrue(session.resume(3_000L) is ResumeResult.Invalid)
        assertTrue(session.finish(3_000L) is FinishResult.Invalid)
        assertEquals(1, session.acceptedCount)
    }

    // --- Агрегаты: дистанция и честные точки ---

    @Test
    fun `distance sums only accepted points from last accepted`() {
        val session = WalkSession(startedAtEpochMillis = 0L)
        assertEquals(AcceptResult.Ok, session.acceptPoint(trackPoint(1_000L, 0)))
        // Первая точка дистанции не даёт — только счётчик.
        assertEquals(0.0, session.distanceMeters, 0.0001)
        assertEquals(AcceptResult.Ok, session.acceptPoint(trackPoint(2_000L, 1)))
        val firstLeg = session.distanceMeters
        assertTrue("первая нога ~111 м, а не $firstLeg", firstLeg in 100.0..125.0)
        assertEquals(AcceptResult.Ok, session.acceptPoint(trackPoint(3_000L, 3)))
        // Вторая нога — от точки #1 сразу к #3 (2 шага по 0,001° ≈ 223 м): точка #2
        // «отвергнута фильтром» (сервис не зовёт acceptPoint) и дистанцию не раздувает.
        val total = session.distanceMeters
        assertTrue("две ноги ~334 м, а не $total", total in 320.0..350.0)
        assertEquals(3, session.acceptedCount)
    }

    @Test
    fun `paused session rejects points`() {
        val session = WalkSession(startedAtEpochMillis = 0L)
        session.acceptPoint(trackPoint(1_000L, 0))
        session.pause(2_000L)
        assertTrue(session.acceptPoint(trackPoint(3_000L, 1)) is AcceptResult.Rejected.SessionPaused)
        assertEquals(1, session.acceptedCount)
        assertEquals(0.0, session.distanceMeters, 0.0001)
    }

    // --- Агрегаты: чистое время ---

    @Test
    fun `active time subtracts pauses including open one`() {
        val session = WalkSession(startedAtEpochMillis = 0L)
        // 0–60 с активно.
        session.acceptPoint(trackPoint(60_000L, 0))
        session.pause(60_000L)
        // Пауза 60–180 с (120 с).
        session.resume(180_000L)
        // 180–300 с активно, затем пауза 300–… открытая.
        session.pause(300_000L)
        // На отметке 360 с: активно 60 + 120 = 180 с; открытая пауза (300–360) не считается.
        assertEquals(180_000L, session.activeMillis(360_000L))
    }

    @Test
    fun `finished session active time is frozen at finish`() {
        val session = WalkSession(startedAtEpochMillis = 0L)
        session.pause(100_000L)
        session.finish(400_000L)
        // Чистое время не растёт после финиша, сколько now ни подавай.
        assertEquals(100_000L, session.activeMillis(900_000L))
    }

    // --- Восстановление из снимка (после убийства процесса) ---

    @Test
    fun `restore from snapshot continues aggregates`() {
        val original = WalkSession(startedAtEpochMillis = 0L)
        original.acceptPoint(trackPoint(1_000L, 0))
        original.acceptPoint(trackPoint(2_000L, 1))
        original.pause(3_000L)

        val restored = WalkSession.restore(original.snapshot())
        assertEquals(WalkState.PAUSED, restored.state)
        assertEquals(original.distanceMeters, restored.distanceMeters, 0.0001)
        assertEquals(2, restored.acceptedCount)
        assertEquals(ResumeResult.Ok, restored.resume(10_000L))
        assertEquals(listOf(PauseInterval(3_000L, 10_000L)), restored.pauseIntervals())
    }

    @Test
    fun `recovery rebuilds aggregates from journal replay without double count`() {
        // Сценарий восстановления после убийства процесса (TrackingService):
        // агрегаты пересчитываются replay'ем журнала с НУЛЯ — перенос снимка
        // удвоил бы дистанцию (дефект, найденный ревью 2.1).
        val points = listOf(
            trackPoint(1_000L, 0),
            trackPoint(2_000L, 1),
            trackPoint(3_000L, 3),
        )
        val recovered = WalkSession(startedAtEpochMillis = 0L)
        points.forEach { assertEquals(AcceptResult.Ok, recovered.acceptPoint(it)) }
        val replayDistance = recovered.distanceMeters

        // То же самое, но «до убийства» сессия уже накопила агрегаты:
        // свежая сессия + replay тех же точек даёт ровно ту же дистанцию.
        val fresh = WalkSession(startedAtEpochMillis = 0L)
        points.forEach { fresh.acceptPoint(it) }
        assertEquals(replayDistance, fresh.distanceMeters, 0.0001)
        assertEquals(3, fresh.acceptedCount)
    }
}
