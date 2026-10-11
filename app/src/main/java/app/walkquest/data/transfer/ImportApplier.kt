package app.walkquest.data.transfer

import app.walkquest.data.settings.SettingsRepository
import app.walkquest.tracking.TrackHistory
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Применение архива переноса к хранилищам (история 6.2, FR-21).
 *
 * Каждая секция валидируется по формату своего хранилища ДО записи и пишется
 * атомарно (temp + rename, как в сторах): битая секция не портит текущие
 * данные и не отменяет остальные — отчёт честен по каждой (Ok/Skipped/Failed).
 *
 * Перед импортом удаляется активная сессия (прогулка не переносится).
 * Перепроверка достижений по восстановленной истории и сброс кэшей пакетов —
 * снаружи (wiring в AppContainer, история 6.3).
 *
 * Чистый JVM-код (AD-10).
 */
class ImportApplier {

    private val json = Json { ignoreUnknownKeys = true }

    fun apply(archive: ExportArchiveReader.Archive, targets: ImportTargets): ImportReport {
        val statuses = LinkedHashMap<String, SectionStatus>()
        for ((name, bytes) in archive.sections.toSortedMap()) {
            statuses[name] = applySection(name, bytes, targets)
        }
        val sessionDeleted = targets.sessionFile?.let { session ->
            session.isFile && session.delete()
        } == true
        return ImportReport(
            sections = statuses,
            sessionDeleted = sessionDeleted,
            warnings = archive.warnings,
        )
    }

    private fun applySection(name: String, bytes: ByteArray, targets: ImportTargets): SectionStatus {
        val target: File = when {
            name == ExportSections.SETTINGS -> File(targets.settingsRoot, SettingsRepository.FILE_NAME)
            name == ExportSections.WALKS -> targets.walksFile
            name == ExportSections.EARNED -> targets.earnedFile
            name == ExportSections.QUESTS -> targets.questsFile
            name == ExportSections.PLANS -> targets.plansFile
            name == ExportSections.POI_CARDS -> targets.poiCardsFile
            name.startsWith(ExportSections.FOG_PREFIX) -> {
                val fileName = name.removePrefix(ExportSections.FOG_PREFIX)
                if (!isSafeFileName(fileName) || !fileName.endsWith(".fog")) {
                    return SectionStatus.Skipped("unsafe fog section name: $name")
                }
                File(targets.fogRoot, fileName)
            }
            name.startsWith(ExportSections.TRACKS_PREFIX) -> {
                val fileName = name.removePrefix(ExportSections.TRACKS_PREFIX)
                if (!isSafeFileName(fileName) ||
                    !fileName.startsWith(TrackHistory.FILE_PREFIX) ||
                    !fileName.endsWith(TrackHistory.FILE_EXTENSION)
                ) {
                    return SectionStatus.Skipped("unsafe tracks section name: $name")
                }
                File(targets.tracksRoot, fileName)
            }
            else -> return SectionStatus.Skipped("unknown section: $name")
        }

        val invalid = validate(name, bytes)
        if (invalid != null) return SectionStatus.Skipped(invalid)

        return try {
            writeAtomic(target, bytes)
            SectionStatus.Ok
        } catch (e: Exception) {
            SectionStatus.Failed(e.message ?: e.toString())
        }
    }

    /** Проверка содержимого по формату хранилища секции; null = валидно. */
    private fun validate(name: String, bytes: ByteArray): String? {
        if (bytes.isEmpty()) return "empty section: $name"
        return when {
            name == ExportSections.WALKS -> validateJsonLines(name, bytes) { line, index ->
                val obj = parseObject(line) ?: return@validateJsonLines "line $index is not a JSON object"
                for (field in WalkRecordFields.REQUIRED) {
                    val value = obj[field] ?: return@validateJsonLines "line $index missing '$field'"
                    // Числовые поля истории: целые или дробные (distanceMeters — Double).
                    val primitive = value.jsonPrimitive
                    if (primitive.longOrNull == null && primitive.doubleOrNull == null) {
                        return@validateJsonLines "line $index field '$field' is not numeric"
                    }
                }
                null
            }
            name.startsWith(ExportSections.FOG_PREFIX) -> {
                val headerLine = firstLine(bytes) ?: return "fog section has no header line"
                if (parseObject(headerLine) == null) return "fog header is not a JSON object"
                if (bytes.indexOf('\n'.code.toByte()) == bytes.size - 1) return "fog payload empty"
                null
            }
            name.startsWith(ExportSections.TRACKS_PREFIX) -> validateJsonLines(name, bytes) { line, index ->
                if (parseObject(line) == null) "line $index is not a JSON object" else null
            }
            // settings/earned/quests/plans/poicards — один JSON-объект.
            else -> if (parseObject(bytes.decodeToString()) == null) "not a JSON object" else null
        }
    }

    /** Каждая непустая строка — JSON-объект, проходящий [lineCheck]. */
    private fun validateJsonLines(
        name: String,
        bytes: ByteArray,
        lineCheck: (line: String, index: Int) -> String?,
    ): String? {
        val lines = bytes.decodeToString().split('\n')
        var index = 0
        for (line in lines) {
            if (line.isBlank()) continue
            val problem = lineCheck(line, index)
            if (problem != null) return "$name: $problem"
            index++
        }
        return null
    }

    private fun firstLine(bytes: ByteArray): String? {
        val newline = bytes.indexOf('\n'.code.toByte())
        if (newline <= 0) return null
        return bytes.decodeToString(0, newline)
    }

    private fun parseObject(text: String): JsonObject? = try {
        json.parseToJsonElement(text).let { it as? JsonObject }
    } catch (e: Exception) {
        null
    }

    /** Имя без путевых трюков: точка в начале (temp-файлы) и любые разделители запрещены. */
    private fun isSafeFileName(name: String): Boolean =
        name.isNotEmpty() && !name.startsWith(".") && !name.contains('/') &&
            !name.contains('\\') && !name.contains("..")

    /** Атомарная запись: temp рядом с целью → rename (как FogRepository.persistWith). */
    private fun writeAtomic(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: throw IOException("no parent for $target")
        parent.mkdirs()
        val temp = File(parent, ".import-${System.nanoTime()}.tmp")
        try {
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw IOException("rename $temp → $target failed")
            }
        } finally {
            temp.delete()
        }
    }

    /** Обязательные числовые поля записи истории (WalkHistoryStore.StoredRecord). */
    private object WalkRecordFields {
        val REQUIRED = listOf(
            "sessionId", "startedAtEpochMillis", "finishedAtEpochMillis",
            "distanceMeters", "activeMillis", "acceptedCount",
        )
    }
}

/** Результат импорта: статус по каждой секции + факт удаления сессии. */
data class ImportReport(
    val sections: Map<String, SectionStatus>,
    val sessionDeleted: Boolean,
    val warnings: List<String>,
)

sealed interface SectionStatus {
    data object Ok : SectionStatus
    data class Skipped(val reason: String) : SectionStatus
    data class Failed(val reason: String) : SectionStatus
}
