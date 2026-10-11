package app.walkquest.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.walkquest.data.packages.RegionPackageRepository
import app.walkquest.data.plans.PlannedWalk
import app.walkquest.data.plans.PlannedWalksStore
import app.walkquest.data.settings.SettingsRepository
import app.walkquest.domain.ComputeRegionProgress
import app.walkquest.domain.PlanPolicy
import app.walkquest.domain.StreakCalculator
import app.walkquest.domain.WalkHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Прогресс одного посещённого региона для экрана статистики (3.4, FR-13). */
data class RegionStatUi(
    val regionId: String,
    val name: String,
    /** Доля открытия 0..1 — готовое значение из domain (AD-4). */
    val fraction: Double,
)

/** План для экрана статистики (3.6, FR-17): время + факт выполнения (domain). */
data class PlannedWalkUi(
    val id: Long,
    val plannedAtEpochMillis: Long,
    /** В день плана была прогулка (PlanPolicy). */
    val fulfilled: Boolean,
    /** Время плана уже прошло. */
    val past: Boolean,
)

/** Сводка экрана статистики (3.4): все числа — из domain, UI только рендерит. */
data class StatsUiState(
    val totalWalks: Int = 0,
    val totalDistanceMeters: Double = 0.0,
    val streakDays: Int = 0,
    /** Дистанция текущей недели (пн–вс), метры — мини-колонка «за неделю» панели карты. */
    val weekDistanceMeters: Double = 0.0,
    /** Проценты регионов, где открыт хотя бы один тайл (FR-13). */
    val regions: List<RegionStatUi> = emptyList(),
    /** Запланированные прогулки (3.6), от ближайших к дальним. */
    val plans: List<PlannedWalkUi> = emptyList(),
    /** Описание цели для подзаголовка (3.2/3.4; null — до онбординга). */
    val goalText: String? = null,
    val loading: Boolean = true,
)

/**
 * Экран статистики (история 3.4, FR-13): проценты посещённых регионов,
 * суммарное расстояние, число прогулок, серия. Источник истины — история
 * прогулок на устройстве (3.1); чтение на IO, состояние — UDF.
 */
class StatsViewModel(
    private val walkHistory: WalkHistory,
    private val streakCalculator: StreakCalculator,
    private val computeRegionProgress: ComputeRegionProgress,
    private val regionPackageRepository: RegionPackageRepository,
    private val settingsRepository: SettingsRepository,
    private val plannedWalksStore: PlannedWalksStore,
    private val planPolicy: PlanPolicy,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val manifestName = { regionId: String ->
                    regionPackageRepository.cachedManifest()
                        ?.packages?.firstOrNull { it.regionId == regionId }?.name
                }
                val visited = regionPackageRepository.installedPackages().mapNotNull { pkg ->
                    when (val result = computeRegionProgress.compute(pkg.regionId)) {
                        is app.walkquest.domain.ProgressResult.Ok ->
                            // «Посещённый» = открыт хотя бы один тайл (FR-13).
                            if (result.progress.openedWalkable > 0) {
                                RegionStatUi(
                                    pkg.regionId,
                                    manifestName(pkg.regionId) ?: pkg.regionId,
                                    result.progress.fraction,
                                )
                            } else {
                                null
                            }
                        else -> null
                    }
                }.sortedByDescending { it.fraction }
                StatsUiState(
                    totalWalks = walkHistory.totalWalks(),
                    totalDistanceMeters = walkHistory.totalDistanceMeters(),
                    streakDays = streakCalculator.currentStreak(System.currentTimeMillis()),
                    weekDistanceMeters = walkHistory
                        .walksInWeekOf(System.currentTimeMillis())
                        .sumOf { it.distanceMeters },
                    regions = visited,
                    plans = plannedWalksStore.plans().map { plan ->
                        PlannedWalkUi(
                            id = plan.id,
                            plannedAtEpochMillis = plan.plannedAtEpochMillis,
                            fulfilled = planPolicy.isFulfilled(plan),
                            past = plan.plannedAtEpochMillis <= System.currentTimeMillis(),
                        )
                    },
                    goalText = settingsRepository.walkGoal()?.describe(),
                    loading = false,
                )
            }
            _uiState.value = loaded
        }
    }

    /**
     * Планирование прогулки (3.6, FR-17): сохраняет план, @return id плана
     * для планировки будильника Android-слоем (lead — из настроек).
     */
    fun planWalk(atEpochMillis: Long): Long? {
        val plan = PlannedWalk(id = System.currentTimeMillis(), plannedAtEpochMillis = atEpochMillis)
        val error = plannedWalksStore.add(plan)
        if (error != null) return null
        reload()
        return plan.id
    }

    /** Отмена плана (3.6); Android-слой отменяет будильник по тому же id. */
    fun cancelPlan(planId: Long) {
        plannedWalksStore.remove(planId)
        reload()
    }

    /** Lead напоминания, мин (3.6) — для планировки будильника. */
    fun planReminderLeadMinutes(): Int = settingsRepository.planReminderLeadMinutes()
}
