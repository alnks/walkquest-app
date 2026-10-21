package app.walkquest.di

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.walkquest.data.boundaries.BoundaryRepository
import app.walkquest.data.fog.FogRepository
import app.walkquest.data.packages.PackageSource
import app.walkquest.data.packages.RegionPackageRepository
import app.walkquest.data.plans.PlannedWalksStore
import app.walkquest.data.poi.PoiRepository
import app.walkquest.data.poicard.PoiCardStore
import app.walkquest.data.settings.SettingsRepository
import app.walkquest.domain.ComputeRegionProgress
import app.walkquest.domain.GoalReminderPolicy
import app.walkquest.domain.InstallRegionPackage
import app.walkquest.domain.PlanPolicy
import app.walkquest.domain.WalkAchievements
import app.walkquest.domain.achievements.AchievementCatalog
import app.walkquest.domain.achievements.AchievementEngine
import app.walkquest.domain.achievements.AchievementEvents
import app.walkquest.domain.achievements.AchievementStateFactory
import app.walkquest.domain.achievements.EarnedStore
import app.walkquest.domain.quests.QuestPool
import app.walkquest.domain.quests.QuestStateMachine
import app.walkquest.domain.quests.QuestStateStore
import app.walkquest.domain.OpenTileAtPoint
import app.walkquest.domain.SuggestInitialRegion
import app.walkquest.domain.WalkHistory
import app.walkquest.domain.DistrictProgressCalculator
import app.walkquest.domain.StreakCalculator
import app.walkquest.domain.territory.DetectForeignRegion
import app.walkquest.tracking.TrackHistory
import app.walkquest.tracking.TrackingStateBus
import app.walkquest.tracking.WalkHistoryStore
import app.walkquest.tracking.WalkSessionStore
import app.walkquest.viewmodel.AchievementsViewModel
import app.walkquest.viewmodel.MapViewModel
import app.walkquest.viewmodel.RatingViewModel
import app.walkquest.viewmodel.RegionsViewModel
import app.walkquest.viewmodel.StatsViewModel
import java.io.File

/**
 * Ручное внедрение зависимостей (spine Deferred: Hilt — только если ручной станет мешать).
 * Туман (1.6): FogRepository (data, файлы `fog/<regionId>.fog`) → OpenTileAtPoint (domain,
 * единственный путь открытия битов, AD-4) → MapViewModel (viewmodel).
 * Пакеты регионов (1.5): RegionPackageRepository (data) → InstallRegionPackage (domain);
 * тот же репозиторий поставляет domain установленные пакеты как источник сеток тумана.
 * Территория (1.10, AD-9): BoundaryRepository (data, boundaries.geojson пакетов) →
 * RegionLocator/DetectForeignRegion (domain: регион точки — только по границам пакета);
 * домашний регион — SettingsRepository (data, settings.json) + HomeRegionPolicy (domain).
 *
 * [filesDir] — context.filesDir из Application; data-слои сами Android не импортируют
 * (JVM-тестируемость), корни хранилищ приходят сюда.
 */
