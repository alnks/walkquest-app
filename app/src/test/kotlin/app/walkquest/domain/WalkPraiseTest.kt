package app.walkquest.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-тесты похвалы финального экрана (история 2.8, FR-8): тиры дистанции,
 * границы, детерминированность, подпись про завтра.
 */
class WalkPraiseTest {

    @Test
    fun `praise tiers by distance`() {
        assertEquals("Коротко, но вы вышли. Уже победа.", WalkPraise.forDistance(0.0))
        assertEquals("Коротко, но вы вышли. Уже победа.", WalkPraise.forDistance(49.9))
        assertEquals("Прогулка состоялась. Это главное.", WalkPraise.forDistance(50.0))
        assertEquals("Прогулка состоялась. Это главное.", WalkPraise.forDistance(999.9))
        assertEquals("Неплохая прогулка — километр есть.", WalkPraise.forDistance(1_000.0))
        assertEquals("Неплохая прогулка — километр есть.", WalkPraise.forDistance(4_999.9))
        assertEquals("Хорошая прогулка. Так держать.", WalkPraise.forDistance(5_000.0))
        assertEquals("Хорошая прогулка. Так держать.", WalkPraise.forDistance(9_999.9))
        assertEquals("Отличная прогулка — целый десяток километров.", WalkPraise.forDistance(10_000.0))
        assertEquals("Отличная прогулка — целый десяток километров.", WalkPraise.forDistance(19_999.9))
        assertEquals("Большой путь. Город заметил вас сегодня.", WalkPraise.forDistance(20_000.0))
        assertEquals("Большой путь. Город заметил вас сегодня.", WalkPraise.forDistance(42_195.0))
    }

    @Test
    fun `praise is deterministic`() {
        assertEquals(WalkPraise.forDistance(7_500.0), WalkPraise.forDistance(7_500.0))
    }

    @Test
    fun `praise strings are calm and non empty`() {
        // Тон DESIGN: без крикливых суперлативов — мягкие формулировки.
        listOf(0.0, 500.0, 3_000.0, 12_000.0, 25_000.0).forEach { distance ->
            val praise = WalkPraise.forDistance(distance)
            assertTrue("пустая похвала для $distance", praise.isNotBlank())
            assertTrue("крикливая похвала: $praise", !praise.contains("!!!"))
        }
    }

    @Test
    fun `tomorrow note is constant and present`() {
        assertTrue(WalkPraise.TOMORROW_NOTE.contains("Завтра"))
    }
}
