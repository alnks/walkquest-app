package app.walkquest.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.walkquest.data.packages.RegionPackageRepository
import app.walkquest.data.settings.SettingsRepository
import app.walkquest.data.transfer.ExportArchiveReader
import app.walkquest.data.transfer.ExportArchiveWriter
import app.walkquest.data.transfer.ExportSources
import app.walkquest.data.transfer.ImportApplier
import app.walkquest.data.transfer.ImportReport
import app.walkquest.data.transfer.ImportTargets
import app.walkquest.data.transfer.SectionStatus
import app.walkquest.domain.InstallRegionPackage
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Вкладка «Профиль» (история 6.3, FR-20/FR-21): перенос прогресса, предупреждение
 * о потере, «О приложении». Потоки (SAF/ContentResolver) приходят из Compose-слоя —
 * viewmodel JVM-тестируема (AD-10 почти полностью: только ViewModel-наследование).
 *
 * Поток импорта двухфазный: readImportPreview читает и держит секции в памяти и
 * показывает диалог с датой копии; confirmImport применяет через ImportApplier и
 * считает недостающие регионы (пакеты не входят в архив — докачка, Decision 6.1).
 */
class ProfileViewModel(
    private val settingsRepository: SettingsRepository,
    private val regionPackageRepository: RegionPackageRepository,
    private val installRegionPackage: InstallRegionPackage,
    private val exportSources: () -> ExportSources,
    private val importTargets: () -> ImportTargets,
    private val appVersion: String,
    private val privacyUrl: String,
    private val onImportApplied: () -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ProfileUiState(appVersion = appVersion, privacyUrl = privacyUrl)
    )
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    /** Архив, ожидающий подтверждения импорта (держим секции в памяти). */
    private var pendingArchive: ExportArchiveReader.Archive? = null

    init {
        reload()
    }

    fun reload() {
        // Чтение настроек — дисковый IO: только IO-поток (паттерн StatsViewModel).
        viewModelScope.launch {
            val snapshot = withContext(ioDispatcher) {
                Triple(
                    settingsRepository.leaderboardNickname(),
                    settingsRepository.leaderboardVisible(),
                    settingsRepository.walkGoal()?.describe(),
                )
            }
            _uiState.update {
                it.copy(
                    nickname = snapshot.first,
                    leaderboardVisible = snapshot.second,
                    goalText = snapshot.third,
                    lastError = null,
                )
            }
        }
    }

    /** Экспорт прогресса в поток (SAF uri из Compose-слоя). suspend: поток
     *  закрывается вызывающим после завершения записи. */
    suspend fun export(out: OutputStream) {
        _uiState.update { it.copy(exportBusy = true, lastError = null, message = null) }
        val result = withContext(ioDispatcher) {
            ExportArchiveWriter().write(out, exportSources())
        }
        when (result) {
            is ExportArchiveWriter.Result.Ok ->
                _uiState.update { it.copy(exportBusy = false, message = "Копия сохранена.") }
            is ExportArchiveWriter.Result.Error ->
                _uiState.update { it.copy(exportBusy = false, lastError = "Экспорт не удался: ${result.detail}") }
        }
    }

    /** Чтение выбранной копии: успех → диалог подтверждения с датой; иначе ошибка. */
    suspend fun readImportPreview(input: InputStream) {
        _uiState.update { it.copy(lastError = null, message = null) }
        val result = withContext(ioDispatcher) { ExportArchiveReader().read(input) }
            when (result) {
                is ExportArchiveReader.ReadResult.Ok -> {
                    pendingArchive = result.archive
                    _uiState.update {
                        it.copy(importState = ImportState.Preview(result.archive.manifest.exportedAtEpochMillis))
                    }
                }
                is ExportArchiveReader.ReadResult.Failure -> {
                    pendingArchive = null
                    _uiState.update {
                        it.copy(lastError = importErrorText(result.error))
                    }
                }
        }
    }

    fun confirmImport() {
        val archive = pendingArchive ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(importState = ImportState.Applying) }
            val report = withContext(ioDispatcher) { ImportApplier().apply(archive, importTargets()) }
            pendingArchive = null
            val installedIds = withContext(ioDispatcher) {
                regionPackageRepository.installedPackages().map { it.regionId }.toSet()
            }
            val missing = archive.manifest.installedRegionIds.filter { it !in installedIds }
            onImportApplied()
            reload() // Импорт мог принести ник/цель/видимость — шапка свежая сразу.
            _uiState.update {
                it.copy(
                    importState = ImportState.Idle,
                    missingRegions = missing,
                    message = formatImportReport(report) + RESTART_HINT,
                )
            }
        }
    }

    fun cancelImport() {
        pendingArchive = null
        _uiState.update { it.copy(importState = ImportState.Idle) }
    }

    /** Докачка недостающих пакетов после импорта (по одному, кнопка экрана). */
    fun downloadMissing(regionId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(downloadingRegionId = regionId, lastError = null) }
            val result = installRegionPackage(regionId)
            val installed = withContext(ioDispatcher) {
                regionPackageRepository.installedPackages().map { it.regionId }.toSet()
            }
            _uiState.update { state ->
                state.copy(
                    downloadingRegionId = null,
                    missingRegions = state.missingRegions.filter { it !in installed },
                    lastError = when (result) {
                        is app.walkquest.data.packages.PackageInstallResult.Failure ->
                            "Не удалось установить регион $regionId."
                        else -> null
                    },
                )
            }
        }
    }

    companion object {
        /** Подсказка после импорта: in-memory биткарты MapViewModel обновляются перезапуском (v1). */
        internal const val RESTART_HINT = "\nПерезапустите приложение, чтобы обновилась карта."
    }
}

