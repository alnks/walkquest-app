package app.walkquest.data.transfer

import java.io.File

/**
 * Цели импорта (история 6.2, FR-21): те же корни хранилищ, что в [ExportSources],
 * плюс активная сессия — она не переносится и удаляется перед импортом.
 * Чистый JVM-код (AD-10).
 */
data class ImportTargets(
    val settingsRoot: File,
    val fogRoot: File,
    val walksFile: File,
    val earnedFile: File,
    val questsFile: File,
    val plansFile: File,
    val poiCardsFile: File,
    val tracksRoot: File,
    /** Активная сессия прогулки (session/session.json) — удаляется при импорте. */
    val sessionFile: File? = null,
)
