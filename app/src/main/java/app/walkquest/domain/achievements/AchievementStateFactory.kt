package app.walkquest.domain.achievements

import app.walkquest.data.packages.RegionPackageRepository
import app.walkquest.data.poicard.PoiCardStore
import app.walkquest.data.poi.PoiRepository
import app.walkquest.data.poi.PoiResult
import app.walkquest.data.settings.SettingsRepository
import app.walkquest.domain.ComputeRegionProgress
import app.walkquest.domain.DistrictProgressCalculator
import app.walkquest.domain.ProgressResult
import app.walkquest.domain.StreakCalculator
import app.walkquest.domain.WalkHistory
import app.walkquest.domain.quests.QuestStateStore

/**
 * Сборка состояния достижений (история 4.6): Android/DI-слой отдаёт сырые
 * зависимости — здесь готовый [AchievementState] для движка (AD-4: сборка
 * решений в domain, не в UI). Чистый Kotlin (AD-10).
 */
class AchievementStateFactory(
    private val walkHistory: WalkHistory,
    private val streakCalculator: StreakCalculator,
    private val computeRegionProgress: ComputeRegionProgress,
    private val districtCalculator: DistrictProgressCalculator,
    private val poiCardStore: PoiCardStore,
    private val poiRepository: PoiRepository,
    private val regionPackageRepository: RegionPackageRepository,
    private val settingsRepository: SettingsRepository,
    private val questStateStore: QuestStateStore,
) {

    fun build(nowEpochMillis: Long = System.currentTimeMillis()): AchievementState {
        val homeId = settingsRepository.homeRegionId()
            ?: regionPackageRepository.installedPackages().firstOrNull()?.regionId
        val homeFraction = homeId?.let { id ->
            (computeRegionProgress.compute(id) as? ProgressResult.Ok)?.progress?.fraction
        }
        val districts = homeId?.let { districtCalculator.progressFor(it) } ?: emptyList()
        val otherFractions = regionPackageRepository.installedPackages()
            .filter { it.regionId != homeId }
            .mapNotNull { pkg ->
                (computeRegionProgress.compute(pkg.regionId) as? ProgressResult.Ok)
                    ?.let { pkg.regionId to it.progress.fraction }
            }
            .toMap()
        // Парки: прочитанные карточки с category leisure=park по всем установленным пакетам.
        val readIds = poiCardStore.readIds()
        val totalPoi: Int
        val parksRead: Int
        run {
            var total = 0
            var parks = 0
            for (pkg in regionPackageRepository.installedPackages()) {
                val pois = when (val result = poiRepository.loadPois(pkg)) {
                    is PoiResult.Ok -> result.pois
                    is PoiResult.Err -> emptyList()
                }
                total += pois.size
                parks += pois.count { it.osmId in readIds && it.category == "leisure=park" }
            }
            totalPoi = total
            parksRead = parks
        }
        val quests = questStateStore.load()
        return AchievementState(
            records = walkHistory.records(),
            homeRegionId = homeId,
            homeCityFraction = homeFraction,
            homeDistricts = districts,
            otherRegionFractions = otherFractions,
            poiReadCount = readIds.size,
            totalPoiCount = totalPoi,
            parksReadCount = parksRead,
            streakDays = streakCalculator.currentStreak(nowEpochMillis),
            questsAcceptedCount = quests.acceptedCount,
            questsCompletedCount = quests.completedCount,
        )
    }
}
