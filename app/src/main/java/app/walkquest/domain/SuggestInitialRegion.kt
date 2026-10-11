package app.walkquest.domain

import app.walkquest.data.packages.RegionPackageRepository
import app.walkquest.domain.territory.DetectForeignRegion

/**
 * Предложение первого региона при запуске без установленных пакетов
 * (дефект D1 эмуляторного прогона 2026-10-05, EXPERIENCE Flow 2 «Где вы живёте?»).
 * До правки первый регион нельзя было получить через UI: запись трека требует
 * пакет, а карточка предложения (1.10) рождается только из записи — замкнутый круг.
 *
 * Выбор региона: по геолокации через [DetectForeignRegion] (bbox записей манифеста),
 * если локация известна; иначе — первая запись манифеста. Манифест скачивается и
 * кэшируется, поэтому детект работает по свежему кэшу. Ошибок наружу нет: нет сети,
 * пустой/битый манифест, уже что-то установлено → null (карточки в UI нет).
 *
 * Чистый Kotlin (AD-10): локация приходит параметром, Android здесь не появляется.
 */
class SuggestInitialRegion(
    private val regionPackageRepository: RegionPackageRepository,
    private val detectForeignRegion: DetectForeignRegion,
) {

    /**
     * Регион, который стоит предложить как первый (домашний), или null:
     * пакеты уже установлены либо манифест недоступен/пуст.
     */
    suspend operator fun invoke(latitude: Double?, longitude: Double?): DetectForeignRegion.Suggestion? {
        if (regionPackageRepository.installedPackages().isNotEmpty()) return null
        val manifest = regionPackageRepository.fetchManifestPreview() ?: return null
        if (latitude != null && longitude != null) {
            detectForeignRegion.check(latitude, longitude)?.let { return it }
        }
        return manifest.packages.firstOrNull()
            ?.let { DetectForeignRegion.Suggestion(it.regionId, it.name) }
    }
}
