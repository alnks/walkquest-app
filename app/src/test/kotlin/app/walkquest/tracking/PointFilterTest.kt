package app.walkquest.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-тесты фильтра валидности точек (история 1.7): валидные цепочки,
 * превышение скорости, телепортации, немонотонное время, границы.
 * Базовая широта ~59.93 (СПб): 0.001° широты ≈ 111.3 м.
 */
class PointFilterTest {

    private fun point(lat: Double, lon: Double, tMillis: Long) = TrackPoint(lat, lon, tMillis)

    @Test
    fun `first point is always accepted`() {
        val filter = PointFilter()
        assertEquals(FilterVerdict.Accepted, filter.check(point(59.93, 30.33, 1_000L)))
    }

    @Test
    fun `walking chain within limit is accepted`() {
        val filter = PointFilter()
        // ~111 м за 60 с ≈ 1.9 м/с (6.7 км/ч) — пеший темп, лимит 12 км/ч.
        assertEquals(FilterVerdict.Accepted, filter.check(point(59.930, 30.33, 0L)))
        assertEquals(FilterVerdict.Accepted, filter.check(point(59.931, 30.33, 60_000L)))
        assertEquals(FilterVerdict.Accepted, filter.check(point(59.932, 30.33, 120_000L)))
    }

    @Test
    fun `speed above limit is rejected`() {
        val filter = PointFilter()
        filter.check(point(59.930, 30.33, 0L))
        // ~111 м за 5 с ≈ 22 м/с (80 км/ч) — выше лимита 12 км/ч.
        val verdict = filter.check(point(59.931, 30.33, 5_000L))
        assertTrue(verdict is FilterVerdict.Rejected.TooFast)
        assertEquals(SpeedLimits.WALKING_MPS, (verdict as FilterVerdict.Rejected.TooFast).limitMps, 1e-9)
    }

    @Test
    fun `boundary — speed equal to limit is accepted, above is rejected`() {
        val dtMillis = 30_000L
        val a = point(59.930, 30.33, 0L)
        val b = point(59.931, 30.33, dtMillis)
        val speed = haversineMeters(a, b) / (dtMillis / 1000.0)
        // Ровно на лимите — принимаем (условие отсечения строгое «>»).
        val atLimit = PointFilter(limitMps = speed, teleportJumpMeters = 1e9)
        atLimit.check(a)
        assertEquals(FilterVerdict.Accepted, atLimit.check(b))
        // Чуть выше лимита — отсекаем.
        val aboveLimit = PointFilter(limitMps = speed * 0.999, teleportJumpMeters = 1e9)
        aboveLimit.check(a)
        assertTrue(aboveLimit.check(b) is FilterVerdict.Rejected.TooFast)
    }

    @Test
    fun `teleport with large gap is rejected`() {
        val filter = PointFilter()
        filter.check(point(59.930, 30.33, 0L))
        // Час спустя фикс в ~55 км (скорость по dt низкая, но скачок > limit × 120 с = 400 м).
        val verdict = filter.check(point(60.430, 30.33, 3_600_000L))
        assertTrue(verdict is FilterVerdict.Rejected.Teleport)
    }

    @Test
    fun `teleport with small gap is rejected as teleport or too fast`() {
        val filter = PointFilter()
        filter.check(point(59.930, 30.33, 0L))
        // ~55 км за секунду — телепортация (проверка скачка идёт раньше скорости).
        val verdict = filter.check(point(60.430, 30.33, 1_000L))
        assertTrue(verdict is FilterVerdict.Rejected.Teleport)
    }

    @Test
    fun `non monotonic time is rejected`() {
        val filter = PointFilter()
        filter.check(point(59.930, 30.33, 10_000L))
        assertTrue(filter.check(point(59.930, 30.33, 10_000L)) is FilterVerdict.Rejected.NonMonotonicTime)
        assertTrue(filter.check(point(59.931, 30.33, 5_000L)) is FilterVerdict.Rejected.NonMonotonicTime)
    }

    @Test
    fun `rejected point does not move anchor`() {
        val filter = PointFilter()
        filter.check(point(59.930, 30.33, 0L))
        // Глитч: 80 км/ч — отвергнут, якорь остаётся на первой точке.
        assertTrue(filter.check(point(59.931, 30.33, 5_000L)) is FilterVerdict.Rejected.TooFast)
        // Следующая точка сравнивается с якорем (t=0): ~111 м за 65 с — валидна.
        assertEquals(FilterVerdict.Accepted, filter.check(point(59.931, 30.33, 65_000L)))
    }

