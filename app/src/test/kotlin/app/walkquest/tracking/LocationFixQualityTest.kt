package app.walkquest.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-тесты качества фикса для маркера «я» (полевой тест 2, 2026-10-05, дефект 3):
 * старый lastKnownLocation маркером не показываем; грубый фикс — тоже; свежий
 * точный — показываем; фикс без accuracy — не брак.
 */
class LocationFixQualityTest {

    private val now = 1_000_000L

    @Test
    fun `fresh accurate fix is marker worthy`() {
        assertTrue(LocationFixQuality.isMarkerWorthy(12f, now - 2_000L, now))
    }

    @Test
    fun `fix without accuracy is marker worthy when fresh`() {
        // Провайдер не дал точность (null) — не брак (как в PointFilter).
        assertTrue(LocationFixQuality.isMarkerWorthy(null, now - 5_000L, now))
    }

    @Test
    fun `stale fix is not marker worthy`() {
        // Старый lastKnownLocation (дефект 3: «в 20 минутах езды») — маркера нет.
        assertFalse(LocationFixQuality.isMarkerWorthy(10f, now - 10 * 60_000L, now))
        assertFalse(LocationFixQuality.isMarkerWorthy(null, now - 31_000L, now))
    }

    @Test
    fun `fix at the age threshold boundary`() {
        // Ровно 30 с — ещё свежий (отсечение строгое «>»); 30 с + 1 мс — устарел.
        assertTrue(LocationFixQuality.isMarkerWorthy(10f, now - 30_000L, now))
        assertFalse(LocationFixQuality.isMarkerWorthy(10f, now - 30_001L, now))
    }

    @Test
    fun `coarse fix is not marker worthy`() {
        // Грубее 100 м (вышки/сеть) — врёт о месте, маркера нет.
        assertFalse(LocationFixQuality.isMarkerWorthy(150f, now - 1_000L, now))
        assertFalse(LocationFixQuality.isMarkerWorthy(800f, now - 1_000L, now))
    }

    @Test
    fun `fix at the accuracy threshold boundary`() {
        // Ровно 100 м — ещё показываем; 100,1 м — нет.
        assertTrue(LocationFixQuality.isMarkerWorthy(100f, now - 1_000L, now))
        assertFalse(LocationFixQuality.isMarkerWorthy(100.1f, now - 1_000L, now))
    }

    @Test
    fun `missing timestamp is not marker worthy`() {
        // Времени фикса нет — свежесть проверить нельзя, маркера нет.
        assertFalse(LocationFixQuality.isMarkerWorthy(10f, null, now))
    }
}
