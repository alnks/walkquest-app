package app.walkquest.data.fog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Сетка тайлов региона: геометрия обязана совпадать с генератором пакета
 * (tools/region-pack/region_pack/walkability.py, формат v0) — indexAt, кольца, валидация.
 */
class FogGridTest {

    @Test
    fun `fromPackage parses grid header and meta bbox`() {
        val header = GridHeader(
            cols = 328, rows = 307, tileSizeMeters = 100.0,
            origin = listOf(30.042, 59.796), order = "row-major, lsb-first",
        )
        val grid = FogGrid.fromPackage(header, bbox = listOf(30.042, 59.796, 30.63, 60.071))

        assertEquals(328, grid.cols)
        assertEquals(307, grid.rows)
        assertEquals(328 * 307, grid.tileCount)
        assertEquals(30.042, grid.originLon, 1e-12)
        assertEquals(59.796, grid.originLat, 1e-12)
        // Формулы генератора: dy = 100/111320; dx = 100/(111320*cos(центр bbox)).
        assertEquals(100.0 / 111320.0, grid.dy, 1e-15)
        val expectedDx = 100.0 / (111320.0 * kotlin.math.cos(Math.toRadians((59.796 + 60.071) / 2.0)))
        assertEquals(expectedDx, grid.dx, 1e-15)
    }

    @Test
    fun `indexAt maps southwest corner point to index 0`() {
        val grid = TestFog.fogGrid()
        assertEquals(0, grid.indexAt(59.0 + grid.dy / 2, 30.0 + grid.dx / 2))
        // Точка ровно в origin — внутри тайла 0 (floor вниз).
        assertEquals(0, grid.indexAt(59.0, 30.0))
    }

    @Test
    fun `indexAt is row-major`() {
        val grid = TestFog.fogGrid()
        // Вторая колонка первой строки → 1.
        assertEquals(1, grid.indexAt(59.0 + grid.dy / 2, 30.0 + grid.dx * 1.5))
        // Первая колонка второй строки → cols.
        assertEquals(grid.cols, grid.indexAt(59.0 + grid.dy * 1.5, 30.0 + grid.dx / 2))
    }

    @Test
    fun `indexAt outside grid returns null`() {
        val grid = TestFog.fogGrid()
        assertNull(grid.indexAt(59.0, 30.0 - 1e-6)) // западнее origin
        assertNull(grid.indexAt(59.0 - 1e-6, 30.0)) // южнее origin
        assertNull(grid.indexAt(grid.northLat + 1e-9, 30.0)) // севернее сетки
        assertNull(grid.indexAt(59.0, grid.eastLon + 1e-9)) // восточнее сетки
    }

    @Test
    fun `indexAt at far edges`() {
        val grid = TestFog.fogGrid()
        // За северной/восточной границей сетки — вне региона (допуск на округление double).
        assertNull(grid.indexAt(grid.northLat + grid.dy / 2, 30.0))
        assertNull(grid.indexAt(59.0, grid.eastLon + grid.dx / 2))
        // Внутри последнего тайла — последний индекс.
        assertEquals(
            grid.tileCount - 1,
            grid.indexAt(grid.northLat - grid.dy / 2, grid.eastLon - grid.dx / 2),
        )
    }

    @Test
    fun `tileRing is a closed rectangle on the grid`() {
        val grid = TestFog.fogGrid()
        val ring = grid.tileRing(0)
        assertEquals(5, ring.size)
        assertArrayEquals(ring.first(), ring.last())
        // Все четыре угла тайла присутствуют; ориентацию обхода проверяет отдельный тест.
        val corners = ring.dropLast(1).map { it[0] to it[1] }.toSet()
        assertEquals(
            setOf(
                30.0 to 59.0,
                30.0 + grid.dx to 59.0,
                30.0 + grid.dx to 59.0 + grid.dy,
                30.0 to 59.0 + grid.dy,
            ),
            corners,
        )
    }