    @Test
    fun `cycling limit accepts cycling speed`() {
        val filter = PointFilter(limitMps = SpeedLimits.CYCLING_MPS)
        filter.check(point(59.930, 30.33, 0L))
        // ~400 м за 60 с ≈ 6.6 м/с (24 км/ч): пеший фильтр отсёк бы, вело-лимит 30 км/ч принимает.
        val dLat = 400.0 / 111_320.0
        assertEquals(FilterVerdict.Accepted, filter.check(point(59.930 + dLat, 30.33, 60_000L)))
    }

    @Test
    fun `reset clears anchor`() {
        val filter = PointFilter()
        filter.check(point(59.930, 30.33, 0L))
        filter.reset()
        // После сброса точка снова «первая» — принимается без сравнения.
        assertEquals(FilterVerdict.Accepted, filter.check(point(60.5, 31.0, 5L)))
    }

    // --- Фильтр точности (разбор владельца 2026-10-05: «туман не рассеялся при записи
    // + случайные квадраты» — первые фиксы с вышек с accuracy в сотнях метров). ---

    @Test
    fun `coarse first fix is rejected and does not anchor`() {
        val filter = PointFilter()
        // Первый фикс с вышек (accuracy 800 м): отвергнут, якоря нет.
        val verdict = filter.check(TrackPoint(59.95, 30.36, 1_000L, accuracyMeters = 800f))
        assertTrue(verdict is FilterVerdict.Rejected.Inaccurate)
        assertEquals(800f, (verdict as FilterVerdict.Rejected.Inaccurate).accuracyMeters, 1e-6f)
        assertEquals(PointFilter.MAX_ACCURACY_METERS, verdict.limitMeters, 1e-6f)
        // Первый ТОЧНЫЙ фикс принят и стал якорем — тайл реальной позиции открывается сразу.
        assertEquals(
            FilterVerdict.Accepted,
            filter.check(TrackPoint(59.930, 30.33, 2_000L, accuracyMeters = 12f)),
        )
    }

    @Test
    fun `coarse fix in the middle is rejected and does not move anchor`() {
        val filter = PointFilter()
        assertEquals(
            FilterVerdict.Accepted,
            filter.check(TrackPoint(59.930, 30.33, 0L, accuracyMeters = 10f)),
        )
        // Грубый фикс-глитч (accuracy 300 м) в другом месте — отвергнут до проверки скачка.
        assertTrue(
            filter.check(TrackPoint(60.4, 30.9, 5_000L, accuracyMeters = 300f))
                is FilterVerdict.Rejected.Inaccurate,
        )
        // Якорь не сдвинулся: ~111 м за 65 с — валидная пешая точка.
        assertEquals(
            FilterVerdict.Accepted,
            filter.check(TrackPoint(59.931, 30.33, 65_000L, accuracyMeters = 15f)),
        )
    }

    @Test
    fun `fix without accuracy is treated as before`() {
        val filter = PointFilter()
        // Провайдер не дал точность (null) — поведение прежнее: первая точка принята.
        assertEquals(FilterVerdict.Accepted, filter.check(point(59.930, 30.33, 1_000L)))
    }

    @Test
    fun `accuracy exactly at threshold is accepted`() {
        val filter = PointFilter()
        // Условие отсечения строгое «>»: точность ровно на пороге 50 м — принимаем.
        assertEquals(
            FilterVerdict.Accepted,
            filter.check(TrackPoint(59.930, 30.33, 1_000L, accuracyMeters = 50f)),
        )
    }

    // --- Порог 50 м городских условий (полевой тест 2, 2026-10-05: порог 25 м браковал
    // весь городской GPS — «точек: 0, отсеяно: 34», туман не открывался). ---

    @Test
    fun `typical urban gps accuracy is accepted`() {
        val filter = PointFilter()
        // Обычная точность городского GPS в застройке (30–50 м) — не брак.
        assertEquals(
            FilterVerdict.Accepted,
            filter.check(TrackPoint(59.930, 30.33, 1_000L, accuracyMeters = 38f)),
        )
    }