data class ProfileUiState(
    val nickname: String? = null,
    val leaderboardVisible: Boolean = true,
    val goalText: String? = null,
    val appVersion: String = "",
    val privacyUrl: String = "",
    val missingRegions: List<String> = emptyList(),
    val downloadingRegionId: String? = null,
    val exportBusy: Boolean = false,
    val importState: ImportState = ImportState.Idle,
    val message: String? = null,
    val lastError: String? = null,
)

sealed interface ImportState {
    data object Idle : ImportState
    data class Preview(val exportedAtEpochMillis: Long) : ImportState
    data object Applying : ImportState
}

/** Отчёт импорта для экрана: одна строка на секцию, Ok/Skipped/Failed честно. */
internal fun formatImportReport(report: ImportReport): String {
    val lines = report.sections.map { (name, status) ->
        val mark = when (status) {
            is SectionStatus.Ok -> "✓"
            is SectionStatus.Skipped -> "пропущена (${status.reason})"
            is SectionStatus.Failed -> "ошибка (${status.reason})"
        }
        "$name — $mark"
    }
    val skipped = report.sections.values.count { it is SectionStatus.Skipped }
    val failed = report.sections.values.count { it is SectionStatus.Failed }
    val headline = when {
        failed > 0 -> "Импорт завершён с ошибками: $failed секций не записано."
        skipped > 0 -> "Импорт завершён частично: $skipped секций пропущено."
        else -> "Импорт завершён: все секции восстановлены."
    }
    return (listOf(headline) + lines + report.warnings).joinToString("\n")
}

/** Человеческий текст ошибки чтения копии. */
internal fun importErrorText(error: app.walkquest.data.transfer.ExportReadError): String = when (error) {
    is app.walkquest.data.transfer.ExportReadError.CorruptArchive ->
        "Это не похоже на копию WalkQuest или файл повреждён."
    is app.walkquest.data.transfer.ExportReadError.UnsupportedFormat ->
        "Копия создана более новой версией приложения (${error.foundVersion}). Обновите WalkQuest."
}

/** «2026-10-11, 19:04» для диалога подтверждения — локальное время устройства. */
internal fun formatCopyDate(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd, HH:mm", Locale.forLanguageTag("ru-RU")).format(Date(epochMillis))
