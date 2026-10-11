package app.walkquest.tracking

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * История треков → принятые точки ленты «свечи» (2026-10-06): replay PointFilter
 * над журналами восстанавливает ровно те точки, что открывали туман при жизни
 * (accuracy теперь хранится в журнале — вердикты повторяются один-в-один).
 */
class TrackHistoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val tracksDir: File get() = File(temp.root, "tracks")

    private fun writeJournal(name: String, points: List<TrackPoint>) {
        // Заголовок согласован с точками (replay отсекает фиксы старше старта записи).
        val journal = TrackJournal(File(tracksDir, name), startedAtEpochMillis = points.first().epochMillis)
        points.forEach { assertEquals(JournalAppendResult.Ok, journal.append(it)) }
    }

    /** Честная пешая цепочка: шаг ~1,4 м, секунда между фиксами, accuracy 10 м. */
    private fun walk(startLat: Double, startLon: Double, count: Int, startT: Long): List<TrackPoint> =
        (0 until count).map { i ->
            TrackPoint(startLat + i * (1.4 / 111_320.0), startLon, startT + i * 1_000L, 10f)
        }

    @Test
    fun `accepted points are replayed through the same filter as live recording`() {
        val walk = walk(59.930, 30.330, 20, 1_000_000L)
        // Грубый сетевой фикс (accuracy 300 м) посреди прогулки — при жизни отсечен
        // фильтром, в ленту не попадает (replay повторяет вердикт благодаря "acc").
        val points = walk.take(10) +
            TrackPoint(59.9305, 30.3305, 1_010_000L, 300f) +
            walk.drop(10)
        writeJournal("track-1000.track", points)

        val accepted = TrackHistory(tracksDir).acceptedPoints()
        assertEquals(walk.size, accepted.size)
        assertEquals(walk, accepted)
    }

    @Test
    fun `teleport jump inside a journal is rejected on replay`() {
        val before = walk(59.930, 30.330, 5, 1_000_000L)
        val jump = TrackPoint(59.99, 30.99, 1_005_000L, 10f) // ~8 км — телепортация
        val after = walk(59.930 + 5 * (1.4 / 111_320.0), 30.330, 5, 1_006_000L)
        writeJournal("track-1000.track", before + jump + after)

        val accepted = TrackHistory(tracksDir).acceptedPoints()
        assertEquals(before + after, accepted)
    }

    @Test
    fun `each journal replays with a fresh filter anchor`() {
        // Две записи подряд: вторая начинается далеко от конца первой — с общим
        // якорем первая точка второй записи отсекалась бы как телепортация.
        writeJournal("track-1000.track", walk(59.930, 30.330, 5, 1_000_000L))
        writeJournal("track-2000.track", walk(59.940, 30.340, 5, 2_000_000L))

        val accepted = TrackHistory(tracksDir).acceptedPoints()
        assertEquals(10, accepted.size)
        // Хронология: сначала старший журнал, внутри — порядок записи.
        assertEquals(59.930, accepted.first().latitude, 1e-9)
        assertEquals(59.940, accepted[5].latitude, 1e-9)
    }

    @Test
    fun `broken journals and foreign files are skipped`() {
        writeJournal("track-1000.track", walk(59.930, 30.330, 3, 1_000_000L))
        File(tracksDir, "track-2000.track").writeText("мусор, а не заголовок\n")
        File(tracksDir, "notes.txt").writeText("не журнал\n")
        File(tracksDir, "track-3000.bak").writeText("не журнал\n")

        val accepted = TrackHistory(tracksDir).acceptedPoints()
        assertEquals(3, accepted.size)
    }

    @Test
    fun `old journal without accuracy field replays with null accuracy`() {
        // Журнал формата до 2026-10-06 (строки без "acc"): читается, replay идёт
        // с accuracy = null — фикс без accuracy браком не считается (PointFilter).
        File(tracksDir, "track-1000.track").apply {
            parentFile.mkdirs()
            writeText(
                "{\"formatVersion\":1,\"startedAtEpochMillis\":0}\n" +
                    "{\"lat\":59.930,\"lon\":30.330,\"t\":1000}\n" +
                    "{\"lat\":59.93001,\"lon\":30.330,\"t\":2000}\n",
            )
        }
        val accepted = TrackHistory(tracksDir).acceptedPoints()
        assertEquals(2, accepted.size)
        assertEquals(null, accepted[0].accuracyMeters)
    }

    @Test
    fun `missing tracks directory yields no points`() {
        assertTrue(TrackHistory(tracksDir).acceptedPoints().isEmpty())
    }
}