    @Test
    fun `accuracy of 100 meters is rejected`() {
        val filter = PointFilter()
        // 100 м — фикс с вышек/сети, а не GPS: брак, якоря нет.
        val verdict = filter.check(TrackPoint(59.930, 30.33, 1_000L, accuracyMeters = 100f))
        assertTrue(verdict is FilterVerdict.Rejected.Inaccurate)
        // Якорь не появился: следующая точная точка — снова «первая», принята сразу.
        assertEquals(
            FilterVerdict.Accepted,
            filter.check(TrackPoint(59.930, 30.33, 2_000L, accuracyMeters = 20f)),
        )
    }

    @Test
    fun `first valid fix after recording start opens current tile`() {
        val filter = PointFilter()
        // Старт записи: первые фиксы грубые (вышки/сеть) — отбракованы, тайл не открыт.
        assertTrue(
            filter.check(TrackPoint(59.95, 30.36, 1_000L, accuracyMeters = 800f))
                is FilterVerdict.Rejected.Inaccurate,
        )
        assertTrue(
            filter.check(TrackPoint(59.94, 30.34, 2_000L, accuracyMeters = 120f))
                is FilterVerdict.Rejected.Inaccurate,
        )
        // Первый ВАЛИДНЫЙ фикс принят якорем — сервис в тот же колбэк зовёт
        // OpenTileAtPoint, и тайл текущей позиции открывается сразу после старта.
        assertEquals(
            FilterVerdict.Accepted,
            filter.check(TrackPoint(59.930, 30.33, 3_000L, accuracyMeters = 24f)),
        )
    }
    // --- Смена лимита на лету (2.2, FR-6: переключение режима во время прогулки) ---

    @Test
    fun `updateLimit changes allowed speed and teleport threshold`() {
        val filter = PointFilter()
        assertEquals(SpeedLimits.WALKING_MPS, filter.limitMps, 0.0001)
        filter.updateLimit(SpeedLimits.CYCLING_MPS)
        assertEquals(SpeedLimits.CYCLING_MPS, filter.limitMps, 0.0001)
        assertEquals(
            SpeedLimits.CYCLING_MPS * TELEPORT_WINDOW_SECONDS,
            filter.teleportJumpMeters,
            0.0001,
        )
        // Граница режимов: 150 м за 30 с = 5 м/с — пеший лимит (3,33 м/с)
        // отсекает, вело (8,33 м/с) принимает.
        val fast = TrackPoint(59.931348, 30.33, 30_000L, accuracyMeters = 5f)
        val walking = PointFilter()
        walking.check(TrackPoint(59.93, 30.33, 0L, accuracyMeters = 5f))
        assertTrue(walking.check(fast) is FilterVerdict.Rejected.TooFast)
        val cycling = PointFilter()
        cycling.updateLimit(SpeedLimits.CYCLING_MPS)
        cycling.check(TrackPoint(59.93, 30.33, 0L, accuracyMeters = 5f))
        assertEquals(FilterVerdict.Accepted, cycling.check(fast))
    }

    @Test
    fun `mode switch mid track keeps anchor`() {
        // 2.2: смена режима во время прогулки не сбрасывает якорь — честная точка
        // после переключения сравнивается с последней принятой до него.
        val filter = PointFilter()
        filter.check(TrackPoint(59.93, 30.33, 0L, accuracyMeters = 5f))
        filter.updateLimit(SpeedLimits.CYCLING_MPS)
        assertEquals(
            FilterVerdict.Accepted,
            filter.check(TrackPoint(59.9301, 30.3301, 30_000L, accuracyMeters = 5f)),
        )
    }

    @Test
    fun `limit boundary just below limit accepted just above rejected`() {
        // 12 км/ч = 3,333 м/с. Якорь принят, далее: 99 м за 30 с = 3,30 м/с —
        // принято; затем от нового якоря 99 м за 29 с = 3,41 м/с — отсечено.
        // (Ровно-на-лимите не проверяем: геодезия haversine даёт погрешность
        // больше разницы — граница закольцована с калибровкой лимитов в spike 2.3.)
        val filter = PointFilter()
        filter.check(TrackPoint(59.93, 30.33, 0L, accuracyMeters = 5f))
        assertEquals(
            FilterVerdict.Accepted,
            filter.check(TrackPoint(59.930889, 30.33, 30_000L, accuracyMeters = 5f)),
        )
        assertTrue(
            filter.check(TrackPoint(59.931778, 30.33, 59_000L, accuracyMeters = 5f))
                is FilterVerdict.Rejected.TooFast,
        )
    }
}
