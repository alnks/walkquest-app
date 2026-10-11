package app.walkquest.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM-тесты детектора неподвижности (история 2.4, FR-7): автопауза после окна
 * без движения (однократно), автовозобновление движением, джиттер стоящего
 * человека не сбрасывает окно, движение обновляет референс, сброс.
 */
class MotionPauseTrackerTest {

    private fun point(t: Long, index: Int) = TrackPoint(59.900 + 0.001 * index, 30.300, t)

    /** Джиттер: точки в пределах 11 м (0,0001°) от базовой — меньше порога 30 м. */
    private fun jitter(t: Long, shift: Int) = TrackPoint(59.900 + 0.0001 * (shift % 2), 30.300, t)

    @Test
    fun `pause due once after inactivity window without movement`() {
        val tracker = MotionPauseTracker()
        assertEquals(MotionSignal.None, tracker.onAcceptedPoint(point(0L, 0)))
        // 14 минут джиттера в пределах 30 м — окно ещё не истекло.
        val beforeWindow = MotionPauseTracker.INACTIVITY_WINDOW_MS - 60_000L
        assertEquals(MotionSignal.None, tracker.onAcceptedPoint(jitter(beforeWindow, 1)))
        // 15 минут — пауза; сигнал однократный.
        assertEquals(MotionSignal.PauseDue, tracker.onAcceptedPoint(jitter(MotionPauseTracker.INACTIVITY_WINDOW_MS, 0)))
        assertEquals(MotionSignal.None, tracker.onAcceptedPoint(jitter(MotionPauseTracker.INACTIVITY_WINDOW_MS + 60_000L, 1)))
    }

    @Test
    fun `movement within jitter radius does not reset window`() {
        val tracker = MotionPauseTracker()
        tracker.onAcceptedPoint(point(0L, 0))
        // Мелкие перемещения (11 м < 30 м порога) раз в минуту — человек стоит;
        // 14 минут — сигналов нет.
        var t = 0L
        for (i in 1..14) {
            t += 60_000L
            assertEquals("итерация $i", MotionSignal.None, tracker.onAcceptedPoint(jitter(t, i)))
        }
        // 15-я минута — пауза (джиттер окно не сбрасывал).
        assertEquals(MotionSignal.PauseDue, tracker.onAcceptedPoint(jitter(t + 60_000L, 1)))
    }

    @Test
    fun `movement beyond threshold resumes after pause`() {
        val tracker = MotionPauseTracker()
        tracker.onAcceptedPoint(point(0L, 0))
        assertEquals(MotionSignal.PauseDue, tracker.onAcceptedPoint(point(MotionPauseTracker.INACTIVITY_WINDOW_MS, 0)))
        // Пошёл: точка дальше 30 м от референса → возобновление.
        assertEquals(MotionSignal.Resumed, tracker.onAcceptedPoint(point(MotionPauseTracker.INACTIVITY_WINDOW_MS + 5_000L, 1)))
    }

    @Test
    fun `movement beyond threshold updates reference and restarts window`() {
        val tracker = MotionPauseTracker()
        tracker.onAcceptedPoint(point(0L, 0))
        // Человек идёт: каждые 2 минуты точка в 111 м (≥30 м порога) — окно
        // неподвижности постоянно сбрасывается, паузы нет.
        var t = 0L
        var index = 0
        repeat(10) {
            t += 120_000L
            index += 1
            assertEquals(MotionSignal.None, tracker.onAcceptedPoint(point(t, index)))
        }
        // Теперь встал: 15 минут тишины от последнего движения → пауза.
        assertEquals(MotionSignal.PauseDue, tracker.onAcceptedPoint(point(t + MotionPauseTracker.INACTIVITY_WINDOW_MS, index)))
    }

    @Test
    fun `movement without pending pause is plain none`() {
        val tracker = MotionPauseTracker()
        tracker.onAcceptedPoint(point(0L, 0))
        // Активная прогулка: движение без паузы — не «возобновление», а None.
        assertEquals(MotionSignal.None, tracker.onAcceptedPoint(point(60_000L, 1)))
    }

    @Test
    fun `reset clears reference window and pending pause`() {
        val tracker = MotionPauseTracker()
        tracker.onAcceptedPoint(point(0L, 0))
        tracker.reset()
        // После сброса первая точка — новый референс, окно с нуля.
        assertEquals(MotionSignal.None, tracker.onAcceptedPoint(point(60_000L, 5)))
    }

    @Test
    fun `custom window is respected`() {
        val tracker = MotionPauseTracker(inactivityWindowMillis = 5_000L)
        tracker.onAcceptedPoint(point(0L, 0))
        assertEquals(MotionSignal.None, tracker.onAcceptedPoint(point(4_000L, 0)))
        assertEquals(MotionSignal.PauseDue, tracker.onAcceptedPoint(point(5_000L, 0)))
    }
    // --- Напоминание о забытой прогулке (история 2.5, FR-7): второе окно ---

    @Test
    fun `reminder due after second inactivity window since last movement`() {
        val tracker = MotionPauseTracker()
        val w = MotionPauseTracker.INACTIVITY_WINDOW_MS
        tracker.onAcceptedPoint(point(0L, 0))
        // 15 мин: пауза.
        assertEquals(MotionSignal.PauseDue, tracker.onAcceptedPoint(jitter(w, 1)))
        // 22 мин (пауза + 7): напоминания ещё нет.
        assertEquals(MotionSignal.None, tracker.onAcceptedPoint(jitter(w + 7 * 60_000L, 0)))
        // 30 мин от последнего движения: напоминание, однократно.
        assertEquals(MotionSignal.ReminderDue, tracker.onAcceptedPoint(jitter(w * 2, 1)))
        assertEquals(MotionSignal.None, tracker.onAcceptedPoint(jitter(w * 2 + 60_000L, 0)))
    }

    @Test
    fun `movement between pause and reminder cancels reminder scenario`() {
        val tracker = MotionPauseTracker()
        val w = MotionPauseTracker.INACTIVITY_WINDOW_MS
        tracker.onAcceptedPoint(point(0L, 0))
        assertEquals(MotionSignal.PauseDue, tracker.onAcceptedPoint(jitter(w, 1)))
        // Пошёл: возобновление сбрасывает и паузу, и флаг напоминания.
        assertEquals(MotionSignal.Resumed, tracker.onAcceptedPoint(point(w + 60_000L, 1)))
        // Встал снова (джиттер вокруг НОВОГО референса, индекс 1): пауза —
        // через полное окно от движения.
        val near = { t: Long, shift: Int -> TrackPoint(59.901 + 0.0001 * (shift % 2), 30.300, t) }
        assertEquals(MotionSignal.None, tracker.onAcceptedPoint(near(w + 10 * 60_000L, 0)))
        assertEquals(
            MotionSignal.PauseDue,
            tracker.onAcceptedPoint(near(w + 60_000L + w, 1)),
        )
    }

    @Test
    fun `reminder not fired without pause`() {
        // Напоминание — только после паузы: короткое окно не должно его дать.
        val tracker = MotionPauseTracker(inactivityWindowMillis = 5_000L)
        tracker.onAcceptedPoint(point(0L, 0))
        assertEquals(MotionSignal.PauseDue, tracker.onAcceptedPoint(point(5_000L, 0)))
        // Дальше по той же логике: пауза + второе окно = напоминание.
        assertEquals(MotionSignal.ReminderDue, tracker.onAcceptedPoint(point(10_000L, 0)))
    }
}
