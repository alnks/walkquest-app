package app.walkquest.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Свежая позиция устройства в снимке шины (маркер «я» на карте, 2026-10-05):
 * публикуется по каждому фиксу до фильтра точек и живёт своей жизнью —
 * счётчики записи и эпоху тумана не трогает.
 */
class TrackingStateBusLocationTest {

    @Test
    fun `location update publishes position without touching recording counters`() {
        val bus = TrackingStateBus()
        bus.recordingStarted()
        bus.pointAccepted(fogChanged = true)

        bus.locationUpdated(59.9343, 30.3351, 12.5f, 1_000_000L)

        val snap = bus.snapshot.value
        assertEquals(59.9343, snap.lastLatitude!!, 1e-9)
        assertEquals(30.3351, snap.lastLongitude!!, 1e-9)
        assertEquals(12.5f, snap.lastAccuracyMeters!!, 1e-6f)
        assertEquals(1_000_000L, snap.lastFixEpochMillis)
        assertEquals(1, snap.acceptedCount)
        assertEquals(1, snap.fogEpoch)
    }

    @Test
    fun `location update works when not recording`() {
        val bus = TrackingStateBus()

        bus.locationUpdated(59.0, 30.0, null, 2_000_000L)

        val snap = bus.snapshot.value
        assertEquals(59.0, snap.lastLatitude!!, 1e-9)
        assertEquals(30.0, snap.lastLongitude!!, 1e-9)
        assertNull(snap.lastAccuracyMeters)
        assertEquals(2_000_000L, snap.lastFixEpochMillis)
        assertEquals(false, snap.isRecording)
    }

    @Test
    fun `fresh snapshot has no position`() {
        val snap = TrackingStateBus().snapshot.value
        assertNull(snap.lastLatitude)
        assertNull(snap.lastLongitude)
        assertNull(snap.lastAccuracyMeters)
        assertNull(snap.lastFixEpochMillis)
        assertNull(snap.lastAcceptedLatitude)
        assertNull(snap.lastAcceptedLongitude)
    }

    /** «Свеча» (2026-10-06): координаты принятой точки публикуются отдельно от маркерного фикса. */
    @Test
    fun `accepted point publishes its own coordinates`() {
        val bus = TrackingStateBus()
        bus.recordingStarted()
        bus.locationUpdated(59.900, 30.900, 200f, 1_000L) // маркерный сетевой фикс
        bus.pointAccepted(fogChanged = true, latitude = 59.9343, longitude = 30.3351)

        val snap = bus.snapshot.value
        assertEquals(59.9343, snap.lastAcceptedLatitude!!, 1e-9)
        assertEquals(30.3351, snap.lastAcceptedLongitude!!, 1e-9)
        // Маркерная позиция не перетёрта засчитанной точкой — это разные потоки.
        assertEquals(59.900, snap.lastLatitude!!, 1e-9)
    }

    // --- Статус «вне области пакета» (разбор 2026-10-05: OutOfRegion не молчит) ---

    @Test
    fun `coverage flag follows updates and resets on start and stop`() {
        val bus = TrackingStateBus()
        assertEquals(false, bus.snapshot.value.outsideCoverage)

        bus.recordingStarted()
        bus.coverageUpdated(outside = true)
        assertEquals(true, bus.snapshot.value.outsideCoverage)

        bus.coverageUpdated(outside = false)
        assertEquals(false, bus.snapshot.value.outsideCoverage)

        // Новая запись и остановка сбрасывают флаг.
        bus.coverageUpdated(outside = true)
        bus.recordingStarted()
        assertEquals(false, bus.snapshot.value.outsideCoverage)
        bus.coverageUpdated(outside = true)
        bus.recordingStopped()
        assertEquals(false, bus.snapshot.value.outsideCoverage)
    }
}
