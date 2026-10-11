package app.walkquest.data.transfer

import java.io.EOFException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json

/**
 * Читатель архива переноса данных (история 6.1, FR-21).
 *
 * Порядок строгий: сначала валидация (zip целиком + манифест + formatVersion),
 * потом — отдать секции вызывающему. Typed-ошибки [ExportReadError]:
 * обрезанный/не-zip — CorruptArchive, formatVersion новее известного —
 * UnsupportedFormat. Ни одна секция не применяется до успешной валидации.
 *
 * Секции читаются в память целиком (v1); лимит на секцию — [MAX_SECTION_BYTES],
 * превышение трактуется как повреждение. Неизвестные entry-имена игнорируются
 * с предупреждением в отчёте (вперёдсмотрящая совместимость: архив от будущей
 * версии с тем же formatVersion не ломает импорт).
 *
 * Чистый JVM-код (AD-10).
 */
class ExportArchiveReader {

    private val json = Json { ignoreUnknownKeys = true }

    fun read(input: InputStream): ReadResult {
        val sections = LinkedHashMap<String, ByteArray>()
        val warnings = ArrayList<String>()
        var manifest: ExportManifest? = null
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = try {
                        zip.nextEntry
                    } catch (e: ZipException) {
                        return ReadResult.Failure(ExportReadError.CorruptArchive("bad zip: ${e.message}"))
                    } catch (e: EOFException) {
                        return ReadResult.Failure(ExportReadError.CorruptArchive("truncated zip"))
                    } ?: break
                    val name = entry.name
                    if (entry.isDirectory) continue
                    val bytes = try {
                        zip.readNBytesLimited(MAX_SECTION_BYTES.toLong())
                    } catch (e: SectionTooLarge) {
                        return ReadResult.Failure(
                            ExportReadError.CorruptArchive("section $name exceeds ${MAX_SECTION_BYTES} bytes")
                        )
                    }
                    if (!ExportSections.isKnown(name)) {
                        warnings += "unknown section ignored: $name"
                        continue
                    }
                    if (name == ExportSections.MANIFEST) {
                        manifest = try {
                            json.decodeFromString<ExportManifest>(bytes.decodeToString())
                        } catch (e: Exception) {
                            return ReadResult.Failure(ExportReadError.CorruptArchive("bad manifest: ${e.message}"))
                        }
                    } else {
                        if (sections.containsKey(name)) {
                            warnings += "duplicate section: $name (применена последняя копия)"
                        }
                        sections[name] = bytes
                    }
                }
            }
        } catch (e: ZipException) {
            return ReadResult.Failure(ExportReadError.CorruptArchive("bad zip: ${e.message}"))
        } catch (e: Exception) {
            return ReadResult.Failure(ExportReadError.CorruptArchive(e.message ?: e.toString()))
        }

        val m = manifest
            ?: return ReadResult.Failure(ExportReadError.CorruptArchive("manifest.json missing"))
        if (m.formatVersion > EXPORT_FORMAT_VERSION) {
            return ReadResult.Failure(ExportReadError.UnsupportedFormat(m.formatVersion))
        }
        if (m.formatVersion < 1) {
            return ReadResult.Failure(ExportReadError.CorruptArchive("formatVersion ${m.formatVersion} invalid"))
        }
        return ReadResult.Ok(Archive(manifest = m, sections = sections, warnings = warnings))
    }

    /** Прочитать до limit байт; бросить [SectionTooLarge] за лимитом. */
    private fun InputStream.readNBytesLimited(limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = this.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw SectionTooLarge()
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private class SectionTooLarge : Exception()

    sealed interface ReadResult {
        data class Ok(val archive: Archive) : ReadResult
        data class Failure(val error: ExportReadError) : ReadResult
    }

    /** Распарсенный архив: манифест + секции (имя → байты) + предупреждения. */
    data class Archive(
        val manifest: ExportManifest,
        val sections: Map<String, ByteArray>,
        val warnings: List<String>,
    )

    companion object {
        const val MAX_SECTION_BYTES: Long = 256L * 1024 * 1024
    }
}