    @Test
    fun `tileRing winds clockwise opposite to counterclockwise bboxRing`() {
        val grid = TestFog.fogGrid()
        // MapLibre (classifyRings) признаёт дыркой только кольцо с ориентацией,
        // противоположной экстерьеру: bboxRing — CCW (площадь > 0), tileRing — CW (< 0).
        // Регрессия дефекта D2 (2026-10-05): одинаковая ориентация не гасила туман.
        assertTrue(signedArea(grid.bboxRing()) > 0.0)
        assertTrue(signedArea(grid.tileRing(0)) < 0.0)
        assertTrue(signedArea(grid.tileRing(grid.tileCount - 1)) < 0.0)
    }

    @Test
    fun `bboxRing covers whole grid`() {
        val grid = TestFog.fogGrid()
        val ring = grid.bboxRing()
        assertEquals(5, ring.size)
        assertArrayEquals(ring.first(), ring.last())
        assertEquals(grid.eastLon, ring[1][0], 1e-12)
        assertEquals(grid.northLat, ring[2][1], 1e-12)
    }

    /**
     * Полевой тест 3 (2026-10-05): экстерьер завесы — весь мир ([-180,-85.05]..[180,85.05],
     * предел широт веб-меркатора), а не bbox сетки — квадратной кромки региона нет.
     */
    @Test
    fun `worldRing covers whole web mercator world counterclockwise`() {
        val ring = FogGrid.worldRing()
        assertEquals(5, ring.size)
        assertArrayEquals(ring.first(), ring.last())
        assertEquals(-180.0, ring[0][0], 1e-12)
        assertEquals(-FogGrid.WEB_MERCATOR_MAX_LATITUDE, ring[0][1], 1e-12)
        assertEquals(180.0, ring[1][0], 1e-12)
        assertEquals(FogGrid.WEB_MERCATOR_MAX_LATITUDE, ring[2][1], 1e-12)
        // Та же ориентация, что была у bboxRing (CCW, площадь > 0): дырки tileRing/runRing
        // (CW) остаются дырками для MapLibre (classifyRings, регрессия дефекта D2).
        assertTrue(signedArea(ring) > 0.0)
        // Мировое кольцо накрывает любую сетку региона целиком.
        val grid = TestFog.fogGrid()
        val bbox = grid.bboxRing()
        assertTrue(bbox.all { (lon, lat) ->
            lon >= -180.0 && lon <= 180.0 &&
                lat >= -FogGrid.WEB_MERCATOR_MAX_LATITUDE && lat <= FogGrid.WEB_MERCATOR_MAX_LATITUDE
        })
    }

    @Test
    fun `fromPackage rejects inconsistent input`() {
        val header = GridHeader(cols = 10, rows = 10, tileSizeMeters = 100.0, origin = listOf(30.0, 59.0), order = "row-major, lsb-first")
        // Нет bbox.
        assertThrowsIAE { FogGrid.fromPackage(header, bbox = emptyList()) }
        // Неподдерживаемый порядок битпака.
        assertThrowsIAE { FogGrid.fromPackage(header.copy(order = "column-major"), bbox = TestFog.BBOX) }
        // Битый origin.
        assertThrowsIAE { FogGrid.fromPackage(header.copy(origin = listOf(30.0)), bbox = TestFog.BBOX) }
        // Нулевая размерность.
        assertThrowsIAE { FogGrid.fromPackage(header.copy(cols = 0), bbox = TestFog.BBOX) }
    }

    private fun assertArrayEquals(expected: DoubleArray, actual: DoubleArray) {
        assertTrue(expected.contentEquals(actual))
    }

    /** Ориентированная площадь кольца по формуле Гаусса (x=lon, y=lat): > 0 — обход против часовой. */
    private fun signedArea(ring: List<DoubleArray>): Double {
        var twice = 0.0
        for (i in 0 until ring.size - 1) {
            twice += ring[i][0] * ring[i + 1][1] - ring[i + 1][0] * ring[i][1]
        }
        return twice / 2.0
    }

    private fun assertThrowsIAE(block: () -> Unit) {
        try {
            block()
            fail("ожидалось IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ок
        }
    }
}
