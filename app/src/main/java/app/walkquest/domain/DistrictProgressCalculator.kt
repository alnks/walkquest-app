package app.walkquest.domain

import app.walkquest.domain.territory.PointInPolygon
import app.walkquest.data.boundaries.BoundaryRepository
import app.walkquest.data.fog.FogRepository
import app.walkquest.data.fog.GridResult
import app.walkquest.data.fog.MaskResult
import app.walkquest.data.packages.RegionPackageRepository
import java.util.BitSet

/**
 * Районный прогресс (история 4.3, достижения A8–A10, E2): для региона считает
 * по каждому району (kind=district пакета) долю открытых ПРОХОДИМЫХ клеток
 * сетки тумана. Клетка района — центр клетки в полигоне (bbox-префильтр +
 * PointInPolygon). «Полностью открыт» = доля 1.0. Малые города без районов —
 * пустой список (районные достижения скрываются, спек каталога).
 *
 * Дорогая часть — перебор клеток сетки; вызывается на фоне после прогулки,
 * не в рендере. Чистый Kotlin (AD-10), зависимости — data-репозитории (AD-4:
 * правила достижений живут в domain).
 */
class DistrictProgressCalculator(
    private val regionPackageRepository: RegionPackageRepository,
    private val boundaryRepository: BoundaryRepository,
    private val fogRepository: FogRepository,
) {

    /** Прогресс по районам региона; пусто — нет пакета/сетки/районов. */
    fun progressFor(regionId: String): List<DistrictProgress> {
        val pkg = regionPackageRepository.getInstalled(regionId) ?: return emptyList()
        val grid = when (val loaded = fogRepository.loadGrid(pkg)) {
            is GridResult.Ok -> loaded.grid
            is GridResult.Err -> return emptyList()
        }
        val districts = boundaryRepository.loadDistricts(pkg)
        if (districts.isEmpty()) return emptyList()
        val walkable = when (val mask = fogRepository.loadWalkableMask(pkg)) {
            is MaskResult.Ok -> mask.mask
            is MaskResult.Err -> return emptyList()
        }
        val opened = fogRepository.openedIndices(regionId, grid)
        return districts.map { district ->
            var total = 0
            var openedIn = 0
            for (index in 0 until grid.tileCount) {
                if (!walkable.get(index)) continue
                val center = cellCenter(grid, index)
                if (!district.inBbox(center[1], center[0])) continue
                if (!PointInPolygon.containsAny(center[1], center[0], district.polygons)) continue
                total += 1
                if (opened.contains(index)) openedIn += 1
            }
            DistrictProgress(
                name = district.name ?: district.regionId,
                openedWalkable = openedIn,
                walkableTotal = total,
                fraction = if (total == 0) 0.0 else openedIn.toDouble() / total,
            )
        }
    }

    companion object {
        /** Центр клетки [index] сетки как [lon, lat] (формулы генератора walkability.py). */
        fun cellCenter(grid: app.walkquest.data.fog.FogGrid, index: Int): DoubleArray {
            val row = index / grid.cols
            val col = index % grid.cols
            return doubleArrayOf(
                grid.originLon + (col + 0.5) * grid.dx,
                grid.originLat + (row + 0.5) * grid.dy,
            )
        }
    }
}

/** Прогресс одного района. */
data class DistrictProgress(
    val name: String,
    val openedWalkable: Int,
    val walkableTotal: Int,
    val fraction: Double,
) {
    /** Полностью открыт (достижения A8–A10). */
    val fullyOpened: Boolean get() = walkableTotal > 0 && openedWalkable >= walkableTotal
}
