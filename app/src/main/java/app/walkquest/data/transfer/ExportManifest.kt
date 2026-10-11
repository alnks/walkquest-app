package app.walkquest.data.transfer

import kotlinx.serialization.Serializable

/**
 * Манифест архива переноса данных (история 6.1, FR-21).
 * Формат версионируемый (AD-5: поле formatVersion с первого релиза):
 * читатель отказывается от архивов новее известной ему версии.
 */
@Serializable
data class ExportManifest(
    val formatVersion: Int = EXPORT_FORMAT_VERSION,
    /** Момент экспорта, epoch millis UTC. */
    val exportedAtEpochMillis: Long,
    /** versionName приложения-экспортёра. */
    val appVersion: String,
    /** Установленные на момент экспорта регионы — импорту нужно знать, что докачать
     *  (сами пакеты в архив не входят, Decision инсепшна 2026-10-11). */
    val installedRegionIds: List<String> = emptyList(),
)

/** Текущая версия формата архива. */
const val EXPORT_FORMAT_VERSION: Int = 1
