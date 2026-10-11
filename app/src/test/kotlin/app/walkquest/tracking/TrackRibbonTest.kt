package app.walkquest.tracking

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Геометрия «свечи v2» (разбор владельца 2026-10-06): буферная тропа вокруг
 * осевой трека ([trailRing] — одно кольцо CW, круглые стыки/торцы), круг из
 * точки для вырожденной тропы ([circleRing]) и дедупликация осевой (точка
 * ближе 9 м к последней принятой в ленту её не продлевает).
 */
class TrackRibbonTest {

    @Test
    fun `circleRing is a closed ring with vertices plus closing point`() {
        val ring = circleRing(59.93, 30.33, REVEAL_RADIUS_METERS)
        assertEquals(REVEAL_CIRCLE_VERTICES + 1, ring.size)
        assertTrue(ring.first().contentEquals(ring.last()))
    }

    @Test
    fun `circleRing vertices sit at reveal radius from center`() {
        val lat = 59.93
        val lon = 30.33
        val ring = circleRing(lat, lon, REVEAL_RADIUS_METERS)
        val center = TrackPoint(lat, lon, 0L)
        ring.dropLast(1).forEach { vertex ->
            val distance = haversineMeters(center, TrackPoint(vertex[1], vertex[0], 0L))
            assertEquals(REVEAL_RADIUS_METERS, distance, 0.6) // аппроксимация метрики сетки
        }
    }

    @Test
    fun `circleRing winds clockwise like tileRing so MapLibre treats it as a hole`() {
        // Регрессия дефекта D2 (2026-10-05): дыркой признаётся только кольцо с
        // ориентацией, противоположной мировому экстерьеру (CCW, площадь > 0).
        assertTrue(signedArea(circleRing(59.93, 30.33, REVEAL_RADIUS_METERS)) < 0.0)
    }

