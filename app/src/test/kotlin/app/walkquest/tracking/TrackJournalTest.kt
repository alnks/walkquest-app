package app.walkquest.tracking

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM-тесты append-only журнала (история 1.7, AD-5): дозапись, чтение,
 * усечённый хвост после убийства процесса, битый заголовок, дозапись после переоткрытия.
 */
class TrackJournalTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun journal(name: String = "track-1.track"): Pair<TrackJournal, File> {
        val file = File(temp.root, "tracks/$name")
        return TrackJournal(file) to file
    }

    @Test
    fun `append then read roundtrip preserves points and order`() {
        val (journal, _) = journal()
        val points = listOf(
            TrackPoint(59.930, 30.330, 1_000L),
            TrackPoint(59.931, 30.331, 2_000L),
            TrackPoint(59.932, 30.332, 3_000L),
        )
        points.forEach { assertEquals(JournalAppendResult.Ok, journal.append(it)) }

        val read = journal.read()
        assertTrue(read is JournalRead.Ok)
        read as JournalRead.Ok
        assertEquals(points, read.points)
        assertFalse(read.truncatedTail)
    }

    @Test
    fun `every point is on disk immediately after append`() {
        val (journal, file) = journal()
        journal.append(TrackPoint(59.93, 30.33, 1_000L))
        // AD-5: без вызова flush/close снаружи точка уже читается с диска
        // новым экземпляром читателя (убийство процесса теряет секунды, не трек).
        val read = TrackJournal(file).read()
        assertTrue(read is JournalRead.Ok)
        assertEquals(1, (read as JournalRead.Ok).points.size)
    }

    @Test
    fun `truncated tail after process kill keeps valid prefix`() {
        val (journal, file) = journal()
        val points = (0 until 5).map { TrackPoint(59.93 + it * 0.001, 30.33, (it + 1) * 1_000L) }
        points.forEach { journal.append(it) }
        // Симуляция убийства процесса: файл обрезан посреди последней строки.
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size - 7))

        val read = journal.read()
        assertTrue(read is JournalRead.Ok)
        read as JournalRead.Ok
        assertTrue(read.truncatedTail)
        assertEquals(points.dropLast(1), read.points)
    }

    @Test
    fun `garbage tail line is tolerated as truncation`() {
        val (journal, file) = journal()
        journal.append(TrackPoint(59.93, 30.33, 1_000L))
        file.appendBytes("{\"lat\":59.9,\"lon\":\u0000\u0001garbage".toByteArray())

        val read = journal.read()
        assertTrue(read is JournalRead.Ok)
        read as JournalRead.Ok
        assertTrue(read.truncatedTail)
        assertEquals(1, read.points.size)
    }

    @Test
    fun `unreadable header yields Invalid`() {
        val file = File(temp.root, "tracks/broken.track").apply {
            parentFile.mkdirs()
            writeText("это не json\n{\"lat\":59.93,\"lon\":30.33,\"t\":1000}\n")
        }
        val read = TrackJournal(file).read()
        assertTrue(read is JournalRead.Invalid)
    }

    @Test
    fun `unsupported formatVersion yields Invalid`() {
        val file = File(temp.root, "tracks/v99.track").apply {
            parentFile.mkdirs()
            writeText("{\"formatVersion\":99,\"startedAtEpochMillis\":0}\n")
        }
        val read = TrackJournal(file).read()
        assertTrue(read is JournalRead.Invalid)
    }

    @Test
    fun `missing file yields Invalid`() {
        val read = TrackJournal(File(temp.root, "tracks/absent.track")).read()
        assertTrue(read is JournalRead.Invalid)
    }

    @Test
    fun `header only journal reads as empty`() {
        // «Запись началась, точек не было»: файл с одним заголовком.
        val file = File(temp.root, "tracks/empty.track").apply {
            parentFile.mkdirs()
            writeText("{\"formatVersion\":1,\"startedAtEpochMillis\":0}\n")
        }
        val read = TrackJournal(file).read()
        assertTrue(read is JournalRead.Ok)
        read as JournalRead.Ok
        assertTrue(read.points.isEmpty())
        assertFalse(read.truncatedTail)
    }

    @Test
    fun `append continues after reopening journal instance`() {
        val (first, file) = journal()
        first.append(TrackPoint(59.930, 30.33, 1_000L))
        // Переоткрытие (после перезапуска приложения): заголовок не дублируется, точки дописываются.
        val second = TrackJournal(file)
        second.append(TrackPoint(59.931, 30.33, 2_000L))

        val read = TrackJournal(file).read()
        assertTrue(read is JournalRead.Ok)
        read as JournalRead.Ok
        assertEquals(2, read.points.size)
        assertEquals(59.931, read.points[1].latitude, 1e-9)
    }

    @Test
    fun `accuracy roundtrips through the journal line`() {
        // 2026-10-06 («свеча»): журнал хранит accuracy ("acc"), чтобы посмертный
        // replay PointFilter (TrackHistory) повторил вердикты живой записи.
        val (journal, _) = journal()
        val points = listOf(
            TrackPoint(59.930, 30.330, 1_000L, 12.5f),
            TrackPoint(59.931, 30.331, 2_000L, null), // провайдер не дал accuracy
        )
        points.forEach { journal.append(it) }

        val read = journal.read()
        assertTrue(read is JournalRead.Ok)
        assertEquals(points, (read as JournalRead.Ok).points)
    }

    @Test
    fun `journal lines without accuracy field read with null accuracy`() {
        // Обратная совместимость: журналы, записанные до появления "acc".
        val file = File(temp.root, "tracks/old.track").apply {
            parentFile.mkdirs()
            writeText(
                "{\"formatVersion\":1,\"startedAtEpochMillis\":0}\n" +
                    "{\"lat\":59.93,\"lon\":30.33,\"t\":1000}\n",
            )
        }
        val read = TrackJournal(file).read()
        assertTrue(read is JournalRead.Ok)
        read as JournalRead.Ok
        assertEquals(1, read.points.size)
        assertEquals(null, read.points[0].accuracyMeters)
    }

    @Test
    fun `service-style header always carries startedAtEpochMillis`() {
        // Регрессия 2026-10-09: дефолт заголовка был currentTimeMillis — при
        // encodeDefaults=false значение совпадало с дефолтом в том же миллисекунде,
        // заголовок записывался как "{}", и при чтении startedAt становился
        // «временем чтения» → replay TrackHistory отсекал все точки (StaleFix).
        val (journal, file) = journal() // startedAt = «сейчас», как у сервиса
        journal.append(TrackPoint(59.93, 30.33, 1_000L))
        val headerLine = file.readLines().first()
        assertTrue(
            "заголовок обязан нести startedAtEpochMillis, фактически: $headerLine",
            headerLine.contains("startedAtEpochMillis"),
        )
        val read = TrackJournal(file).read()
        assertTrue(read is JournalRead.Ok)
        assertTrue((read as JournalRead.Ok).startedAtEpochMillis > 0L)
    }

    @Test
    fun `legacy empty header reads with zero startedAt`() {
        // Журналы 2026-10-08..09 с заголовком "{}" (см. регрессию выше):
        // startedAt=0 отключает правило StaleFix — точки replay'ятся.
        val file = File(temp.root, "tracks/legacy.track").apply {
            parentFile.mkdirs()
            writeText("{}\n{\"lat\":59.93,\"lon\":30.33,\"t\":1000,\"acc\":5.0}\n")
        }
        val read = TrackJournal(file).read()
        assertTrue(read is JournalRead.Ok)
        read as JournalRead.Ok
        assertEquals(0L, read.startedAtEpochMillis)
        assertEquals(1, read.points.size)
    }
}
