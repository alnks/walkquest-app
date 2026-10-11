package app.walkquest.tracking

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM-тесты хранилища сессии (история 2.1, AD-5): save/load круглый, атомарность
 * (tmp+rename, без .tmp-мусора), битый файл и чужой formatVersion, различение
 * незавершённой и завершённой сессии (сигнал восстановления).
 */
class WalkSessionStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(name: String = "session/session.json"): Pair<WalkSessionStore, File> {
        val file = File(temp.root, name)
        return WalkSessionStore(file) to file
    }

    private fun activeSession(startedAt: Long = 1_000L) = WalkSession(startedAtEpochMillis = startedAt)

    @Test
    fun `save then load roundtrip preserves full snapshot`() {
        val (store, _) = store()
        val session = activeSession(5_000L)
        session.acceptPoint(TrackPoint(59.9, 30.3, 6_000L))
        session.pause(7_000L)
        assertEquals(StoreSaveResult.Ok, store.save(session.snapshot()))

        val read = store.load()
        assertTrue(read is WalkSessionRead.Ok)
        val snapshot = (read as WalkSessionRead.Ok).snapshot
        assertEquals(5_000L, snapshot.sessionId)
        assertEquals(WalkState.PAUSED, snapshot.state)
        assertEquals(1, snapshot.acceptedCount)
        assertEquals(session.distanceMeters, snapshot.distanceMeters, 0.0001)
        // Пауза ещё открыта: закрытых интервалов нет, её начало — в currentPauseStartedAt.
        assertEquals(0, snapshot.pauses.size)
        assertEquals(7_000L, snapshot.currentPauseStartedAt)
        assertEquals(null, snapshot.finishedAtEpochMillis)
    }

    @Test
    fun `finished session roundtrip keeps finish time and state`() {
        val (store, _) = store()
        val session = activeSession(0L)
        session.finish(600_000L)
        assertEquals(StoreSaveResult.Ok, store.save(session.snapshot()))

        val read = store.load()
        assertTrue(read is WalkSessionRead.Ok)
        val snapshot = (read as WalkSessionRead.Ok).snapshot
        assertEquals(WalkState.FINISHED, snapshot.state)
        assertEquals(600_000L, snapshot.finishedAtEpochMillis)
    }

    @Test
    fun `restore snapshot recovers equivalent session`() {
        val (store, _) = store()
        val session = activeSession(0L)
        session.acceptPoint(TrackPoint(59.9, 30.3, 1_000L))
        session.acceptPoint(TrackPoint(59.901, 30.3, 2_000L))
        store.save(session.snapshot())

        val restored = WalkSession.restore((store.load() as WalkSessionRead.Ok).snapshot)
        assertEquals(session.distanceMeters, restored.distanceMeters, 0.0001)
        assertEquals(session.acceptedCount, restored.acceptedCount)
        assertEquals(WalkState.ACTIVE, restored.state)
        // Продолжаем учёт: дистанция считается от последней принятой точки снимка.
        restored.acceptPoint(TrackPoint(59.902, 30.3, 3_000L))
        assertTrue(restored.distanceMeters > session.distanceMeters)
    }

    @Test
    fun `no file reads as absent`() {
        val (store, _) = store()
        assertEquals(WalkSessionRead.Absent, store.load())
    }

    @Test
    fun `corrupt file reads as invalid not crash`() {
        val (store, file) = store()
        file.parentFile?.mkdirs()
        file.writeText("{ not json")
        assertTrue(store.load() is WalkSessionRead.Invalid)
    }

    @Test
    fun `foreign format version reads as invalid`() {
        val (store, file) = store()
        file.parentFile?.mkdirs()
        file.writeText(
            """{"formatVersion":99,"sessionId":1,"startedAtEpochMillis":1,"state":"ACTIVE",""" +
                """"distanceMeters":0.0,"acceptedCount":0}""",
        )
        val read = store.load()
        assertTrue(read is WalkSessionRead.Invalid)
    }

    @Test
    fun `save leaves no tmp garbage and replaces previous snapshot`() {
        val (store, file) = store()
        store.save(activeSession(1L).snapshot())
        store.save(activeSession(2L).snapshot())
        assertFalse(File(file.parentFile, file.name + ".tmp").exists())
        val read = store.load()
        assertTrue(read is WalkSessionRead.Ok)
        assertEquals(2L, (read as WalkSessionRead.Ok).snapshot.sessionId)
    }
}
