package app.walkquest.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-тесты троттлера прогресс-уведомлений (история 2.7, FR-9): первый показ
 * сразу, повторы в пределах интервала подавлены, после интервала пропущен,
 * кастомный интервал, сброс.
 */
class NotificationThrottlerTest {

    @Test
    fun `first notification shows immediately`() {
        val throttler = NotificationThrottler(intervalMillis = 5 * 60_000L)
        assertTrue(throttler.shouldNotify(1_000L))
    }

    @Test
    fun `repeats within interval are suppressed`() {
        val throttler = NotificationThrottler(intervalMillis = 5 * 60_000L)
        assertTrue(throttler.shouldNotify(0L))
        assertFalse(throttler.shouldNotify(60_000L))
        assertFalse(throttler.shouldNotify(4 * 60_000L))
    }

    @Test
    fun `notification passes again after interval`() {
        val throttler = NotificationThrottler(intervalMillis = 5 * 60_000L)
        assertTrue(throttler.shouldNotify(0L))
        assertFalse(throttler.shouldNotify(4 * 60_000L + 59_000L))
        assertTrue(throttler.shouldNotify(5 * 60_000L))
        assertFalse(throttler.shouldNotify(5 * 60_000L + 1_000L))
        assertTrue(throttler.shouldNotify(10 * 60_000L))
    }

    @Test
    fun `custom interval respected`() {
        val throttler = NotificationThrottler(intervalMillis = 60_000L)
        assertTrue(throttler.shouldNotify(0L))
        assertFalse(throttler.shouldNotify(59_999L))
        assertTrue(throttler.shouldNotify(60_000L))
    }

    @Test
    fun `reset allows immediate notification`() {
        val throttler = NotificationThrottler(intervalMillis = 5 * 60_000L)
        assertTrue(throttler.shouldNotify(0L))
        throttler.reset()
        assertTrue(throttler.shouldNotify(1_000L))
    }
}
