package app.walkquest.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.walkquest.data.packages.PackageInstallResult
import app.walkquest.data.packages.RegionPackageRepository
import app.walkquest.data.settings.SettingsRepository
import app.walkquest.domain.ComputeRegionProgress
import app.walkquest.domain.InstallRegionPackage
import app.walkquest.domain.ProgressResult
import app.walkquest.domain.territory.HomeRegionPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Строка вкладки «Регионы» (1.10, FR-4): регион со своим процентом — не смешивается с другими. */
data class RegionRowUi(
    val regionId: String,
    /** Имя из манифест-кэша; фолбэк — regionId. */
    val name: String,
    /** Доля открытия 0..1 из domain; null — прогресс не прочитался (битый пакет/сетка). */
    val fraction: Double?,
    val isHome: Boolean,
    /** Манифест предлагает packageVersion выше установленной → действие «Обновить». */
    val updateAvailable: Boolean = false,
)

/** Состояние вкладки «Регионы». */
data class RegionsUiState(
    val rows: List<RegionRowUi> = emptyList(),
    val homeRegionId: String? = null,
    /** Сообщение типизированной ошибки записи настроек (смена домашнего) или обновления. */
    val lastError: String? = null,
    /** Регион, чей пакет сейчас обновляется; null — обновления нет (одно за раз). */
    val updatingRegionId: String? = null,
)

/**
 * Состояние и события вкладки «Регионы» (история 1.10, AD-9, UDF): список установленных
 * регионов, у каждого свой процент из domain (ComputeRegionProgress по его пакету и его
 * биткарте — раздельные с 1.6). Домашний регион — персистентная настройка
 * (data/settings); правило дефолта — domain (HomeRegionPolicy). Переход к карте региона
 * — колбэком наружу (MainActivity переключает вкладку и зовёт MapViewModel).
 */
class RegionsViewModel(
    private val regionPackageRepository: RegionPackageRepository,
    private val settingsRepository: SettingsRepository,
    private val computeRegionProgress: ComputeRegionProgress,
    private val installRegionPackage: InstallRegionPackage,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RegionsUiState())
    val uiState: StateFlow<RegionsUiState> = _uiState.asStateFlow()

    init {
        reload()
    }

    /**
     * Перечитывает пакеты, проценты и домашний регион с диска (офлайн, AD-2).
     * Манифест сперва мягко обновляется из сети (best-effort: ошибка сети → кэш),
     * чтобы вкладка видела свежие версии пакетов (обновление, дефект 7).
     */
    fun reload() {
        viewModelScope.launch {
            val (rows, home) = withContext(Dispatchers.IO) {
                regionPackageRepository.fetchManifestPreview() // обновляет кэш; ошибка → null
                val manifest = regionPackageRepository.cachedManifest()
                // По одному ряду на регион: после обновления на диске могут лежать
                // v1 и v2 — берём максимальную версию (как getInstalled).
                val installed = regionPackageRepository.installedPackages()
                    .groupBy { it.regionId }
                    .map { (_, pkgs) -> pkgs.maxBy { it.packageVersion } }
                val homeId = HomeRegionPolicy.resolve(
                    settingsRepository.homeRegionId(),
                    installed.map { it.regionId },
                )
                val entries = manifest?.packages.orEmpty()
                val rows = installed
                    .sortedBy { it.regionId }
                    .map { pkg ->
                        val fraction = when (val result = computeRegionProgress.compute(pkg.regionId)) {
                            is ProgressResult.Ok -> result.progress.fraction
                            else -> null
                        }
                        val entry = entries.firstOrNull { it.regionId == pkg.regionId }
                        RegionRowUi(
                            regionId = pkg.regionId,
                            name = entry?.name ?: pkg.regionId,
                            fraction = fraction,
                            isHome = pkg.regionId == homeId,
                            updateAvailable = entry != null && entry.packageVersion > pkg.packageVersion,
                        )
                    }
                rows to homeId
            }
            _uiState.update { it.copy(rows = rows, homeRegionId = home) }
        }
    }

    /**
     * Обновление пакета региона (дефект 7 разбора владельца 2026-10-05): манифест
     * предлагает версию выше установленной → существующий InstallRegionPackage
     * (ту же версию он пропускает — здесь версия заведомо выше, закачка пойдёт).
     * После успеха — reload: процент и флаг обновления перечитываются с диска.
     */
    fun onUpdateRegion(regionId: String) {
        if (_uiState.value.updatingRegionId != null) return
        _uiState.update { it.copy(updatingRegionId = regionId, lastError = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { installRegionPackage(regionId) }
            when (result) {
                is PackageInstallResult.Failure -> _uiState.update {
                    it.copy(updatingRegionId = null, lastError = result.error.message)
                }
                is PackageInstallResult.Success, is PackageInstallResult.AlreadyInstalled -> {
                    _uiState.update { it.copy(updatingRegionId = null) }
                    reload()
                }
            }
        }
    }

    /** Смена домашнего региона — явное действие пользователя; персистентно (settings.json). */
    fun onSetHomeRegion(regionId: String) {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) { settingsRepository.setHomeRegion(regionId) }
            _uiState.update { state ->
                state.copy(
                    lastError = error?.message,
                    homeRegionId = if (error == null) regionId else state.homeRegionId,
                    rows = if (error == null) {
                        state.rows.map { it.copy(isHome = it.regionId == regionId) }
                    } else {
                        state.rows
                    },
                )
            }
        }
    }
}