class AppContainer(
    private val filesDir: File,
    /**
     * Последняя известная локация (lat, lon) или null — живёт в Android-слое
     * (WalkQuestApplication), сюда приходит функцией: viewmodel и domain остаются
     * чистым Kotlin (AD-10). Используется только предложением первого региона (D1).
     */
    lastKnownLocation: () -> Pair<Double, Double>? = { null },
    /**
     * Флаг debuggable-сборки (ApplicationInfo.FLAG_DEBUGGABLE) из Android-слоя.
     * В debug-режиме источник пакетов может быть переопределён файлом
     * `files/qa/packages-base-url.txt` (`_qa/wq.py push-base-url`, QA-контур).
     */
    debugEnabled: Boolean = false,
) {

    val fogRepository: FogRepository = FogRepository(fogRoot = File(filesDir, "fog"))

    val regionPackageRepository: RegionPackageRepository =
        RegionPackageRepository(
            packagesRoot = File(filesDir, "packages"),
            // DEBUG: files/qa/packages-base-url.txt может переопределить источник
            // на локальный сервер `_qa/wq.py serve` (QA-контур без публикации).
            baseUrl = PackageSource.effectiveBaseUrl(filesDir, debugEnabled),
        )

    val installRegionPackage: InstallRegionPackage = InstallRegionPackage(regionPackageRepository)

    /** Границы регионов из пакетов (1.10): парсинг boundaries.geojson → голая геометрия для domain. */
    val boundaryRepository: BoundaryRepository = BoundaryRepository()

    /** Настройки (1.10): домашний регион, персистентно в settings/settings.json. */
    val settingsRepository: SettingsRepository = SettingsRepository(settingsRoot = File(filesDir, "settings"))

    /** Мульти-регион (1.10): точка → её регион по границам пакетов → бит только его биткарты. */
    val openTileAtPoint: OpenTileAtPoint =
        OpenTileAtPoint(fogRepository, regionPackageRepository, boundaryRepository)

    /** Детект «локация в неустановленном регионе» (1.10): границы установленных + bbox манифест-кэша. */
    val detectForeignRegion: DetectForeignRegion =
        DetectForeignRegion(regionPackageRepository, boundaryRepository)

    /** Предложение первого (домашнего) региона при запуске без пакетов (дефект D1 прогона 2026-10-05). */
    val suggestInitialRegion: SuggestInitialRegion =
        SuggestInitialRegion(regionPackageRepository, detectForeignRegion)

    /** Процент открытия (1.8/1.10): единственный вычислитель в domain (AD-4), регион — параметр. */
    val computeRegionProgress: ComputeRegionProgress =
        ComputeRegionProgress(fogRepository, regionPackageRepository)

    /** Мост состояния записи (1.7/1.10): TrackingService публикует, MapViewModel собирает. */
    val trackingStateBus: TrackingStateBus = TrackingStateBus()

    /** POI-маяки (1.9, FR-18): poi.json установленного пакета, офлайн, кэш в памяти. */
    val poiRepository: PoiRepository = PoiRepository()

    /** История треков (2026-10-06, «свеча»): replay журналов → точки ленты раскрытия. */
    val trackHistory: TrackHistory = TrackHistory(tracksRoot = File(filesDir, "tracks"))

    /** Сессия прогулки (2.1): снимок session/session.json — восстановление после убийства процесса (AD-5). */
    val walkSessionStore: WalkSessionStore = WalkSessionStore(File(filesDir, "session/session.json"))

    /** История завершённых прогулок (3.1, FR-13): append-only walks/walks.jsonl — источник истины агрегатов. */
    val walkHistoryStore: WalkHistoryStore = WalkHistoryStore(File(filesDir, "walks/walks.jsonl"))

    /** Агрегаты истории (3.1, AD-4): серии/статистика/цель читают только здесь. */
    val walkHistory: WalkHistory = WalkHistory(walkHistoryStore)

    /** Серия прогулок (3.4, FR-13) — поверх истории (3.1). */
    val streakCalculator: StreakCalculator = StreakCalculator(walkHistory)

    /** Умные напоминания по цели (3.5, FR-14): решение и текст — в domain (AD-4). */
    val goalReminderPolicy: GoalReminderPolicy = GoalReminderPolicy(walkHistory)

    /** Планировщик прогулок (3.6, FR-17): хранилище планов + политика выполнения. */
    val plannedWalksStore: PlannedWalksStore = PlannedWalksStore(File(filesDir, "plans/plans.json"))
    val planPolicy: PlanPolicy = PlanPolicy(walkHistory)

    /** Прочтённые карточки POI (4.1): достижения A11–A16. */
    val poiCardStore: PoiCardStore = PoiCardStore(File(filesDir, "poicards.json"))

    // --- Достижения и задания (эпик 4) ---

    /** Полученные достижения (4.2). */
    val earnedStore: EarnedStore = EarnedStore(File(filesDir, "achievements/earned.json"))

    /** Движок достижений (4.2). */
    val achievementEngine: AchievementEngine = AchievementEngine(earnedStore = earnedStore)

    /** Районный прогресс (4.3). */
    val districtProgressCalculator: DistrictProgressCalculator =
        DistrictProgressCalculator(regionPackageRepository, boundaryRepository, fogRepository)

    /** Состояние заданий (4.5). */
    val questStateStore: QuestStateStore = QuestStateStore(File(filesDir, "quests/quests.json"))

    /** Пул и машина заданий (4.5). */
    val questPool: QuestPool = QuestPool()
    val questMachine: QuestStateMachine = QuestStateMachine()

    /** Сборка состояния достижений (4.6). */
    val achievementStateFactory: AchievementStateFactory = AchievementStateFactory(
        walkHistory, streakCalculator, computeRegionProgress, districtProgressCalculator,
        poiCardStore, poiRepository, regionPackageRepository, settingsRepository,
        questStateStore,
    )

    /** Порт ачивок прогулки (2.8/4.6): новые достижения последней прогулки. */
    val walkAchievementsPort: WalkAchievements = WalkAchievements { _, _ ->
        AchievementEvents.lastWalkNewIds.mapNotNull { AchievementCatalog.byId(it)?.name }
    }

    // --- Лидерборд (эпик 5, AD-7) ---

    /** URL и anon-key проекта Supabase (local.properties / BuildConfig; пусто — до настройки проекта). */
    private val supabaseUrl: String = try {
        app.walkquest.BuildConfig.SUPABASE_URL
    } catch (e: Throwable) {
        ""
    }
    private val supabaseKey: String = try {
        app.walkquest.BuildConfig.SUPABASE_ANON_KEY
    } catch (e: Throwable) {
        ""
    }

    /** API лидерборда; null — проект не настроен (экран покажет «не настроено»). */
    val leaderboardApi: app.walkquest.data.leaderboard.LeaderboardApi? =
        if (supabaseUrl.isNotBlank() && supabaseKey.isNotBlank()) {
            app.walkquest.data.leaderboard.LeaderboardApi("$supabaseUrl/rest/v1", supabaseKey)
        } else {
            null
        }

    /** Офлайн-очередь агрегатов (FR-19). */
    val leaderboardOutbox: app.walkquest.data.leaderboard.LeaderboardOutbox =
        app.walkquest.data.leaderboard.LeaderboardOutbox(File(filesDir, "leaderboard/outbox.json"))

    // --- Перенос данных и публикация (эпик 6) ---

    /** Источники экспорта (6.1) собираются на каждый вызов: список установленных
     *  регионов в манифест должен быть свежим (диск, AD-2). */
    fun buildExportSources(): app.walkquest.data.transfer.ExportSources =
        app.walkquest.data.transfer.ExportSources(
            settingsRoot = File(filesDir, "settings"),
            fogRoot = File(filesDir, "fog"),
            walksFile = File(filesDir, "walks/walks.jsonl"),
            earnedFile = File(filesDir, "achievements/earned.json"),
            questsFile = File(filesDir, "quests/quests.json"),
            plansFile = File(filesDir, "plans/plans.json"),
            poiCardsFile = File(filesDir, "poicards.json"),
            tracksRoot = File(filesDir, "tracks"),
            appVersion = try {
                app.walkquest.BuildConfig.VERSION_NAME
            } catch (e: Throwable) {
                ""
            },
            // distinct(): installedPackages() возвращает запись на КАЖДУЮ версию
            // директории v* — после обновления пакета регион повторяется (нахватал
            // полевой прогон 2026-10-11: ["spb-city","spb-city"] в манифесте).
            installedRegionIds = regionPackageRepository.installedPackages().map { it.regionId }.distinct(),
        )

    /** Цели импорта (6.2): активная сессия не переносится — удаляется перед импортом. */
    val importTargets: app.walkquest.data.transfer.ImportTargets =
        app.walkquest.data.transfer.ImportTargets(
            settingsRoot = File(filesDir, "settings"),
            fogRoot = File(filesDir, "fog"),
            walksFile = File(filesDir, "walks/walks.jsonl"),
            earnedFile = File(filesDir, "achievements/earned.json"),
            questsFile = File(filesDir, "quests/quests.json"),
            plansFile = File(filesDir, "plans/plans.json"),
            poiCardsFile = File(filesDir, "poicards.json"),
            tracksRoot = File(filesDir, "tracks"),
            sessionFile = File(filesDir, "session/session.json"),
        )

    /** Вкладка «Профиль» (6.3): перенос прогресса, предупреждение о потере, «О приложении». */
    val profileViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            app.walkquest.viewmodel.ProfileViewModel(
                settingsRepository = settingsRepository,
                regionPackageRepository = regionPackageRepository,
                installRegionPackage = installRegionPackage,
                exportSources = { buildExportSources() },
                importTargets = { importTargets },
                appVersion = try {
                    app.walkquest.BuildConfig.VERSION_NAME
                } catch (e: Throwable) {
                    ""
                },
                privacyUrl = PRIVACY_POLICY_URL,
                onImportApplied = {
                    // FR-15: восстановленная история → выдача достижений задним числом.
                    runAchievementCheck()
                },
            )
        }
    }

    companion object {
        /** Политика конфиденциальности: заглушка до публикации (6.5), URL подменится. */
        const val PRIVACY_POLICY_URL = "https://walkquest.app/privacy"
    }

    val ratingViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            RatingViewModel(
                settingsRepository,
                leaderboardApi,
                leaderboardOutbox,
                walkHistory,
                computeRegionProgress,
                installedRegionIds = { regionPackageRepository.installedPackages().map { it.regionId } },
                onJoined = {
                    // D4: вступление в рейтинг — перепроверка достижений.
                    runAchievementCheck()
                },
                onFriendAccepted = { runAchievementCheck() },
            )
        }
    }

    /** Полная перепроверка достижений (после прогулки / на старте / после заданий). */
    fun runAchievementCheck() {
        val fresh = achievementEngine.checkAndRecord(achievementStateFactory.build())
        AchievementEvents.lastWalkNewIds = fresh.map { it.id }
    }

    val mapViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            MapViewModel(
                openTileAtPoint,
                regionPackageRepository,
                fogRepository,
                trackingStateBus,
                computeRegionProgress,
                poiRepository,
                settingsRepository,
                installRegionPackage,
                suggestInitialRegion,
                trackHistory,
                boundaryRepository,
                lastKnownLocation,
                // Ачивки прогулки (2.8/4.6): новые достижения последней прогулки.
                walkAchievementsPort,
                // Серия прогулок для сетки финала (7.2).
                streakCalculator,
                // Прочтённые карточки POI (4.1).
                poiCardStore,
                // Задания прогулки (4.5).
                MapViewModel.QuestDeps(
                    store = questStateStore,
                    pool = questPool,
                    machine = questMachine,
                    onChanged = { runAchievementCheck() },
                ),
            )
        }
    }

    val regionsViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            RegionsViewModel(
                regionPackageRepository,
                settingsRepository,
                computeRegionProgress,
                installRegionPackage,
            )
        }
    }

    /** Экран наград (4.4): полная перепроверка достижений по истории. */
    val achievementsViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            AchievementsViewModel(achievementEngine, achievementStateFactory)
        }
    }

    /** Экран статистики (3.4, FR-13; 7.4: герой-процент домашнего региона,
     *  сетка 6 карточек, средние метрики — агрегаты достижений/заданий/серий). */
    val statsViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            StatsViewModel(
                walkHistory,
                streakCalculator,
                computeRegionProgress,
                regionPackageRepository,
                settingsRepository,
                plannedWalksStore,
                planPolicy,
                achievementEngine,
                achievementStateFactory,
                questStateStore,
            )
        }
    }
}
