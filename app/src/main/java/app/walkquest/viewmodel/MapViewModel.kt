package app.walkquest.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.walkquest.data.boundaries.BoundaryRepository
import app.walkquest.data.boundaries.BoundaryResult
import app.walkquest.data.fog.FogGrid
import app.walkquest.data.fog.FogRepository
import app.walkquest.data.fog.GridResult
import app.walkquest.data.packages.PackageInstallResult
import app.walkquest.data.packages.RegionPackageRepository
import app.walkquest.data.poi.Poi
import app.walkquest.data.poi.PoiBounds
import app.walkquest.data.poi.PoiFilter
import app.walkquest.data.poi.PoiRepository
import app.walkquest.data.poi.PoiResult
import app.walkquest.data.settings.SettingsRepository
import app.walkquest.domain.ComputeRegionProgress
import app.walkquest.domain.InstallRegionPackage
import app.walkquest.domain.OpenResult
import app.walkquest.domain.OpenTileAtPoint
import app.walkquest.domain.ProgressResult
import app.walkquest.domain.SuggestInitialRegion
import app.walkquest.domain.WalkAchievements
import app.walkquest.domain.WalkGoal
import app.walkquest.domain.WalkPraise
import app.walkquest.domain.territory.HomeRegionPolicy
import app.walkquest.domain.territory.RegionBoundary
import app.walkquest.domain.territory.RegionLocator
import app.walkquest.tracking.RibbonBuilder
import app.walkquest.tracking.TrackHistory
import app.walkquest.tracking.TrackPoint
import app.walkquest.tracking.TrackingStateBus
import app.walkquest.tracking.WalkMode
import app.walkquest.tracking.WalkState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Карточка предложения загрузить регион (история 1.10, EXPERIENCE Flow 4):
 * локация в неустановленном регионе → «Загрузить?» → прогресс/ошибка установки.
 * [isInitialHome] — предложение первого (домашнего) региона при запуске без
 * установленных пакетов (дефект D1 прогона 2026-10-05, EXPERIENCE Flow 2):
 * другой текст карточки и иммунитет к затиранию снимками шины записи.
 */
data class RegionSuggestionUi(
    val regionId: String,
    /** Человекочитаемое имя из манифеста; фолбэк — regionId. */
    val name: String,
    val installing: Boolean = false,
    val error: String? = null,
    val isInitialHome: Boolean = false,
)

/** Сводка завершённой прогулки для финального экрана (2.8, FR-8). */
data class WalkSummaryUi(
    /** Похвала — готовая строка из domain (WalkPraise), UI только рендерит. */
    val praise: String,
    /** Дистанция по принятым точкам, метры. */
    val distanceMeters: Double,
    /** Чистое время без пауз, мс. */
    val activeMillis: Long,
    /** Доля открытия активного региона 0..1 (domain, AD-4); null — не прочиталась. */
    val progressFraction: Double?,
    /** Новые достижения (порт WalkAchievements; пусто до epic-achievements). */
    val achievements: List<String>,
)

