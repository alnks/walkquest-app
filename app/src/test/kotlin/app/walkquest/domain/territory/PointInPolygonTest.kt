package app.walkquest.domain.territory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Point-in-polygon (AD-9: принадлежность точки региону — только в domain по границам
 * пакета): ray casting по кольцам GeoJSON — внешнее кольцо, дырки, multipolygon,
 * точки снаружи и на ребре. Синтетика здесь; фактические boundaries.geojson обоих
 * пакетов — в [RealBoundariesPointInPolygonTest] и Python-зеркале _qa/check-1-10-territory.py.
 */
class PointInPolygonTest {

    /** Квадрат [10,50]–[20,60] (lon, lat). */
    private fun square(): GeoPolygon = GeoPolygon(
        listOf(
            doubleArrayOf(10.0, 50.0, 20.0, 50.0, 20.0, 60.0, 10.0, 60.0, 10.0, 50.0),
        ),
    )

    /** Квадрат [10,50]–[20,60] с дыркой [14,54]–[16,56]. */
    private fun squareWithHole(): GeoPolygon = GeoPolygon(
        listOf(
            doubleArrayOf(10.0, 50.0, 20.0, 50.0, 20.0, 60.0, 10.0, 60.0, 10.0, 50.0),
            doubleArrayOf(14.0, 54.0, 16.0, 54.0, 16.0, 56.0, 14.0, 56.0, 14.0, 54.0),
        ),
    )

    @Test
    fun `point inside square is inside`() {
        assertTrue(PointInPolygon.contains(55.0, 15.0, square()))
    }

    @Test
    fun `points outside square are outside`() {
        assertFalse(PointInPolygon.contains(55.0, 9.99, square())) // западнее
        assertFalse(PointInPolygon.contains(55.0, 20.01, square())) // восточнее
        assertFalse(PointInPolygon.contains(49.99, 15.0, square())) // южнее
        assertFalse(PointInPolygon.contains(60.01, 15.0, square())) // севернее
        assertFalse(PointInPolygon.contains(0.0, 0.0, square())) // далеко
    }

    @Test
    fun `hole subtracts from polygon`() {
        val polygon = squareWithHole()
        assertFalse(PointInPolygon.contains(55.0, 15.0, polygon)) // в дырке — снаружи
        assertTrue(PointInPolygon.contains(52.0, 15.0, polygon)) // между краем и дыркой — внутри
        assertTrue(PointInPolygon.contains(57.0, 15.0, polygon))
    }

    @Test
    fun `point on edge is inside deterministically`() {
        assertTrue(PointInPolygon.contains(55.0, 10.0, square())) // западное ребро
        assertTrue(PointInPolygon.contains(50.0, 15.0, square())) // южное ребро
        assertTrue(PointInPolygon.contains(60.0, 15.0, square())) // северное ребро
    }

    @Test
    fun `multipolygon matches any part`() {
        val far = GeoPolygon(
            listOf(
                doubleArrayOf(30.0, 50.0, 40.0, 50.0, 40.0, 60.0, 30.0, 60.0, 30.0, 50.0),
            ),
        )
        val polygons = listOf(square(), far)
        assertTrue(PointInPolygon.containsAny(55.0, 35.0, polygons)) // во второй части
        assertTrue(PointInPolygon.containsAny(55.0, 15.0, polygons)) // в первой части
        assertFalse(PointInPolygon.containsAny(55.0, 25.0, polygons)) // между частями
    }
}

/**
 * Локатор региона: bbox-префильтр, полигоны решают, деградация (без полигонов) — bbox-тест,
 * промах по всем регионам → null.
 */
class RegionLocatorTest {

    private fun boundary(
        regionId: String,
        bbox: List<Double>,
        withPolygons: Boolean = true,
    ): RegionBoundary = RegionBoundary(
        regionId,
        bbox,
        if (withPolygons) {
            listOf(
                GeoPolygon(
                    listOf(
                        doubleArrayOf(
                            bbox[0], bbox[1], bbox[2], bbox[1], bbox[2], bbox[3],
                            bbox[0], bbox[3], bbox[0], bbox[1],
                        ),
                    ),
                ),
            )
        } else {
            emptyList()
        },
    )

    // Два непересекающихся региона (паттерн СПб + город-спутник).
    private val spb = boundary("spb-city", listOf(30.0, 59.0, 30.63, 60.071))
    private val satellite = boundary("satellite", listOf(29.5, 59.3, 29.9, 59.6))

    @Test
    fun `point is located to its own region among two`() {
        val regions = listOf(spb, satellite)
        assertEquals("spb-city", RegionLocator.locate(59.05, 30.05, regions))
        assertEquals("satellite", RegionLocator.locate(59.45, 29.7, regions))
        assertEquals("spb-city", RegionLocator.locate(59.9343, 30.3351, regions))
    }

    @Test
    fun `point outside all regions yields null`() {
        val regions = listOf(spb, satellite)
        assertNull(RegionLocator.locate(58.0, 30.0, regions))
        assertNull(RegionLocator.locate(59.45, 29.95, regions)) // между bbox регионов
        assertNull(RegionLocator.locate(0.0, 0.0, regions))
    }

    @Test
    fun `region without polygons falls back to bbox`() {
        val degraded = boundary("degraded", listOf(30.0, 59.0, 30.63, 60.071), withPolygons = false)
        assertEquals("degraded", RegionLocator.locate(59.5, 30.3, listOf(degraded)))
        assertNull(RegionLocator.locate(58.0, 30.3, listOf(degraded)))
    }

    @Test
    fun `bbox prefilter does not let polygon-less point through`() {
        // Точка внутри bbox, но снаружи полигона (полигон — левая половина bbox).
        val half = RegionBoundary(
            "half",
            listOf(30.0, 59.0, 31.0, 60.0),
            listOf(
                GeoPolygon(
                    listOf(doubleArrayOf(30.0, 59.0, 30.5, 59.0, 30.5, 60.0, 30.0, 60.0, 30.0, 59.0)),
                ),
            ),
        )
        assertNull(RegionLocator.locate(59.5, 30.75, listOf(half)))
        assertEquals("half", RegionLocator.locate(59.5, 30.25, listOf(half)))
    }
}

/** Правило домашнего региона: сохранённый → первый установленный → null. */
class HomeRegionPolicyTest {

    @Test
    fun `stored home wins when its package is installed`() {
        assertEquals(
            "gatchina",
            HomeRegionPolicy.resolve("gatchina", listOf("spb-city", "gatchina")),
        )
    }

    @Test
    fun `stored home of a deleted package falls back to first installed`() {
        assertEquals(
            "spb-city",
            HomeRegionPolicy.resolve("deleted-region", listOf("spb-city", "gatchina")),
        )
    }

    @Test
    fun `no stored home yields first installed`() {
        assertEquals("spb-city", HomeRegionPolicy.resolve(null, listOf("spb-city", "gatchina")))
    }

    @Test
    fun `no packages yields null`() {
        assertNull(HomeRegionPolicy.resolve(null, emptyList()))
        assertNull(HomeRegionPolicy.resolve("spb-city", emptyList()))
    }
}
