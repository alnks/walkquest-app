package app.walkquest.tracking

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Append-only журнал точек трека (история 1.7, AD-5).
 *
 * Формат файла (версионируемый, Consistency Conventions):
 *   строка 1 — JSON-заголовок {"formatVersion":1,"startedAtEpochMillis":...};
 *   строки 2..N — по одной JSON-точке {"lat":...,"lon":...,"t":...} (JSONL);
 *   с 2026-10-06 (рендер «свечи») у точки опциональное поле "acc" — заявленная
 *   провайдером точность, метры: она нужна, чтобы посмертный replay PointFilter
 *   (TrackHistory) повторил вердикты живой записи один-в-один. Поле опционально
 *   (encodeDefaults выключен, null не пишется): журналы старого формата читаются
 *   как прежде, formatVersion не поднят — расширение обратно совместимо.
 *
 * Каждая точка пишется немедленно: append + flush + fsync на каждую запись —
 * убийство процесса теряет секунды записи, а не трек. Чтение терпимо к
 * усечённому хвосту (обрыв mid-line при убийстве процесса): валидный префикс
 * восстанавливается, факт усечения возвращается вызывающему.
 *
 * Чистый JVM-код (AD-10): файл инжектируется, Android не импортируется.
 * Журнал — файловый прототип; Room-история прогулок — epic-walk-tracking.
 */
class TrackJournal(
    private val file: File,
    /**
     * Время старта записи для заголовка нового журнала; null — «сейчас»
     * (боевое поведение сервиса). Тесты передают время своих фикстур, чтобы
     * заголовок и точки были согласованы (replay отсекает фиксы СТАРШЕ
     * старта записи — PointFilter, 2026-10-08).
     */
    private val startedAtEpochMillis: Long? = null,
) {

    /**
     * Дописывает точку в конец журнала (заголовок пишется при первом append).
     * Дисковая синхронизация — на каждую точку (AD-5).
     */
    @Synchronized
    fun append(point: TrackPoint): JournalAppendResult {
        return try {
            file.parentFile?.mkdirs()
            val isNew = !file.isFile || file.length() == 0L
            java.io.FileOutputStream(file, /* append = */ true).use { out ->
                if (isNew) {
                    val header = JournalHeader(
                        startedAtEpochMillis = startedAtEpochMillis ?: System.currentTimeMillis(),
                    )
                    out.write((JournalJson.encodeToString(header) + "\n").toByteArray(Charsets.UTF_8))
                }
                val line = JournalJson.encodeToString(
                    PointLine(
                        lat = point.latitude,
                        lon = point.longitude,
                        t = point.epochMillis,
                        acc = point.accuracyMeters,
                    ),
                )
                out.write((line + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            JournalAppendResult.Ok
        } catch (e: Exception) {
            JournalAppendResult.Error(e.message ?: e.toString())
        }
    }

    /**
     * Читает журнал. Усечённый/битый хвост (после убийства процесса) отбрасывается:
     * возвращаются все валидные точки префикса + [JournalRead.Ok.truncatedTail] = true.
     * Нечитаемый заголовок или чужой formatVersion — [JournalRead.Invalid].
     */
    fun read(): JournalRead {
        if (!file.isFile) return JournalRead.Invalid("файл журнала не найден: ${file.name}")
        val text = try {
            // decodeToString заменяет битые байты на U+FFFD — строка не распарсится
            // и попадёт в truncatedTail, валидный префикс не теряется.
            file.readBytes().decodeToString()
        } catch (e: Exception) {
            return JournalRead.Invalid(e.message ?: e.toString())
        }
        val lines = text.split('\n')
        val header = try {
            JournalJson.decodeFromString<JournalHeader>(lines.first())
        } catch (e: Exception) {
            return JournalRead.Invalid("заголовок журнала не читается: ${e.message ?: e.toString()}")
        }
        if (header.formatVersion != FORMAT_VERSION) {
            return JournalRead.Invalid("formatVersion журнала ${header.formatVersion}, поддерживается $FORMAT_VERSION")
        }
        val points = ArrayList<TrackPoint>()
        var truncated = false
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue // финальный перевод строки
            val parsed = try {
                JournalJson.decodeFromString<PointLine>(line)
            } catch (e: Exception) {
                truncated = true
                break
            }
            points.add(TrackPoint(parsed.lat, parsed.lon, parsed.t, parsed.acc))
        }
        return JournalRead.Ok(points, truncated, header.startedAtEpochMillis)
    }

    /** Удаляет файл журнала (отказ от записи прототипа). */
    fun delete() {
        file.delete()
    }

    @Serializable
    private data class JournalHeader(
        val formatVersion: Int = FORMAT_VERSION,
        /**
         * Время старта записи. Дефолт 0 (а не currentTimeMillis): JournalJson
         * не пишет дефолты (encodeDefaults выключен), а «дефолт = сейчас»
         * совпадал с записываемым значением в том же миллисекунде — заголовок
         * уходил как `{}` и при чтении startedAt становился «сейчас чтения»:
         * replay TrackHistory отсекал ВСЕ точки как StaleFix (полевой баг
         * 2026-10-09: тропа не восстанавливалась после перезапуска).
         * 0 при записи невозможен (реальное время), при чтении `{}` журналов
         * 2026-10-08..09 даёт startedAt=0 → правило StaleFix просто выключено.
         */
        val startedAtEpochMillis: Long = 0,
    )

    @Serializable
    private data class PointLine(val lat: Double, val lon: Double, val t: Long, val acc: Float? = null)

    companion object {
        const val FORMAT_VERSION = 1
        private val JournalJson = Json { ignoreUnknownKeys = true }
    }
}

/** Результат дозаписи точки. */
sealed class JournalAppendResult {
    data object Ok : JournalAppendResult()
    data class Error(val detail: String) : JournalAppendResult()
}

/** Результат чтения журнала. */
sealed class JournalRead {
    /**
     * Валидные точки префикса; [truncatedTail] — хвост оборван (убийство процесса).
     * [startedAtEpochMillis] — время старта записи из заголовка: replay фильтра
     * отсекает устаревшие фиксы-призраки относительно него (PointFilter, 2026-10-08).
     */
    data class Ok(
        val points: List<TrackPoint>,
        val truncatedTail: Boolean,
        val startedAtEpochMillis: Long,
    ) : JournalRead()

    /** Журнал не читается целиком (заголовок, formatVersion, файла нет). */
    data class Invalid(val detail: String) : JournalRead()
}
