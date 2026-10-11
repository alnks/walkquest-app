package app.walkquest.domain

import app.walkquest.data.boundaries.BoundaryRepository
import app.walkquest.data.boundaries.BoundaryResult
import app.walkquest.data.fog.FogError
import app.walkquest.data.fog.FogRepository
import app.walkquest.data.fog.GridResult
import app.walkquest.data.fog.OpenBitResult
import app.walkquest.data.packages.RegionPackageRepository
import app.walkquest.domain.territory.RegionLocator

/**
 * Открытие тайла тумана по географической точке — единственный путь открытия битов (AD-4).
 *
 * Мульти-регион (история 1.10, AD-9): регион точки определяется здесь, в domain,
 * по границам установленных пакетов (RegionLocator над boundaries.geojson); бит
 * открывается только в биткарте своего региона (`fog/<regionId>.fog`, раздельные с 1.6) —
 * прогресс регионов не смешивается. Точка вне всех установленных регионов —
 * [OpenResult.OutOfRegion], состояние не меняется (предложение загрузить чужой
 * регион — отдельный use-case DetectForeignRegion).
 *
 * Чистый Kotlin без Android/MapLibre (AD-10). Сетка — знаменатель из установленного
 * пакета региона (walkability.grid + meta.json, истории 1.3/1.5); биткарта персистентна
 * (data/fog, история 1.6). Ни UI, ни слой MapLibre биты не открывают.
 */
class OpenTileAtPoint(
    private val fogRepository: FogRepository,
    private val regionPackageRepository: RegionPackageRepository,
    private val boundaryRepository: BoundaryRepository,
) {

    /**
     * Открывает тайл, содержащий точку, в регионе, которому точка принадлежит.
     * Идемпотентно: повторное открытие того же тайла — [OpenResult.Unchanged],
     * состояние и файл не меняются.
     */
    fun openAt(latitude: Double, longitude: Double): OpenResult {
        val installed = regionPackageRepository.installedPackages()
        if (installed.isEmpty()) return OpenResult.NoRegion
        val byId = installed.associateBy { it.regionId }
        val boundaries = installed.mapNotNull { pkg ->
            when (val result = boundaryRepository.load(pkg)) {
                is BoundaryResult.Ok -> result.boundary
                is BoundaryResult.Err -> null
            }
        }
        val regionId = RegionLocator.locate(latitude, longitude, boundaries)
            ?: return OpenResult.OutOfRegion
        val pkg = byId.getValue(regionId)
        val grid = when (val loaded = fogRepository.loadGrid(pkg)) {
            is GridResult.Err -> return OpenResult.Failure(loaded.error)
            is GridResult.Ok -> loaded.grid
        }
        val index = grid.indexAt(latitude, longitude) ?: return OpenResult.OutOfRegion
        return when (val opened = fogRepository.open(regionId, grid, index)) {
            is OpenBitResult.Changed -> OpenResult.Changed(opened.openedCount, regionId)
            is OpenBitResult.Unchanged -> OpenResult.Unchanged(regionId)
            is OpenBitResult.OutOfRange -> OpenResult.OutOfRegion
            is OpenBitResult.Failure -> OpenResult.Failure(opened.error)
        }
    }
}

/** Результат открытия тайла — единственный тип возврата через границу domain → viewmodel. */
sealed class OpenResult {
    /** Тайл был закрыт и теперь открыт; [openedCount] — всего открыто в регионе точки. */
    data class Changed(val openedCount: Int, val regionId: String? = null) : OpenResult()

    /** Тайл уже был открыт — состояние не изменилось. */
    data class Unchanged(val regionId: String? = null) : OpenResult()

    /** Точка вне границ всех установленных регионов — бит не открывается. */
    data object OutOfRegion : OpenResult()

    /** Ни одного пакета региона не установлено — тумана нет. */
    data object NoRegion : OpenResult()

    /** Типизированная ошибка data-слоя (сетка пакета или хранилище биткарты). */
    data class Failure(val error: FogError) : OpenResult()
}
