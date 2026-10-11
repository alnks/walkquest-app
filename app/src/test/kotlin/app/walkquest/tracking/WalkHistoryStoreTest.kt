package app.walkquest.tracking

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM-тесты истории прогулок (история 3.1, FR-13): append/чтение round-trip,
 * долговечность сразу после append (AD-5), усечённый хвост, пустая история.
 */
class WalkHistoryStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(): Pair<WalkHistoryStore, File> {
        val file = File(temp.root, "walks/walks.jsonl")
        return WalkHistoryStore(file) to file
    }

    private fun record(id: Long) = WalkRecord(
        sessionId = id,
        startedAtEpochMillis = id,
        finishedAtEpochMillis = id + 3_600_000L,
        distanceMeters = 5_000.0 + id,
        activeMillis = 3_000_000L,
        acceptedCount = 100,
    )

    @Test
    fun `append then read roundtrip preserves records and order`() {
        val (store, _) = store()
        assertEquals(HistoryAppendResult.Ok, store.append(record(1)))
        assertEquals(HistoryAppendResult.Ok, store.append(record(2)))
        assertEquals(HistoryAppendResult.Ok, store.append(record(3)))
        val read = store.read()
        assertFalse(read.truncatedTail)
        assertEquals(listOf(record(1), record(2), record(3)), read.records)
    }

    @Test
    fun `record is on disk immediately after append`() {
        val (store, file) = store()
        store.append(record(42))
        // AD-5: без закрытия снаружи запись читается новым экземпляром.
        val read = WalkHistoryStore(file).read()
        assertEquals(listOf(record(42)), read.records)
    }

    @Test
    fun `truncated tail after process kill keeps valid prefix`() {
        val (store, file) = store()
        store.append(record(1))
        store.append(record(2))
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size - 5))
        val read = store.read()
        assertTrue(read.truncatedTail)
        assertEquals(listOf(record(1)), read.records)
    }

    @Test
    fun `empty history reads empty without tail flag`() {
        val (store, _) = store()
        val read = store.read()
        assertFalse(read.truncatedTail)
        assertTrue(read.records.isEmpty())
    }

    @Test
    fun `garbage line truncates and keeps prefix`() {
        val (store, file) = store()
        store.append(record(1))
        file.appendText("не-json\n")
        val read = store.read()
        assertTrue(read.truncatedTail)
        assertEquals(listOf(record(1)), read.records)
    }
}