/** Состояние экрана карты (истории 1.6–1.10). */
data class MapUiState(
    /** Регион, чья карта/туман/прогресс сейчас на экране; null — пакетов нет. */
    val activeRegionId: String? = null,
    /** Домашний регион (AD-9): карта по умолчанию; пометка во вкладке регионов. */
    val homeRegionId: String? = null,
    /** Установлен ли пакет активного региона (без него — asset-стиль, тумана нет). */
    val regionInstalled: Boolean = false,
    /** URI стиля карты: file:// style.json установленного пакета или asset-запас. */
    val styleUri: String = FALLBACK_STYLE_URI,
    /** Стартовая камера: центр bbox активного региона или центр СПб (запас). */
    val cameraLatitude: Double = FALLBACK_LATITUDE,
    val cameraLongitude: Double = FALLBACK_LONGITUDE,
    val cameraZoom: Double = FALLBACK_ZOOM,
    /** Сетка тайлов активного региона; null — пакет не установлен или сетка не прочиталась. */
    val grid: FogGrid? = null,
    /** Индексы открытых тайлов активного региона (снимок его биткарты) — учёт (AD-4). */
    val openedIndices: Set<Int> = emptySet(),
    /**
     * Осевые цепочки раскрытия «свечи v2» (2026-10-06) ПО СЕССИЯМ записи
     * (полевой баг 2026-10-08: единая лента соединяла разные записи прямыми
     * линиями через весь регион — replay журналов сбрасывал якорь фильтра,
     * но не цепочку буфера). Каждая цепочка — дедуплицированные ПРИНЯТЫЕ
     * точки одной записи активного региона; слой карты строит по кольцу
     * буфера на цепочку (trailRing), соединений между цепочками нет.
     * Источник — журналы `tracks/track-*.track` (TrackHistory, восстановление
     * после перезапуска) плюс инкремент из шины записи. Учёт
     * (openedIndices/процент) отсюда не зависит.
     */
    val ribbonChains: List<List<TrackPoint>> = emptyList(),
    /** Счётчик изменений биткарты — триггер перестройки слоя тумана. */
    val fogEpoch: Int = 0,
    /**
     * Доля открытия активного региона 0..1 (история 1.8) — готовое значение из domain (AD-4);
     * UI только форматирует. null — пакет не установлен или прогресс не прочитался.
     */
    val progressFraction: Double? = null,
    /** Сообщение о типизированной ошибке открытия (показ снэкбаром — позже; сейчас только состояние). */
    val lastOpenError: String? = null,
    /** Идёт ли запись трека (1.7, снимок из TrackingStateBus). */
    val isRecording: Boolean = false,
    /** Принято фильтром точек в текущей записи. */
    val recordedPoints: Int = 0,
    /** Отсеяно фильтром точек в текущей записи (скорость/телепортация/время). */
    val rejectedPoints: Int = 0,
    /**
     * POI-маяки для слоя карты (история 1.9, FR-18) — выдача по зуму/области
     * (PoiFilter: тиры, лимит), не все 58k пакета. Пусто без пакета или при битом poi.json.
     */
    val visiblePois: List<Poi> = emptyList(),
    /** Выбранный тапом POI — открытая карточка места; null — карточка закрыта. */
    val selectedPoi: Poi? = null,
    /**
     * Файл фото выбранного POI из установленного пакета (пакет v2: поле `photo`
     * в poi.json — относительный путь от корня пакета). null — фото нет в данных
     * или файла нет на диске: карточка показывается без фото-полосы (fallback).
     */
    val selectedPoiPhoto: File? = null,
    /** Предложение загрузить регион для текущей локации (1.10); null — предложения нет. */
    val suggestion: RegionSuggestionUi? = null,
    /**
     * Позиция устройства для маркера «я» на карте (токен me-marker из DESIGN).
     * При записи — фиксы из TrackingStateBus (тот же поток, что питает запись, 1.7);
     * без записи — лёгкий слушатель экрана карты (событие onUserLocation).
     * null — разрешение не выдано или фикса ещё не было: маркер не рисуется.
     */
    val userLatitude: Double? = null,
    val userLongitude: Double? = null,
    /** Точность последнего фикса, метры (круг точности маркера «я»); null — неизвестна. */
    val userAccuracyMeters: Float? = null,
    /**
     * Время последнего фикса (мс UTC) — по нему UI отсекает устаревшие позиции
     * (полевой тест 2, дефект 3: старый lastKnownLocation маркером не показываем);
     * null — фикса ещё не было.
     */
    val userFixEpochMillis: Long? = null,
    /**
     * Версия пакета активного региона, чьи данные сейчас в состоянии — debug-оверлей
     * (история 1.14): диагностика «в памяти старая версия» (дефект fix4) без logcat.
     * null — пакет не установлен.
     */
    val packageVersion: Int? = null,
    /** Всего POI активного пакета в памяти (1.14); visiblePois — выдача после фильтра. */
    val poiTotalCount: Int = 0,
    /** Источник пакетов (1.14): debug-override vs DEFAULT_BASE_URL — видно, откуда качаем. */
    val packagesBaseUrl: String = "",
    /** Провайдер последнего маркерного фикса («gps»/«network») — debug-оверлей (1.14). */
    val userFixProvider: String? = null,
    /** Имя последнего вердикта отбраковки фильтра («TooFast»…) — debug-оверлей (1.14). */
    val lastRejectedVerdict: String? = null,
    /**
     * Идёт запись и последняя принятая точка вне области всех установленных пакетов
     * (разбор 2026-10-05: OutOfRegion не должна «молчать») — UI показывает статус
     * «Вы вне области пакета». Снимок из TrackingStateBus.
     */
    val outsideCoverage: Boolean = false,
    /**
     * Состояние сессии прогулки (2.1, снимок шины): null — записи в этой сессии
     * приложения ещё не было; FINISHED — последняя прогулка завершена (итог виден
     * до следующего старта). Активная/на паузе — строка состояния над кнопкой.
     */
    val walkState: WalkState? = null,
    /** Дистанция прогулки по принятым фильтром точкам, метры (2.1, AD-4: готовое значение из WalkSession). */
    val walkDistanceMeters: Double = 0.0,
    /** Чистое (активное) время прогулки без пауз, мс (2.1). Форматирование — только UI. */
    val walkActiveMillis: Long = 0L,
    /** Режим прогулки (2.2, FR-6): выбранный пользователем (персистится) или активный
     * из шины во время записи. По умолчанию — сохранённый выбор, иначе WALKING. */
    val walkMode: WalkMode = WalkMode.WALKING,
    /** Автопауза после неподвижности (2.4, FR-7); сервис читает на старте записи. */
    val autoPauseEnabled: Boolean = true,
    /** Напоминание о забытой прогулке (2.5, FR-7); сервис читает на старте записи. */
    val forgottenReminderEnabled: Boolean = true,
    /** Сводка завершённой прогулки (2.8, FR-8); null — экран не показываем. */
    val walkSummary: WalkSummaryUi? = null,
    /** Онбординг первого запуска (3.3, FR-12/FR-11); скрыт после цели. */
    val showOnboarding: Boolean = false,
    /** Активное задание прогулки (4.5); null — нет. */
    val questActive: QuestUi? = null,
    /** Выполненные задания (4.5; сегмент «Задания» вкладки Прогресс), новые первыми. */
    val questDone: List<QuestUi> = emptyList(),
    /** Кнопка задания видна (настройка, FR-16). */
    val showQuestButton: Boolean = true,
    /** Диалог выбора из 6 предложенных (4.5). */
    val questPickOptions: List<QuestUi> = emptyList(),
    /** Диалог подтверждения замены активного задания (id нового). */
    val questReplacePending: String? = null,
) {
    companion object {
        const val FALLBACK_STYLE_URI = "asset://scaffold-style.json"
        const val FALLBACK_LATITUDE = 59.9343
        const val FALLBACK_LONGITUDE = 30.3351
        const val FALLBACK_ZOOM = 13.0
        const val REGION_ZOOM = 10.0
    }
}

