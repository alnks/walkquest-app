package app.walkquest.tracking

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Хранилище снимка сессии прогулки (история 2.1, AD-5).
 *
 * Один файл `session/session.json` — снимок ПОСЛЕДНЕЙ сессии (активной или
 * завершённой). Незавершённый снимок (state ACTIVE/PAUSED) — сигнал к
 * восстановлению после убийства процесса: сервис читает его при ACTION_START,
 * находит журнал `track-<sessionId>.track` и продолжает запись (WalkSession,
 * TrackingService). FINISHED — восстановления не требует, только итог.
 *
 * Долговечность как у журнала (AD-5): запись через tmp-файл + flush + fsync +
 * rename — убийство процесса посреди save либо оставляет старый снимок, либо
 * новый целиком, полурасписанного состояния нет. Чтение битого/чужого файла —
 * [WalkSessionRead.Invalid], вызывающий трактует как «сессии нет».
 *
 * Чистый JVM-код (AD-10): файл инжектируется, Android не импортируется.
 * Room-история прогулок — позже (epic-walk-tracking, отдельная история).
 */
class WalkSessionStore(private val file: File) {

    /** Сохраняет снимок атомарно (tmp → fsync → rename). */
    fun save(snapshot: WalkSessionSnapshot): StoreSaveResult {
        return try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
            java.io.FileOutputStream(tmp).use { out ->
                out.write((SessionJson.encodeToString(snapshot.toSerializable()) + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (file.exists() && !file.delete()) {
                return StoreSaveResult.Error("не удалось заменить прежний снимок сессии")
            }
            if (!tmp.renameTo(file)) {
                tmp.delete()
                return StoreSaveResult.Error("не удалось переименовать tmp-снимок сессии")
            }
            StoreSaveResult.Ok
        } catch (e: Exception) {
            StoreSaveResult.Error(e.message ?: e.toString())
        }
    }

    /** Читает последний снимок; файла нет — [WalkSessionRead.Absent]. */
    fun load(): WalkSessionRead {
        if (!file.isFile) return WalkSessionRead.Absent
        val text = try {
            file.readBytes().decodeToString()
        } catch (e: Exception) {
            return WalkSessionRead.Invalid(e.message ?: e.toString())
        }
        val parsed = try {
            SessionJson.decodeFromString<StoredSession>(text.trim())
        } catch (e: Exception) {
            return WalkSessionRead.Invalid("снимок сессии не читается: ${e.message ?: e.toString()}")
        }
        if (parsed.formatVersion != FORMAT_VERSION) {
            return WalkSessionRead.Invalid("formatVersion снимка ${parsed.formatVersion}, поддерживается $FORMAT_VERSION")
        }
        return WalkSessionRead.Ok(parsed.toSnapshot())
    }

    companion object {
        const val FORMAT_VERSION = 1
        private const val TMP_SUFFIX = ".tmp"
        private val SessionJson = Json { ignoreUnknownKeys = true }
    }
}

/** Результат сохранения снимка. */
sealed class StoreSaveResult {
    data object Ok : StoreSaveResult()
    data class Error(val detail: String) : StoreSaveResult()
}

/** Результат чтения снимка. */
sealed class WalkSessionRead {
    data class Ok(val snapshot: WalkSessionSnapshot) : WalkSessionRead()
    data object Absent : WalkSessionRead()
    data class Invalid(val detail: String) : WalkSessionRead()
}

@Serializable
private data class StoredSession(
    val formatVersion: Int = WalkSessionStore.FORMAT_VERSION,
    val sessionId: Long,
    val startedAtEpochMillis: Long,
    val state: String,
    val distanceMeters: Double,
    val acceptedCount: Int,
    val lastAcceptedLatitude: Double? = null,
    val lastAcceptedLongitude: Double? = null,
    val pauses: List<StoredPause> = emptyList(),
    val currentPauseStartedAt: Long? = null,
    val finishedAtEpochMillis: Long? = null,
)

@Serializable
private data class StoredPause(val startedAtEpochMillis: Long, val endedAtEpochMillis: Long)

private fun WalkSessionSnapshot.toSerializable() = StoredSession(
    sessionId = sessionId,
    startedAtEpochMillis = startedAtEpochMillis,
    state = state.name,
    distanceMeters = distanceMeters,
    acceptedCount = acceptedCount,
    lastAcceptedLatitude = lastAcceptedLatitude,
    lastAcceptedLongitude = lastAcceptedLongitude,
    pauses = pauses.map { StoredPause(it.startedAtEpochMillis, it.endedAtEpochMillis) },
    currentPauseStartedAt = currentPauseStartedAt,
    finishedAtEpochMillis = finishedAtEpochMillis,
)

private fun StoredSession.toSnapshot() = WalkSessionSnapshot(
    sessionId = sessionId,
    startedAtEpochMillis = startedAtEpochMillis,
    state = WalkState.valueOf(state),
    distanceMeters = distanceMeters,
    acceptedCount = acceptedCount,
    lastAcceptedLatitude = lastAcceptedLatitude,
    lastAcceptedLongitude = lastAcceptedLongitude,
    pauses = pauses.map { PauseInterval(it.startedAtEpochMillis, it.endedAtEpochMillis) },
    currentPauseStartedAt = currentPauseStartedAt,
    finishedAtEpochMillis = finishedAtEpochMillis,
)
