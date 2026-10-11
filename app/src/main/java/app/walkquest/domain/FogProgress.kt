package app.walkquest.domain

import app.walkquest.data.fog.FogError
import app.walkquest.data.fog.FogRepository
import app.walkquest.data.fog.GridResult
import app.walkquest.data.fog.MaskResult
import app.walkquest.data.packages.RegionPackageRepository
import java.util.BitSet

/**
 * Процент открытия региона — единый владелец вычисления (история 1.8, AD-4, FR-2).
 *
 * Процент = открытые проходимые биты / все проходимые биты пакета. Знаменатель —
 * проходимая маска walkability.grid установленного пакета (payload, lsb-first);
 * числитель — пересечение снимка открытых битов с этой маской. Пересечение, а не
 * «число открытых битов»: биткарта теоретически может содержать биты вне актуальной
 * маски (смена сетки пакета), и они не должны завышать процент.
 *
 * Доля хранится как доля (0..1, Double) — форматирование «17,4%» только на UI
 * (Consistency Conventions). Чистый Kotlin без Android/MapLibre (AD-10).
 */

/** Снимок прогресса региона: доля и оба счётчика, из которых она получена. */
data class FogProgressSnapshot(
    /** Доля открытия 0..1 (открытые проходимые / все проходимые). */
    val fraction: Double,
    /** Открытых битов внутри проходимой маски. */
    val openedWalkable: Int,
    /** Всего проходимых битов пакета (знаменатель). */
    val walkableTotal: Int,
)

/** Чистый вычислитель доли: на вход снимок открытых битов и проходимая маска пакета. */
object FogProgress {

    /**
     * Доля открытия по пересечению с маской. Результат зависит только от множества
     * битов, не от порядка их открытия. Пустой знаменатель (регион без проходимых
     * тайлов) → доля 0, деления на ноль нет.
     */
    fun compute(openedIndices: Set<Int>, walkableMask: BitSet): FogProgressSnapshot {
        val total = walkableMask.cardinality()
        if (total == 0) return FogProgressSnapshot(fraction = 0.0, openedWalkable = 0, walkableTotal = 0)
        // BitSet.get за пределами длины маски безопасно возвращает false — биты вне
        // маски в числитель не попадают и процент не завышают.
        val openedWalkable = openedIndices.count { it >= 0 && walkableMask.get(it) }
        return FogProgressSnapshot(
            fraction = openedWalkable.toDouble() / total.toDouble(),
            openedWalkable = openedWalkable,
            walkableTotal = total,
        )
    }
}

/**
 * Use-case снимка прогресса региона: читает пакет, сетку, маску и биткарту из
 * data-слоя и считает долю через [FogProgress]. Не мутирует состояние.
 *
 * Мульти-регион (история 1.10): регион — параметр вызова; прогресс каждого региона
 * считается по его пакету и его биткарте и ни с чем не смешивается (AD-9, FR-4).
 */
class ComputeRegionProgress(
    private val fogRepository: FogRepository,
    private val regionPackageRepository: RegionPackageRepository,
) {

    fun compute(regionId: String): ProgressResult {
        val pkg = regionPackageRepository.getInstalled(regionId)
            ?: return ProgressResult.NoRegion
        val grid = when (val loaded = fogRepository.loadGrid(pkg)) {
            is GridResult.Err -> return ProgressResult.Failure(loaded.error)
            is GridResult.Ok -> loaded.grid
        }
        val mask = when (val loaded = fogRepository.loadWalkableMask(pkg)) {
            is MaskResult.Err -> return ProgressResult.Failure(loaded.error)
            is MaskResult.Ok -> loaded.mask
        }
        val opened = fogRepository.openedIndices(regionId, grid)
        return ProgressResult.Ok(FogProgress.compute(opened, mask))
    }
}

/** Результат вычисления прогресса — единственный тип возврата через границу domain → viewmodel. */
sealed class ProgressResult {
    /** Доля посчитана: снимок прогресса (доля 0..1 + счётчики). */
    data class Ok(val progress: FogProgressSnapshot) : ProgressResult()

    /** Пакет региона не установлен — процента нет (как и тумана). */
    data object NoRegion : ProgressResult()

    /** Типизированная ошибка data-слоя (сетка/маска пакета или хранилище). */
    data class Failure(val error: FogError) : ProgressResult()
}
