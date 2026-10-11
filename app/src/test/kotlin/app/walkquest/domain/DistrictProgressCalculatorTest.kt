package app.walkquest.domain

import app.walkquest.data.boundaries.BoundaryRepository
import app.walkquest.data.boundaries.DistrictBoundary
import app.walkquest.data.fog.FogGrid
import app.walkquest.data.packages.InstalledPackage
import app.walkquest.domain.territory.GeoPolygon
import app.walkquest.domain.territory.PointInPolygon
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM-тесты районного прогресса (история 4.3): геометрия считается по реальным
 * dx/dy сетки (cos широты), район строится в тех же координатах.
 */
class DistrictProgressCalculatorTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** Сетка 10×10, origin (30.0, 59.0), шаг ≈ 0,001° по широте. */
    private fun grid(): FogGrid = FogGrid(
        cols = 10, rows = 10, tileSizeMeters = 111.32,
        originLon = 30.0, originLat = 59.0,
        bboxMinLat = 59.0, bboxMaxLat = 59.011,
    )

    /** Квадратный район: клетки с col 2..5 и row 2..5 (центры внутри, 4×4). */
    private fun squareDistrict(g: FogGrid): DistrictBoundary {
        val west = g.originLon + 2 * g.dx
        val east = g.originLon + 6 * g.dx
        val south = g.originLat + 2 * g.dy
        val north = g.originLat + 6 * g.dy
        val ring = doubleArrayOf(west, south, west, north, east, north, east, south, west, south)
        return DistrictBoundary("spb-city", "Квадратный", listOf(GeoPolygon(listOf(ring))))
    }

    @Test
    fun `cell center roundtrips through indexAt`() {
        val g = grid()
        for (index in listOf(0, 11, 33, 77, g.tileCount - 1)) {
            val center = DistrictProgressCalculator.cellCenter(g, index)
            assertEquals(index, g.indexAt(center[1], center[0]))
        }
    }

    @Test
    fun `district polygon contains its cell centers only`() {
        val g = grid()
        val d = squareDistrict(g)
        // Клетки 2..5 × 2..5 — внутри.
        for (row in 2..5) {
            for (col in 2..5) {
                val c = DistrictProgressCalculator.cellCenter(g, row * g.cols + col)
                assertTrue("клетка $col,$row вне района", PointInPolygon.containsAny(c[1], c[0], d.polygons))
            }
        }
        // Клетка (0,0) и (9,9) — вне.
        val c00 = DistrictProgressCalculator.cellCenter(g, 0)
        assertFalse(PointInPolygon.containsAny(c00[1], c00[0], d.polygons))
        val c99 = DistrictProgressCalculator.cellCenter(g, 99)
        assertFalse(PointInPolygon.containsAny(c99[1], c99[0], d.polygons))
        assertTrue(d.inBbox(59.004, 30.004))
        assertFalse(d.inBbox(59.0095, 30.0095))
    }

    @Test
    fun `district progress value semantics`() {
        assertTrue(DistrictProgress("Квадратный", 16, 16, 1.0).fullyOpened)
        assertFalse(DistrictProgress("Квадратный", 8, 16, 0.5).fullyOpened)
        assertFalse(DistrictProgress("Квадратный", 0, 0, 0.0).fullyOpened) // деления на ноль нет
        assertEquals(0.5, DistrictProgress("Квадратный", 8, 16, 0.5).fraction, 1e-9)
    }

    @Test
    fun `district boundary parses from geojson kind field`() {
        val pkgDir = File(temp.root, "packages/spb-city-v1")
        pkgDir.mkdirs()
        File(pkgDir, "meta.json").writeText(
            """{"formatVersion":1,"regionId":"spb-city","version":1,"tileSizeMeters":100.0,"bbox":[30.0,59.0,30.7,60.0],"maxzoom":14}""",
        )
        val geojson = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","properties":{"kind":"city","name":"СПб"},
               "geometry":{"type":"Polygon","coordinates":[[[30.0,59.0],[30.7,59.0],[30.7,60.0],[30.0,60.0],[30.0,59.0]]]}},
              {"type":"Feature","properties":{"kind":"district","name":"Центральный"},
               "geometry":{"type":"Polygon","coordinates":[[[30.1,59.9],[30.2,59.9],[30.2,59.95],[30.1,59.95],[30.1,59.9]]]}}
            ]}
        """.trimIndent()
        File(pkgDir, "boundaries.geojson").writeText(geojson)
        val pkg = InstalledPackage("spb-city", 1, 1, pkgDir)
        val districts = BoundaryRepository().loadDistricts(pkg)
        assertEquals(1, districts.size)
        assertEquals("Центральный", districts[0].name)
        assertEquals("spb-city", districts[0].regionId)
        // Городской полигон (kind=city) в районы не попал.
        assertEquals(0, districts.count { it.name == "СПб" })
    }
}