    @Test
    fun `circleRing rejects degenerate vertex counts`() {
        try {
            circleRing(59.93, 30.33, REVEAL_RADIUS_METERS, vertices = 2)
            org.junit.Assert.fail("ожидалось IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ок
        }
    }

    // --- Буферная тропа (trailRing) ---

    @Test
    fun `trailRing of empty or single point falls back to nothing or circle`() {
        assertTrue(trailRing(emptyList()).isEmpty())
        val single = trailRing(listOf(TrackPoint(59.93, 30.33, 0L)))
        assertEquals(REVEAL_CIRCLE_VERTICES + 1, single.size)
        assertTrue(signedArea(single) < 0.0)
    }

    @Test
    fun `trailRing of straight line is a closed CW ring with round caps`() {
        // Осевая 100 м строго на север, радиус 15 м.
        val r = 15.0
        val points = (0..4).map { TrackPoint(59.93 + it * (25.0 / 111_320.0), 30.33, it * 1_000L) }
        val ring = trailRing(points, r)
        assertTrue(ring.first().contentEquals(ring.last()))
        assertTrue("кольцо должно быть CW (дырка для MapLibre)", signedArea(ring) < 0.0)
        // Ширина тропы по долготе ≈ 2R; длина по широте ≈ 100 м + 2R (торцы-полукруги).
        val lonHalfWidthMeters = ring.maxOf { it[0] } - ring.minOf { it[0] }
        val latSpanMeters = (ring.maxOf { it[1] } - ring.minOf { it[1] }) * 111_320.0
        val metersPerLon = 111_320.0 * cos(Math.toRadians(59.93))
        assertEquals(2 * r, lonHalfWidthMeters * metersPerLon, 0.5)
        assertEquals(100.0 + 2 * r, latSpanMeters, 0.5)
        // Круглые торцы: есть вершины южнее старта и севернее финиша почти на R.
        assertTrue(ring.minOf { it[1] } < 59.93)
        assertTrue(ring.maxOf { it[1] } > points.last().latitude)
    }

    @Test
    fun `trailRing vertices never dip inside the buffer radius`() {
        // Инвариант буфера: каждая вершина кольца не ближе R к осевой (срез и
        // дуги не режут тропу). Осевая с двумя поворотами (север → восток → север).
        val r = 15.0
        val axis = mutableListOf<TrackPoint>()
        var lat = 59.9300
        var lon = 30.3300
        repeat(5) { axis.add(TrackPoint(lat, lon, 0L)); lat += 20.0 / 111_320.0 }
        val metersPerLon = 111_320.0 * cos(Math.toRadians(lat))
        repeat(5) { axis.add(TrackPoint(lat, lon, 0L)); lon += 20.0 / metersPerLon }
        repeat(5) { axis.add(TrackPoint(lat, lon, 0L)); lat += 20.0 / 111_320.0 }
        val ring = trailRing(axis, r)
        ring.dropLast(1).forEach { v ->
            val d = distanceToPolylineMeters(v[1], v[0], axis)
            assertTrue("вершина ближе радиуса к осевой: $d м", d >= r - 0.3)
        }
    }

    @Test
    fun `trailRing rounds the outer side of a turn`() {
        // Поворот на 90°: на внешней стороне угла есть вершины дуги — точки
        // кольца на расстоянии ≈ R от вершины поворота в сторону внешнего угла.
        val r = 15.0
        val cornerLat = 59.93 + 50.0 / 111_320.0
        val cornerLon = 30.33
        val axis = listOf(
            TrackPoint(59.93, 30.33, 0L),
            TrackPoint(cornerLat, cornerLon, 1_000L),
            TrackPoint(cornerLat, cornerLon + 50.0 / (111_320.0 * cos(Math.toRadians(cornerLat))), 2_000L),
        )
        val ring = trailRing(axis, r)
        // Внешняя сторона поворота «север → восток» (правый поворот) — северо-
        // ЗАПАД от угла: ищем вершины дуги на расстоянии ~R от угла там.
        val corner = TrackPoint(cornerLat, cornerLon, 0L)
        val outerArc = ring.dropLast(1).filter { v ->
            v[1] > cornerLat && v[0] < cornerLon &&
                abs(haversineMeters(corner, TrackPoint(v[1], v[0], 0L)) - r) < 0.5
        }
        assertTrue("дуга round join на внешней стороне не найдена", outerArc.size >= 2)
    }

    @Test
    fun `trailRing handles a hairpin without exploding`() {
        // Разворот на 180° (срез за пределом лимита): кольцо замкнуто, CW,
        // разброс вершин остаётся в пределах ~нескольких радиусов от осевой.
        val r = 15.0
        val step = 20.0 / 111_320.0
        val axis = listOf(
            TrackPoint(59.93, 30.33, 0L),
            TrackPoint(59.93 + step, 30.33, 1_000L),
            TrackPoint(59.93 + step, 30.33 + 0.0002, 2_000L),
            TrackPoint(59.93, 30.33 + 0.0002, 3_000L),
        )
        val ring = trailRing(axis, r)
        assertTrue(ring.first().contentEquals(ring.last()))
        assertTrue(signedArea(ring) < 0.0)
        ring.forEach { v ->
            val d = distanceToPolylineMeters(v[1], v[0], axis)
            assertTrue("вершина улетела от осевой: $d м", d <= MITER_EXPECTED_LIMIT * r)
        }
    }

    @Test
    fun `builder dedupes points closer than the step to the last kept circle`() {
        val builder = RibbonBuilder()
        // Шаг ~1,4 м (секундный фикс пешком): ближе 9 м — точки осевой не добавляются.
        val base = TrackPoint(59.93000, 30.33000, 1_000L)
        assertTrue(builder.add(base))
        var t = 1_000L
        repeat(5) { i ->
            t += 1_000
            assertFalse(builder.add(TrackPoint(59.93000 + 0.0000126 * (i + 1), 30.33000, t)))
        }
        assertEquals(1, builder.points.size)
        // Ушли дальше 9 м от последней точки осевой — осевая продлена.
        t += 1_000
        assertTrue(builder.add(TrackPoint(59.93000 + 0.0000126 * 10, 30.33000, t)))
        assertEquals(2, builder.points.size)
    }

    @Test
    fun `dedup is chained against the last kept circle, not the last point`() {
        // Серия точек по 8 м: против последней ТОЧКИ каждая «далеко», против
        // последней ПРИНЯТОЙ — каждая вторая сливается (дрейфа порога нет).
        val builder = RibbonBuilder(dedupMeters = 12.0)
        var t = 1_000L
        val stepLat = 8.0 / 111_320.0
        repeat(10) {
            builder.add(TrackPoint(59.93 + it * stepLat, 30.33, t))
            t += 1_000
        }
        // Точки осевой на 0, 16, 32, 48, 64 м от старта: 5 на 72 м цепочки.
        assertEquals(5, builder.points.size)
    }

    @Test
    fun `addAll rebuilds ribbon from journal replay with dedup`() {
        val builder = RibbonBuilder()
        // 100 секундных фиксов с шагом ~1,4 м → 140 м цепочки → ~15 точек осевой
        // (дедуп шагом 9 м: 0, 9,8, 19,6, …).
        val points = (0 until 100).map { i ->
            TrackPoint(59.93 + i * (1.4 / 111_320.0), 30.33, (i + 1) * 1_000L)
        }
        val added = builder.addAll(points)
        assertEquals(15, added)
        assertEquals(added, builder.points.size)
    }

    /** Ориентированная площадь кольца (x=lon, y=lat): > 0 — CCW, < 0 — CW. */
    private fun signedArea(ring: List<DoubleArray>): Double {
        var twice = 0.0
        for (i in 0 until ring.size - 1) {
            twice += ring[i][0] * ring[i + 1][1] - ring[i + 1][0] * ring[i][1]
        }
        return twice / 2.0
    }

    /** Расстояние от точки (lat/lon) до полилинии осевой в метрах (локальная проекция). */
    private fun distanceToPolylineMeters(lat: Double, lon: Double, axis: List<TrackPoint>): Double {
        val origin = axis.first()
        val metersPerLon = 111_320.0 * cos(Math.toRadians(origin.latitude))
        fun toMeters(p: TrackPoint) = doubleArrayOf(
            (p.longitude - origin.longitude) * metersPerLon,
            (p.latitude - origin.latitude) * 111_320.0,
        )
        val px = (lon - origin.longitude) * metersPerLon
        val py = (lat - origin.latitude) * 111_320.0
        var best = Double.MAX_VALUE
        for (i in 0 until axis.size - 1) {
            val a = toMeters(axis[i])
            val b = toMeters(axis[i + 1])
            val abx = b[0] - a[0]
            val aby = b[1] - a[1]
            val len2 = abx * abx + aby * aby
            val t = if (len2 < 1e-12) 0.0 else
                ((px - a[0]) * abx + (py - a[1]) * aby) / len2
            val tc = t.coerceIn(0.0, 1.0)
            val dx = px - (a[0] + abx * tc)
            val dy = py - (a[1] + aby * tc)
            best = minOf(best, sqrt(dx * dx + dy * dy))
        }
        return best
    }
}

/** Предел разброса вершин кольца на развороте, в радиусах (см. MITER_LIMIT_RADIUS). */
private const val MITER_EXPECTED_LIMIT = 5.0
