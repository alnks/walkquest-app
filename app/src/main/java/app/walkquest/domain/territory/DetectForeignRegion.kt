package app.walkquest.domain.territory

import app.walkquest.data.boundaries.BoundaryRepository
import app.walkquest.data.boundaries.BoundaryResult
import app.walkquest.data.packages.RegionPackageRepository

/**
 * Детект «локация в неустановленном регионе» (история 1.10, AD-9, EXPERIENCE Flow 4):
 * приложение видит новый город → предложение загрузить его пакет.
 *
 * Решение в два шага: сначала честная проверка по границам установленных пакетов
 * (та же модель, что открывает туман, — RegionLocator); если точка не покрыта —
 * поиск по bbox записей кэшированного манифеста пакетов (манифест 1.4 + опциональный
 * bbox; кэш на диске — детект офлайн, AD-2). Точности bbox достаточно для предложения;
 * принадлежность после установки снова решают полигоны.
 *
 * Чистый Kotlin (AD-10). Ошибок наружу нет: любой пробел в данных (нет кэша,
 * нет bbox, битые границы) → null (нет подсказки), sealed-результат не нужен.
 */
class DetectForeignRegion(
    private val regionPackageRepository: RegionPackageRepository,
    private val boundaryRepository: BoundaryRepository,
) {

    /** Предложение загрузить регион: id из манифеста и человекочитаемое имя (если есть). */
    data class Suggestion(val regionId: String, val name: String?)

    /**
     * regionId, чей пакет стоит загрузить для точки, или null — точка покрыта
     * установленным регионом либо неизвестна манифесту. Уже установленные регионы
     * не предлагаются.
     */
    fun check(latitude: Double, longitude: Double): Suggestion? {
        val installed = regionPackageRepository.installedPackages()
        val boundaries = installed.mapNotNull { pkg ->
            when (val result = boundaryRepository.load(pkg)) {
                is BoundaryResult.Ok -> result.boundary
                is BoundaryResult.Err -> null
            }
        }
        if (RegionLocator.locate(latitude, longitude, boundaries) != null) return null

        val installedIds = installed.mapTo(HashSet()) { it.regionId }
        val hit = regionPackageRepository.cachedManifest()
            ?.packages
            ?.firstOrNull { entry ->
                entry.regionId !in installedIds &&
                    entry.bbox?.size == 4 &&
                    longitude >= entry.bbox[0] && longitude <= entry.bbox[2] &&
                    latitude >= entry.bbox[1] && latitude <= entry.bbox[3]
            }
        return hit?.let { Suggestion(it.regionId, it.name) }
    }
}
