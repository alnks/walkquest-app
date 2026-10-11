package app.walkquest.tracking

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * История завершённых прогулок (история 3.1, FR-13) — append-only JSONL
 * `walks/walks.jsonl`: каждая завершённая сессия — одна запись. Формат и
 * долговечность — как у журнала трека (AD-5): append + flush + fsync на запись,
 * чтение терпимо к усечённому хвосту (убийство процесса). Снимок
 * session/session.json — для восстановления живой сессии, историю не дублирует.
 *
 * Источник истины — устройство (FR-13): сервис дописывает запись при финале
 * сессии; агрегаты (серии, статистика, цель) читают ТОЛЬКО отсюда (domain).
 *
 * Чистый JVM-код (AD-10): файл инжектируется, Android не импортируется.
 */

/** Завершённая прогулка — запись истории; время epoch millis UTC.
 *  Поля v2 (4.1): regionIds/modes/newTilesOpened — у старых записей дефолты. */
data class WalkRecord(
    val sessionId: Long,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long,
    val distanceMeters: Double,
    val activeMillis: Long,
    val acceptedCount: Int,
    /** Регионы, где прогулка открывала тайлы (путешествия, E1/E3/E4). */
    val regionIds: List<String> = emptyList(),
    /** Режимы, бывшие активны в прогулке (C6 — велопрогулка). */
    val modes: List<String> = emptyList(),
    /** Новых тайлов открыто за прогулку (C8 — любимая тропа: 0 новых).
     *  -1 — неизвестно (запись эпохи до 4.1, поля в файле не было). */
    val newTilesOpened: Int = -1,
)

class WalkHistoryStore(private val file: File) {

    /** Дописывает завершённую прогулку (append + fsync, AD-5). */
    @Synchronized
    fun append(record: WalkRecord): HistoryAppendResult {
        return try {
            file.parentFile?.mkdirs()
            java.io.FileOutputStream(file, /* append = */ true).use { out ->
                out.write((HistoryJson.encodeToString(record.toSerializable()) + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            HistoryAppendResult.Ok
        } catch (e: Exception) {
            HistoryAppendResult.Error(e.message ?: e.toString())
        }
    }

    /**
     * Все записи истории в порядке добавления. Усечённый/битый хвост
     * (убийство процесса) отбрасывается: валидный префикс возвращается,
     * [HistoryRead.truncatedTail] фиксирует факт.
     */
    fun read(): HistoryRead {
        if (!file.isFile) return HistoryRead(emptyList(), truncatedTail = false)
        val text = try {
            file.readBytes().decodeToString()
        } catch (e: Exception) {
            return HistoryRead(emptyList(), truncatedTail = true)
        }
        val records = ArrayList<WalkRecord>()
        var truncated = false
        for (line in text.split('\n')) {
            if (line.isBlank()) continue // финальный перевод строки
            val parsed = try {
                HistoryJson.decodeFromString<StoredRecord>(line)
            } catch (e: Exception) {
                truncated = true
                break
            }
            records.add(parsed.toRecord())
        }
        return HistoryRead(records, truncated)
    }

    companion object {
        private val HistoryJson = Json { ignoreUnknownKeys = true }
    }
}

/** Результат чтения истории: записи + факт усечённого хвоста. */
data class HistoryRead(val records: List<WalkRecord>, val truncatedTail: Boolean)

/** Результат дозаписи записи. */
sealed class HistoryAppendResult {
    data object Ok : HistoryAppendResult()
    data class Error(val detail: String) : HistoryAppendResult()
}

@Serializable
private data class StoredRecord(
    val sessionId: Long,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long,
    val distanceMeters: Double,
    val activeMillis: Long,
    val acceptedCount: Int,
    val regionIds: List<String> = emptyList(),
    val modes: List<String> = emptyList(),
    val newTilesOpened: Int? = null,
)

private fun WalkRecord.toSerializable() = StoredRecord(
    sessionId = sessionId,
    startedAtEpochMillis = startedAtEpochMillis,
    finishedAtEpochMillis = finishedAtEpochMillis,
    distanceMeters = distanceMeters,
    activeMillis = activeMillis,
    acceptedCount = acceptedCount,
    regionIds = regionIds,
    modes = modes,
    newTilesOpened = newTilesOpened.takeIf { it >= 0 },
)

private fun StoredRecord.toRecord() = WalkRecord(
    sessionId = sessionId,
    startedAtEpochMillis = startedAtEpochMillis,
    finishedAtEpochMillis = finishedAtEpochMillis,
    distanceMeters = distanceMeters,
    activeMillis = activeMillis,
    acceptedCount = acceptedCount,
    regionIds = regionIds,
    modes = modes,
    newTilesOpened = newTilesOpened ?: -1,
)