/** Активное задание для карточки на карте (4.5). */
data class QuestUi(val id: String, val icon: String, val name: String, val description: String)

/**
 * Состояние и события экрана карты (UDF). UI только рендерит [uiState] и отправляет события.
 * Биткарту открывает только domain (OpenTileAtPoint, AD-4); viewmodel читает снимки для рендера.
 *
 * Мульти-регион (1.10, AD-9): карта показывает [MapUiState.activeRegionId] (по умолчанию —
 * домашний: сохранённый выбор пользователя или первый установленный, HomeRegionPolicy);
 * туман/прогресс/POI — всегда активного региона и не смешиваются. Предложение загрузить
 * чужой регион приходит из TrackingStateBus (детект в domain по манифест-кэшу);
 * установка — существующим InstallRegionPackage (1.5), запись трека не прерывается.
 */
class MapViewModel(
    private val openTileAtPoint: OpenTileAtPoint,
    private val regionPackageRepository: RegionPackageRepository,
    private val fogRepository: FogRepository,
    private val trackingStateBus: TrackingStateBus,
    private val computeRegionProgress: ComputeRegionProgress,
    private val poiRepository: PoiRepository,
    private val settingsRepository: SettingsRepository,
    private val installRegionPackage: InstallRegionPackage,
    private val suggestInitialRegion: SuggestInitialRegion,
    /** История треков на диске (2026-10-06, «свеча»): восстановление ленты после перезапуска. */
    private val trackHistory: TrackHistory,
    /** Границы установленных пакетов (AD-9): приписывание точек трека активному региону. */
    private val boundaryRepository: BoundaryRepository,
    /** Последняя известная локация (lat, lon) или null — Android даёт её через DI (AD-10). */
    private val lastKnownLocation: () -> Pair<Double, Double>? = { null },
    /** Порт достижений прогулки (2.8): дефолт — пустая выдача до epic-achievements. */
    private val walkAchievements: WalkAchievements = WalkAchievements { _, _ -> emptyList() },
    /** Прочтённые карточки POI (4.1): запись при открытии карточки. */
    private val poiCardStore: app.walkquest.data.poicard.PoiCardStore =
        app.walkquest.data.poicard.PoiCardStore(java.io.File("poicards.json")),
    /** Задания (4.5); null — зависимости не подключены (тесты). */
    private val questDeps: QuestDeps? = null,
) : ViewModel() {

    /** Зависимости заданий (4.5) — пакет, чтобы не плодить nullable-параметры. */
    data class QuestDeps(
        val store: app.walkquest.domain.quests.QuestStateStore,
        val pool: app.walkquest.domain.quests.QuestPool,
        val machine: app.walkquest.domain.quests.QuestStateMachine,
        /** Действие после смены состояния заданий: перепроверка достижений (D1–D3). */
        val onChanged: () -> Unit,
    )

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    /** Последняя увиденная эпоха тумана из шины записи — перечитываем снимок только по изменению. */
    private var lastTrackingFogEpoch = 0

    /** Все POI активного региона (1.9) — в память, не в UiState: выдача режется фильтром. */
    private var allPois: List<Poi> = emptyList()

    /** Подсказки, отклонённые пользователем в этой сессии — карточка не всплывает повторно. */
    private val dismissedSuggestionIds = HashSet<String>()

    // --- «Свеча» (2026-10-06): лента раскрытия по принятым точкам трека ---

    /**
     * Инкрементальный строитель ленты активного региона (дедупликация цепочкой).
     * Начальное состояние — replay журналов в [loadRegion]; дальше растёт по шине записи.
     */
    private var ribbonBuilder = RibbonBuilder()

    /** Границы установленных регионов — приписывание точек ленты (перечитывается в loadRegion). */
    private var regionBoundaries: List<RegionBoundary> = emptyList()

    /** Счётчик принятых точек, уже учтённых в ленте из шины — защита от повторов снимка. */
    private var lastRibbonAcceptedCount = 0

    /** Прежний флаг записи из шины — старт новой сессии закрывает цепочку ленты (2026-10-08). */
    private var lastIsRecording = false

    /**
     * Регион точки по границам установленных пакетов (AD-9) или null вне всех.
     * Одна точка за раз — дёшево (bbox-префильтр + ray casting одного региона).
     */
    private fun locateRegion(latitude: Double, longitude: Double): String? =
        RegionLocator.locate(latitude, longitude, regionBoundaries)

    /** Восстановление ленты из журналов треков (IO): replay фильтра → свой регион →
     * дедуп. Цепочка — на журнал (сессию): записи друг с другом не соединяются
     * (полевой баг «прямых линий» 2026-10-08). */
    private fun loadRibbonFromJournals(regionId: String) {
        val sessions = trackHistory.acceptedPointsByJournal()
        val builder = RibbonBuilder()
        for (session in sessions) {
            builder.startNewChain()
            for (point in session) {
                if (locateRegion(point.latitude, point.longitude) == regionId) builder.add(point)
            }
        }
        ribbonBuilder = builder
    }

    /**
     * Версия пакета, чьи данные сейчас в состоянии (style/grid/POI/вода).
     * Обновление пакета из вкладки «Регионы» (v1→v2) меняет файлы на диске, но не это
     * состояние (дефект разбора 2026-10-05: после «Обновить» карта показывала старые
     * POI без фото) — расхождение ловит [onMapShown] при возврате на карту.
     */
    private var loadedPackageVersion: Int? = null

    init {
        _uiState.update {
            it.copy(
                packagesBaseUrl = regionPackageRepository.baseUrl,
                // Режим прогулки (2.2): сохранённый выбор, дефолт — WALKING.
                walkMode = settingsRepository.walkMode(),
                // Автопауза (2.4): сохранённая настройка, дефолт — включена.
                autoPauseEnabled = settingsRepository.autoPauseEnabled(),
                // Напоминание о забытой прогулке (2.5): дефолт — включено.
                forgottenReminderEnabled = settingsRepository.forgottenWalkReminderEnabled(),
                // Онбординг (3.3): показываем, пока нет флага ИЛИ нет цели
                // (FR-11: цель обязательна; пропуск обучения её не отменяет).
                showOnboarding = !settingsRepository.onboardingDone() ||
                    settingsRepository.walkGoal() == null,
                showQuestButton = settingsRepository.showQuestButton(),
            )
        }
        publishQuestState()
        loadInitialRegion()
        observeTracking()
    }

    /**
     * Выбор режима прогулки (2.2, FR-6): персистится (переживает перезапуск);
     * во время записи MapScreen дополнительно шлёт ACTION_SET_MODE сервису —
     * лимит фильтра меняется на лету, якорь не трогается.
     */
    fun onWalkModeSelected(mode: WalkMode) {
        settingsRepository.setWalkMode(mode)
        _uiState.update { it.copy(walkMode = mode) }
    }

    /**
     * Переключение автопаузы (2.4, FR-7): персистится; действует со старта
     * следующей записи (смена посреди прогулки — deferred-work: шина настроек
     * пока не существует, сценарий редкий).
     */
    fun onAutoPauseToggled() {
        val enabled = !_uiState.value.autoPauseEnabled
        settingsRepository.setAutoPauseEnabled(enabled)
        _uiState.update { it.copy(autoPauseEnabled = enabled) }
    }

    /** Переключение напоминания о забытой прогулке (2.5, FR-7): персистится. */
    fun onForgottenReminderToggled() {
        val enabled = !_uiState.value.forgottenReminderEnabled
        settingsRepository.setForgottenWalkReminderEnabled(enabled)
        _uiState.update { it.copy(forgottenReminderEnabled = enabled) }
    }

    /** «Закрыть» на финальном экране (2.8): сводка убирается до следующего финиша. */
    fun onWalkSummaryDismissed() {
        _uiState.update { it.copy(walkSummary = null) }
    }

    // --- Задания прогулки (4.5, FR-16) ---

    private fun questState() = questDeps?.store?.load() ?: app.walkquest.domain.quests.QuestState()

    private fun publishQuestState() {
        val deps = questDeps ?: return
        val state = questState()
        val active = state.activeId?.let { id -> app.walkquest.domain.quests.QuestCatalog.byId(id) }
        _uiState.update {
            it.copy(
                questActive = active?.let { q -> QuestUi(q.id, q.icon, q.name, q.description) },
                questDone = state.doneIds.mapNotNull { id ->
                    app.walkquest.domain.quests.QuestCatalog.byId(id)
                }.map { q -> QuestUi(q.id, q.icon, q.name, q.description) },
            )
        }
    }

    /** Взять случайное задание; при активном — диалог подтверждения замены. */
    fun onQuestRandom() {
        val deps = questDeps ?: return
        if (questState().activeId != null) {
            _uiState.update { it.copy(questReplacePending = "__random__") }
            return
        }
        takeQuest(deps.pool.random().id)
    }

    /** Показать выбор из 6 предложенных. */
    fun onQuestPickRequested() {
        val deps = questDeps ?: return
        _uiState.update {
            it.copy(questPickOptions = deps.pool.pickSix().map { q -> QuestUi(q.id, q.icon, q.name, q.description) })
        }
    }

    fun onQuestPickDismissed() {
        _uiState.update { it.copy(questPickOptions = emptyList()) }
    }

    /** Выбрано задание из шести (или случайный id через __random__ нельзя — только список). */
    fun onQuestPicked(questId: String) {
        _uiState.update { it.copy(questPickOptions = emptyList()) }
        if (questState().activeId != null) {
            _uiState.update { it.copy(questReplacePending = questId) }
            return
        }
        takeQuest(questId)
    }

    /** Подтверждение замены активного задания (прежнее снимается без штрафа). */
    fun onQuestReplaceConfirmed() {
        val pending = _uiState.value.questReplacePending ?: return
        _uiState.update { it.copy(questReplacePending = null) }
        val id = if (pending == "__random__") questDeps?.pool?.random()?.id else pending
        if (id != null) takeQuest(id, confirmed = true)
    }

    fun onQuestReplaceCancelled() {
        _uiState.update { it.copy(questReplacePending = null) }
    }

    private fun takeQuest(questId: String, confirmed: Boolean = false) {
        val deps = questDeps ?: return
        val state = questState()
        when (val result = deps.machine.take(state, questId, confirmed)) {
            is app.walkquest.domain.quests.QuestTakeResult.Taken -> {
                deps.store.save(deps.machine.applyTake(state, questId))
                deps.onChanged()
                publishQuestState()
            }
            is app.walkquest.domain.quests.QuestTakeResult.Invalid ->
                if (!confirmed) _uiState.update { it.copy(questReplacePending = questId) }
            is app.walkquest.domain.quests.QuestTakeResult.AlreadyActive -> Unit
        }
    }

    /** Выполнено на честном слове (FR-16). */
    fun onQuestCompleted() {
        val deps = questDeps ?: return
        deps.store.save(deps.machine.complete(questState()))
        deps.onChanged()
        publishQuestState()
    }

    /** «Снять задание» — отказ без штрафа. */
    fun onQuestDeclined() {
        val deps = questDeps ?: return
        deps.store.save(deps.machine.decline(questState()))
        publishQuestState()
    }

    /** Скрыть кнопку задания (настройка, FR-16). */
    fun onQuestButtonHidden() = onShowQuestButtonChanged(false)

    /** Видимость кнопки задания из настроек (FR-16): персистится, публикуется в UI. */
    fun onShowQuestButtonChanged(show: Boolean) {
        settingsRepository.setShowQuestButton(show)
        _uiState.update { it.copy(showQuestButton = show) }
    }

    /** Онбординг завершён (3.3): цель персистится, флаг поднят, оверлей скрыт. */
    fun onOnboardingComplete(goal: WalkGoal) {
        settingsRepository.setWalkGoal(goal)
        settingsRepository.setOnboardingDone(true)
        _uiState.update { it.copy(showOnboarding = false) }
    }

    /** Домашний регион по правилу domain: сохранённый → первый установленный → null. */
    private fun resolveHomeRegionId(): String? {
        val installedIds = regionPackageRepository.installedPackages().map { it.regionId }
        return HomeRegionPolicy.resolve(settingsRepository.homeRegionId(), installedIds)
    }

    /**
     * Готовая доля открытия из domain (AD-4: единственный вычислитель процента).
     * null — пакета нет или снимок не прочитался: прежнее значение состояния не трогаем.
     */
    private suspend fun currentProgressFraction(regionId: String): Double? = withContext(Dispatchers.IO) {
        when (val result = computeRegionProgress.compute(regionId)) {
            is ProgressResult.Ok -> result.progress.fraction
            else -> null
        }
    }

    /** UDF: состояние записи приходит из шины записи; при изменении биткарты — перечитываем просветы. */
    private fun observeTracking() {
        viewModelScope.launch {
            trackingStateBus.snapshot.collect { snap ->
                var opened: Set<Int>? = null
                var fraction: Double? = null
                if (snap.fogEpoch != lastTrackingFogEpoch) {
                    lastTrackingFogEpoch = snap.fogEpoch
                    val grid = _uiState.value.grid
                    val regionId = _uiState.value.activeRegionId
                    if (grid != null && regionId != null) {
                        opened = withContext(Dispatchers.IO) {
                            fogRepository.openedIndices(regionId, grid)
                        }
                        fraction = currentProgressFraction(regionId)
                    }
                }
                // «Свеча v2» (2026-10-06): принятая точка → осевая тропы активного
                // региона. Координаты берём из снимка шины (не маркерный фикс —
                // засчитанную точку); регион точки — по границам пакетов (AD-9),
                // чужие/внешние точки в тропу активного региона не попадают.
                // Редкий пропуск точки при конфляции StateFlow визуально скрыт
                // буфером (шаг 9 м, радиус 15 м) и восполняется replay журнала
                // при следующем запуске.
                var ribbonChanged = false
                // Старт новой записи — новая цепочка ленты: сессии не соединяются
                // (полевой баг «прямых линий» 2026-10-08).
                if (snap.isRecording && !lastIsRecording) {
                    ribbonBuilder.startNewChain()
                }
                lastIsRecording = snap.isRecording
                if (snap.acceptedCount < lastRibbonAcceptedCount) {
                    lastRibbonAcceptedCount = snap.acceptedCount // новая запись — счётчики шины сброшены
                }
                if (snap.acceptedCount > lastRibbonAcceptedCount) {
                    lastRibbonAcceptedCount = snap.acceptedCount
                    val lat = snap.lastAcceptedLatitude
                    val lon = snap.lastAcceptedLongitude
                    val regionId = _uiState.value.activeRegionId
                    if (lat != null && lon != null && regionId != null &&
                        locateRegion(lat, lon) == regionId
                    ) {
                        ribbonChanged = ribbonBuilder.add(
                            TrackPoint(lat, lon, snap.lastFixEpochMillis ?: 0L),
                        )
                    }
                }
                // Финальный экран (2.8, FR-8): финиш сессии (ручной стоп или
                // автозавершение по батарее 2.6) — собираем сводку один раз;
                // старт новой записи её очищает. Процент — готовая доля из
                // domain (AD-4), ачивки — порт (пусто до epic-achievements).
                var summary = _uiState.value.walkSummary
                if (snap.walkState == WalkState.FINISHED && summary == null) {
                    summary = WalkSummaryUi(
                        praise = WalkPraise.forDistance(snap.walkDistanceMeters),
                        distanceMeters = snap.walkDistanceMeters,
                        activeMillis = snap.walkActiveMillis,
                        progressFraction = _uiState.value.activeRegionId
                            ?.let { currentProgressFraction(it) },
                        achievements = walkAchievements.forWalk(
                            snap.walkDistanceMeters,
                            snap.walkActiveMillis,
                        ),
                    )
                }
                if (snap.isRecording) summary = null
                _uiState.update {
                    it.copy(
                        isRecording = snap.isRecording,
                        recordedPoints = snap.acceptedCount,
                        rejectedPoints = snap.rejectedCount,
                        openedIndices = opened ?: it.openedIndices,
                        ribbonChains = if (ribbonChanged) ribbonBuilder.chainsPoints.map { c -> c.toList() } else it.ribbonChains,
                        progressFraction = fraction ?: it.progressFraction,
                        fogEpoch = if (opened != null || ribbonChanged) it.fogEpoch + 1 else it.fogEpoch,
                        // Позиция «я» во время записи — из шины (тот же источник, что трек).
                        userLatitude = snap.lastLatitude ?: it.userLatitude,
                        userLongitude = snap.lastLongitude ?: it.userLongitude,
                        userAccuracyMeters = snap.lastAccuracyMeters ?: it.userAccuracyMeters,
                        userFixEpochMillis = snap.lastFixEpochMillis ?: it.userFixEpochMillis,
                        // Debug-оверлей (1.14): провайдер маркерного фикса и вердикт фильтра.
                        userFixProvider = snap.lastFixProvider ?: it.userFixProvider,
                        lastRejectedVerdict = snap.lastRejectedVerdict ?: it.lastRejectedVerdict,
                        // Карточку первого (домашнего) региона шина записи не трогает (D1).
                        suggestion = if (it.suggestion?.isInitialHome == true) {
                            it.suggestion
                        } else {
                            suggestionFor(snap.suggestedRegionId, it.suggestion)
                        },
                        outsideCoverage = snap.outsideCoverage,
                        // Сессия прогулки (2.1): состояние + агрегаты из шины (AD-4).
                        walkState = snap.walkState,
                        walkDistanceMeters = snap.walkDistanceMeters,
                        walkActiveMillis = snap.walkActiveMillis,
                        // Режим из шины (2.2) — во время записи сервис решает.
                        walkMode = snap.walkMode ?: it.walkMode,
                        // Сводка финального экрана (2.8).
                        walkSummary = summary,
                    )
                }
            }
        }
    }

    /**
     * Подсказка из шины → карточка. Не перетираем карточку с живой установкой
     * (шина продолжает слать тот же regionId, пока точка в чужом регионе);
     * отклонённые в сессии регионы не всплывают повторно.
     */
    private fun suggestionFor(regionId: String?, current: RegionSuggestionUi?): RegionSuggestionUi? {
        if (current != null && current.regionId == regionId) return current
        if (regionId == null || regionId in dismissedSuggestionIds) return null
        if (regionPackageRepository.getInstalled(regionId) != null) return null
        val name = regionPackageRepository.cachedManifest()
            ?.packages?.firstOrNull { it.regionId == regionId }?.name
        return RegionSuggestionUi(regionId, name ?: regionId)
    }

    /** Первичная загрузка: домашний регион (правило domain) → его карта; без пакетов — запасной стиль. */
    private fun loadInitialRegion() {
        viewModelScope.launch {
            val home = withContext(Dispatchers.IO) { resolveHomeRegionId() }
            _uiState.update { it.copy(homeRegionId = home) }
            loadRegion(home)
            if (home == null) suggestInitialHomeRegion()
        }
    }

    /**
     * Первый запуск без установленных регионов (дефект D1 прогона 2026-10-05,
     * EXPERIENCE Flow 2): карточка «Загрузите домашний регион» — регион по геолокации
     * (bbox манифеста, DetectForeignRegion) или первый из манифеста. Сеть недоступна
     * или манифест пуст — карточки нет, остаёмся на запасном стиле.
     */
    private fun suggestInitialHomeRegion() {
        viewModelScope.launch {
            val suggestion = withContext(Dispatchers.IO) {
                val location = lastKnownLocation()
                suggestInitialRegion(location?.first, location?.second)
            } ?: return@launch
            _uiState.update {
                // Пока шла сеть, регион могли установить другим путём — карточку не показываем.
                if (it.regionInstalled || it.suggestion != null) {
                    it
                } else {
                    it.copy(
                        suggestion = RegionSuggestionUi(
                            regionId = suggestion.regionId,
                            name = suggestion.name ?: suggestion.regionId,
                            isInitialHome = true,
                        ),
                    )
                }
            }
        }
    }

    /** Читает установленный пакет региона, сетку, биткарту и POI с диска (офлайн, AD-2). */
    private fun loadRegion(regionId: String?) {
        viewModelScope.launch {
            if (regionId == null) {
                allPois = emptyList()
                loadedPackageVersion = null
                regionBoundaries = emptyList()
                ribbonBuilder = RibbonBuilder()
                lastRibbonAcceptedCount = 0
                _uiState.update {
                    it.copy(
                        activeRegionId = null,
                        regionInstalled = false,
                        styleUri = MapUiState.FALLBACK_STYLE_URI,
                        cameraLatitude = MapUiState.FALLBACK_LATITUDE,
                        cameraLongitude = MapUiState.FALLBACK_LONGITUDE,
                        cameraZoom = MapUiState.FALLBACK_ZOOM,
                        grid = null,
                        openedIndices = emptySet(),
                        ribbonChains = emptyList(),
                        progressFraction = null,
                        visiblePois = emptyList(),
                        selectedPoi = null,
                        selectedPoiPhoto = null,
                        packageVersion = null,
                        poiTotalCount = 0,
                        fogEpoch = it.fogEpoch + 1,
                    )
                }
                return@launch
            }
            val loaded = withContext(Dispatchers.IO) {
                val pkg = regionPackageRepository.getInstalled(regionId) ?: return@withContext null
                val grid = when (val result = fogRepository.loadGrid(pkg)) {
                    is GridResult.Err -> return@withContext null
                    is GridResult.Ok -> result.grid
                }
                val opened = fogRepository.openedIndices(regionId, grid)
                // Битый poi.json не роняет карту: маяков просто нет (матрица истории 1.9).
                val pois = when (val result = poiRepository.loadPois(pkg)) {
                    is PoiResult.Err -> emptyList()
                    is PoiResult.Ok -> result.pois
                }
                LoadedRegion(
                    pkg.styleFile.absolutePath,
                    pkg.packageVersion,
                    grid,
                    opened,
                    pois,
                )
            } ?: return@launch
            allPois = loaded.pois
            loadedPackageVersion = loaded.packageVersion
            // «Свеча»: границы всех установленных регионов (атрибуция точек, AD-9)
            // и восстановление ленты активного региона из журналов треков (перезапуск).
            withContext(Dispatchers.IO) {
                regionBoundaries = regionPackageRepository.installedPackages().mapNotNull { pkg ->
                    when (val result = boundaryRepository.load(pkg)) {
                        is BoundaryResult.Ok -> result.boundary
                        is BoundaryResult.Err -> null
                    }
                }
                loadRibbonFromJournals(regionId)
            }
            val fraction = currentProgressFraction(regionId)
            // Стартовая выдача маяков: весь регион на обзорном зуме → тир 1 (FR-18).
            val initialVisible = PoiFilter.selectPois(
                loaded.pois,
                PoiBounds(
                    loaded.grid.originLat, loaded.grid.northLat,
                    loaded.grid.originLon, loaded.grid.eastLon,
                ),
                MapUiState.REGION_ZOOM,
            )
            _uiState.update {
                it.copy(
                    activeRegionId = regionId,
                    regionInstalled = true,
                    styleUri = "file://${loaded.stylePath}",
                    cameraLatitude = (loaded.grid.originLat + loaded.grid.northLat) / 2.0,
                    cameraLongitude = (loaded.grid.originLon + loaded.grid.eastLon) / 2.0,
                    cameraZoom = MapUiState.REGION_ZOOM,
                    grid = loaded.grid,
                    openedIndices = loaded.openedIndices,
                    ribbonChains = ribbonBuilder.chainsPoints.map { c -> c.toList() },
                    progressFraction = fraction,
                    fogEpoch = it.fogEpoch + 1,
                    visiblePois = initialVisible,
                    selectedPoi = null,
                    selectedPoiPhoto = null,
                    lastOpenError = null,
                    packageVersion = loaded.packageVersion,
                    poiTotalCount = loaded.pois.size,
                )
            }
        }
    }

    /** Переход к карте другого установленного региона (из вкладки «Регионы»). */
    fun onRegionSelected(regionId: String) {
        if (regionId == _uiState.value.activeRegionId) return
        loadRegion(regionId)
    }

    /**
     * Карта на экране (вход в композицию вкладки / ON_RESUME): перечитываем активный
     * регион, если пакет на диске обновился (дефект разбора 2026-10-05: «Обновить» v1→v2
     * во вкладке «Регионы» не инвалидировал состояние карты — оставались старые POI
     * без фото, старый style.json со старым pmtiles-путём и старая сетка тумана).
     * Сравнение по версии: та же версия — ничего не делаем (диск не дёргаем зря);
     * пакет удалён — состояние не трогаем (сценарий удаления региона пока не существует).
     */
    fun onMapShown() {
        val regionId = _uiState.value.activeRegionId ?: return
        viewModelScope.launch {
            val installed = withContext(Dispatchers.IO) {
                regionPackageRepository.getInstalled(regionId)
            } ?: return@launch
            if (installed.packageVersion != loadedPackageVersion) {
                loadRegion(regionId)
            }
        }
    }

    /** Установка предложенного региона явным действием (кнопка карточки); трек не прерывается. */
    fun onInstallSuggested() {
        val suggestion = _uiState.value.suggestion ?: return
        if (suggestion.installing) return
        _uiState.update { it.copy(suggestion = suggestion.copy(installing = true, error = null)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                installRegionPackage(suggestion.regionId)
            }
            when (result) {
                is PackageInstallResult.Success, is PackageInstallResult.AlreadyInstalled -> {
                    // Карта нового региона; домашний перечитываем по правилу domain:
                    // после первой установки им становится единственный регион (AD-9).
                    val home = withContext(Dispatchers.IO) { resolveHomeRegionId() }
                    _uiState.update { it.copy(suggestion = null, homeRegionId = home) }
                    loadRegion(suggestion.regionId)
                }
                is PackageInstallResult.Failure -> {
                    _uiState.update {
                        val current = it.suggestion ?: return@update it
                        it.copy(suggestion = current.copy(installing = false, error = result.error.message))
                    }
                }
            }
        }
    }

    /** Отклонить предложение: до конца сессии карточка этого региона не всплывает. */
    fun onDismissSuggestion() {
        val regionId = _uiState.value.suggestion?.regionId ?: return
        dismissedSuggestionIds += regionId
        _uiState.update { it.copy(suggestion = null) }
    }

    /** Карта остановилась: пересчёт видимой выдачи POI по viewport и зуму (тот же UDF-поток). */
    fun onViewportChanged(bounds: PoiBounds, zoom: Double) {
        val pois = allPois
        if (pois.isEmpty()) return
        viewModelScope.launch {
            val visible = withContext(Dispatchers.Default) {
                PoiFilter.selectPois(pois, bounds, zoom)
            }
            _uiState.update { it.copy(visiblePois = visible) }
        }
    }

    /** Тап по маркеру POI: открыть карточку места (данные уже в памяти — офлайн).
     *  4.1: открытие карточки = прочтение (достижения A11–A16). */
    fun onPoiTapped(osmId: String) {
        val poi = allPois.find { it.osmId == osmId } ?: return
        viewModelScope.launch {
            val photo = withContext(Dispatchers.IO) { resolvePoiPhoto(poi) }
            withContext(Dispatchers.IO) { poiCardStore.markRead(osmId) }
            _uiState.update { it.copy(selectedPoi = poi, selectedPoiPhoto = photo) }
        }
    }

    /**
     * Фото POI из установленного пакета (пакет v2): поле `photo` — относительный путь
     * от корня пакета; резолвим в файл на диске. Пути «../» отсекаем (данные не должны
     * выводить за пределы пакета), отсутствующий файл → null (fallback без полосы).
     */
    private fun resolvePoiPhoto(poi: Poi): File? {
        val relative = poi.photo?.takeIf { it.isNotBlank() } ?: return null
        val regionId = _uiState.value.activeRegionId ?: return null
        val pkg = regionPackageRepository.getInstalled(regionId) ?: return null
        val root = pkg.rootDir.canonicalFile
        val file = File(root, relative).canonicalFile
        if (!file.toPath().startsWith(root.toPath())) return null
        return file.takeIf { it.isFile }
    }

    /** Тап вне карточки / свайп вниз / кнопка закрытия. */
    fun onPoiCardDismissed() {
        _uiState.update { it.copy(selectedPoi = null, selectedPoiPhoto = null) }
    }

    /**
     * Позиция от лёгкого слушателя экрана карты (маркер «я», когда запись выключена).
     * Во время записи позицию даёт шина (observeTracking) — событие игнорируется,
     * чтобы два источника не дёргали состояние вперёд-назад.
     * [fixEpochMillis] — время фикса (Location.time): UI по нему отсекает устаревшие
     * позиции (дефект 3 полевого теста 2 — старый lastKnownLocation).
     */
    fun onUserLocation(latitude: Double, longitude: Double, accuracyMeters: Float?, fixEpochMillis: Long) {
        if (_uiState.value.isRecording) return
        _uiState.update {
            it.copy(
                userLatitude = latitude,
                userLongitude = longitude,
                userAccuracyMeters = accuracyMeters,
                userFixEpochMillis = fixEpochMillis,
            )
        }
    }

    /**
     * Тестовая точка (ручная проверка 1.6 на устройстве): проходит путь
     * ui → viewmodel → domain → data и открывает реальный тайл тумана у Дворцовой площади.
     * Мульти-регион (1.10): domain сам находит регион точки (СПб); снимок на экране
     * обновляем для активного региона, каким бы он ни был.
     */
    fun onTraceTestPoint() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                openTileAtPoint.openAt(TEST_POINT_LATITUDE, TEST_POINT_LONGITUDE)
            }
            when (result) {
                is OpenResult.Changed -> {
                    val grid = _uiState.value.grid ?: return@launch
                    val regionId = _uiState.value.activeRegionId ?: return@launch
                    val opened = withContext(Dispatchers.IO) {
                        fogRepository.openedIndices(regionId, grid)
                    }
                    // «Свеча»: тестовая точка — тоже точка-отметка, ей круг ленты,
                    // иначе ручная проверка не даёт видимого просвета (рендер по точкам).
                    ribbonBuilder.add(
                        TrackPoint(TEST_POINT_LATITUDE, TEST_POINT_LONGITUDE, System.currentTimeMillis()),
                    )
                    val fraction = currentProgressFraction(regionId)
                    _uiState.update {
                        it.copy(
                            openedIndices = opened,
                            ribbonChains = ribbonBuilder.chainsPoints.map { c -> c.toList() },
                            progressFraction = fraction ?: it.progressFraction,
                            fogEpoch = it.fogEpoch + 1,
                            lastOpenError = null,
                        )
                    }
                }
                is OpenResult.Unchanged, OpenResult.OutOfRegion, OpenResult.NoRegion ->
                    _uiState.update { it.copy(lastOpenError = null) }
                is OpenResult.Failure ->
                    _uiState.update { it.copy(lastOpenError = result.error.message) }
            }
        }
    }

    private companion object {
        // Тестовая точка — центр Санкт-Петербурга (Дворцовая площадь).
        const val TEST_POINT_LATITUDE = 59.9343
        const val TEST_POINT_LONGITUDE = 30.3351
    }
}

/** Снимок чтения пакета с диска при загрузке региона (loadRegion). */
private data class LoadedRegion(
    val stylePath: String,
    val packageVersion: Int,
    val grid: FogGrid,
    val openedIndices: Set<Int>,
    val pois: List<Poi>,
)
