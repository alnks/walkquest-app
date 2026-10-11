package app.walkquest.data.transfer

import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import app.walkquest.data.settings.SettingsRepository
import app.walkquest.tracking.TrackHistory
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Писатель архива переноса данных (история 6.1, FR-21).
 *
 * Формат: zip (DEFLATED), первым entry — `manifest.json` ([ExportManifest]),
 * дальше секции по [ExportSections]. Биткарты тумана и журналы треков —
 * бинарные, копируются побайтово, не парсятся. Отсутствующие файлы и пустые
 * директории пропускаются: архив от устройства без истории валиден.
 *
 * Целостность — штатным CRC zip: читатель ловит обрезанный/битый архив.
 * Чистый JVM-код (AD-10): OutputStream и File приходят снаружи.
 */
class ExportArchiveWriter {

    private val json = Json { encodeDefaults = true }

    fun write(out: OutputStream, sources: ExportSources): Result {
        var entries = 0
        try {
            ZipOutputStream(out.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(ExportSections.MANIFEST))
                zip.write(
                    json.encodeToString(
                        ExportManifest(
                            exportedAtEpochMillis = System.currentTimeMillis(),
                            appVersion = sources.appVersion,
                            installedRegionIds = sources.installedRegionIds,
                        )
                    ).toByteArray(Charsets.UTF_8)
                )
                zip.closeEntry()
                entries++

                entries += putFile(zip, ExportSections.SETTINGS, File(sources.settingsRoot, SettingsRepository.FILE_NAME))
                entries += putFile(zip, ExportSections.WALKS, sources.walksFile)
                entries += putFile(zip, ExportSections.EARNED, sources.earnedFile)
                entries += putFile(zip, ExportSections.QUESTS, sources.questsFile)
                entries += putFile(zip, ExportSections.PLANS, sources.plansFile)
                entries += putFile(zip, ExportSections.POI_CARDS, sources.poiCardsFile)
                // Только файлы по конвенции читателей: temp-останки атомарных записей
                // (`.fog-*.tmp`) и посторонние файлы в корнях не экспортируются.
                entries += putDirectory(zip, ExportSections.FOG_PREFIX, sources.fogRoot) { name ->
                    !name.startsWith(".") && name.endsWith(".fog")
                }
                entries += putDirectory(zip, ExportSections.TRACKS_PREFIX, sources.tracksRoot) { name ->
                    name.startsWith(TrackHistory.FILE_PREFIX) && name.endsWith(TrackHistory.FILE_EXTENSION)
                }
            }
        } catch (e: Exception) {
            return Result.Error(e.message ?: e.toString())
        }
        return Result.Ok(entries)
    }

    /** Копирует один файл как entry; вернёт 1, если файл был, 0 — если пропущен. */
    private fun putFile(zip: ZipOutputStream, name: String, file: File): Int {
        if (!file.isFile) return 0
        zip.putNextEntry(ZipEntry(name))
        FileInputStream(file).use { it.copyTo(zip) }
        zip.closeEntry()
        return 1
    }

    /** Копирует файлы директории, проходящие фильтр, с префиксом имени; вернёт число добавленных. */
    private fun putDirectory(
        zip: ZipOutputStream,
        prefix: String,
        dir: File,
        nameFilter: (String) -> Boolean,
    ): Int {
        val files = dir.listFiles { f -> f.isFile && nameFilter(f.name) } ?: return 0
        var count = 0
        for (file in files.sortedBy { it.name }) {
            zip.putNextEntry(ZipEntry(prefix + file.name))
            FileInputStream(file).use { it.copyTo(zip) }
            zip.closeEntry()
            count++
        }
        return count
    }

    sealed interface Result {
        data class Ok(val entries: Int) : Result
        data class Error(val detail: String) : Result
    }
}
