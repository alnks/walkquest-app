package app.walkquest.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.walkquest.MainActivity
import app.walkquest.R
import app.walkquest.WalkQuestApplication
import app.walkquest.domain.OpenResult
import java.io.File

/**
 * Foreground-сервис записи трека — прототип AD-8 (история 1.7, AD-5).
 *
 * Единственное место пакета tracking с android-зависимостями (AD-10): фильтр,
 * журнал и шина — чистый Kotlin. Источник локации — LocationManager: GPS **и**
 * NETWORK провайдеры (полевой тест 3, 2026-10-05: в помещении GPS молчит и маркер
 * «я» пропадал на время записи; сетевой фикс маркеру лучше, чем никакого).
 * Офлайн-first (AD-2), новых зависимостей (play-services-location) не вводим.
 *
 * Поток точки: LocationManager → журнал (append+fsync, AD-5) → фильтр →
 * domain OpenTileAtPoint (AD-4, единственный путь открытия битов) → шина состояния.
 * Открытие тайлов сетевыми фиксами отсекает PointFilter (accuracy ≤ 50 м) —
 * маркер и механика тумана питаются из одного потока, но решают разное
 * (маркер — свежайший фикс любого провайдера, [MarkerFixPicker]).
 * Колбэки на HandlerThread — дисковая запись не на main-потоке.
 * Уведомление и логи координат не содержат (AD-3).
 *
 * Не входит (epic-walk-tracking): автопауза (2.4), батарейный страж (2.6),
 * финальный экран (2.8), Room-история. Агрегаты сессии (дистанция/чистое время)
 * — история 2.1, в WalkSession; сервис только публикует их в шину (AD-4).
 */
class TrackingService : Service() {

    private var worker: HandlerThread? = null
    private var journal: TrackJournal? = null
    private var filter = PointFilter()
    private var markerPicker = MarkerFixPicker()
    private var recording = false
    private var accepted = 0
    private var rejected = 0

    /** Сессия прогулки (2.1): создаётся на старте, восстанавливается после убийства процесса. */
    private var session: WalkSession? = null

    /** Текущий режим прогулки (2.2, FR-6); лимит фильтра следует за ним. */
    private var currentMode: WalkMode = WalkMode.WALKING

    /** Детектор неподвижности для автопаузы (2.4, FR-7); чистый на старте записи. */
    private var motionTracker = MotionPauseTracker()

    /** Сигналы прогулки для достижений (4.1): регионы, режимы, новые тайлы. */
    private val walkRegionIds = LinkedHashSet<String>()
    private val walkModes = LinkedHashSet<String>()
    private var walkNewTiles = 0

    /** Автопауза прочитана с настроек на старте записи (2.4). */
    private var autoPauseEnabled = true

    /** Напоминание о забытой прогулке — с настроек на старте записи (2.5). */
    private var forgottenReminderEnabled = true

    /** Батарейный страж (2.6, FR-10): пороги с настроек, чистый на старте. */
    private var batteryGuard = BatteryGuard()

    /** Троттлер прогресс-уведомлений (2.7, FR-9); интервал с настроек. */
    private var progressThrottler = NotificationThrottler()

    /** Хранилище снимка сессии (2.1) — из DI-контейнера. */
    private fun sessionStore(): WalkSessionStore = (application as WalkQuestApplication).container.walkSessionStore

    /** Поток симуляции трека (1.15, debug-сборки); null — симуляции нет. */
    private var simulationThread: Thread? = null

    /**
     * Кэш последнего детекта чужого региона (1.10): повторная проверка — только
     * когда точка ушла от предыдущей проверенной дальше ~500 м (0.005°), чтобы
     * не дёргать диск на каждый фикс GPS.
     */
    private var lastDetectLatitude = Double.NaN
    private var lastDetectLongitude = Double.NaN

    private val locationListener = LocationListener { location -> onLocation(location) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
            ACTION_SET_MODE -> setMode(intent.getStringExtra(EXTRA_MODE))
            ACTION_RESUME -> resumeFromReminder()
            ACTION_PAUSE -> pauseRecording()
            ACTION_SIMULATE -> startSimulation(
                speedFactor = intent.getFloatExtra(EXTRA_SIM_SPEED_FACTOR, 1.0f).toDouble(),
            )
        }
        return START_STICKY
    }

    /**
     * Ручная пауза с панели прогулки (DESIGN: «Пауза» — крупное частое действие):
     * та же сессионная пауза, что у автопаузы 2.4, трекер сброшен (новое окно
     * неподвижности со следующей принятой точки), статус — в уведомлении.
     */
    private fun pauseRecording() {
        val current = session ?: return
        if (current.state != WalkState.PAUSED) {
            current.pause(System.currentTimeMillis())
        }
        motionTracker.reset()
        persistSession()
        publishWalkStats()
        updateNotification()
    }

    /**
     * «Продолжить» из напоминания о забытой прогулке (2.5): сессия снимается
     * с паузы, трекер сброшен (новое окно неподвижности со следующей принятой
     * точки — человек подтвердил «я продолжаю»), напоминание снято.
     */
    private fun resumeFromReminder() {
        cancelReminderNotification()
        session?.resume(System.currentTimeMillis())
        motionTracker.reset()
        persistSession()
        publishWalkStats()
        updateNotification()
    }

    /**
     * Смена режима прогулки на лету (2.2, FR-6): лимит фильтра — эффективный
     * (override настроек ?: дефолт режима, SpeedLimitPolicy), якорь не трогаем.
     * Любой режим допустим — «самокат в пешем режиме» легален (объяснение
     * пользователю — обучение FR-12, здесь только механика).
     */
    private fun setMode(modeName: String?) {
        val mode = WalkMode.entries.firstOrNull { it.name == modeName } ?: return
        currentMode = mode
        walkModes += mode.name
        filter.updateLimit(effectiveLimitMps(mode))
        bus().walkModeChanged(mode)
        Log.d(TAG, "mode set to $mode, limitMps=${filter.limitMps}")
    }

    /** Эффективный лимит режима из настроек (override ?: дефолт, AD-4 — решает не UI). */
    private fun effectiveLimitMps(mode: WalkMode): Double {
        val override = settings().walkSpeedLimitKmh(mode)
        return SpeedLimitPolicy.effectiveLimitMps(mode, override)
    }

    private fun settings(): app.walkquest.data.settings.SettingsRepository =
        (application as WalkQuestApplication).container.settingsRepository

    /**
     * Симуляция трека (история 1.15, ТОЛЬКО debuggable-сборки — release её не
     * содержит: гейт по FLAG_DEBUGGABLE). Точки файла `files/qa/sim-track.track`
     * (пушится `_qa/wq.py simulate`) идут через тот же [onLocation], что живые
     * фиксы LocationManager: журнал → фильтр → OpenTileAtPoint → шина. LocationManager
     * при этом не дёргается: accuracy/провайдер/темп — из фикстуры, детерминированно
     * (сценарии «грубый фикс с вышек», «accuracy > 50 м», «NETWORK в помещении»).
     * Разрешение геолокации не требуется (живых провайдеров нет), но FGS типа
     * location его требует платформенно — `wq.py simulate` выдаёт заранее.
     */
    private fun startSimulation(speedFactor: Double) {
        if (recording) return
        val debuggable = applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) {
            stopSelf()
            return
        }
        val simFile = File(filesDir, SIM_FILE_PATH)
        val result = runCatching { TrackSimulation.parse(simFile.readText(), speedFactor) }
            .getOrNull()
        if (result !is TrackSimulation.SimResult.Ok) {
            Log.d(TAG, "simulation not started: ${(result as? TrackSimulation.SimResult.Invalid)?.detail}")
            stopSelf()
            return
        }
        // Тот же стартовый блок, что у живой записи: сессия/журнал, фильтр, шина, уведомление.
        createNotificationChannel()
        beginRecordingState()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        recording = true
        Log.d(TAG, "simulation started: ${result.steps.size} points, speed x$speedFactor")
        bus().recordingStarted()
        val thread = Thread {
            for (step in result.steps) {
                if (Thread.currentThread().isInterrupted) return@Thread
                if (step.delayBeforeMillis > 0) {
                    try {
                        Thread.sleep(step.delayBeforeMillis)
                    } catch (e: InterruptedException) {
                        return@Thread
                    }
                }
                onLocation(simulatedLocation(step))
            }
            // Файл доигран — останавливаемся как обычная запись (шина/уведомление/журнал).
            stopRecording()
        }
        thread.name = "walkquest-simulation"
        thread.start()
        simulationThread = thread
    }

    /** Synthetic-фикс шага симуляции: время — «сейчас» (проверки свежести маркера). */
    private fun simulatedLocation(step: TrackSimulation.SimStep): Location =
        Location(step.provider).apply {
            latitude = step.latitude
            longitude = step.longitude
            step.accuracyMeters?.let { accuracy = it }
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = System.nanoTime()
        }

    // --- Сессия прогулки (2.1): жизненный цикл, восстановление, агрегаты ---

    /** Снимок сессии + её журнал + восстановленный фильтр (результат recovery). */
    private data class RecoveredRecording(
        val session: WalkSession,
        val journal: TrackJournal,
        val filter: PointFilter,
    )

    /**
     * Стартовый блок записи (2.1): новая сессия или восстановление незавершённой
     * после убийства процесса (AD-5). Оба пути (живые фиксы и симуляция) проходят
     * здесь — сессия ведёт себя одинаково в debug-прогонах и в поле.
     */
    private fun beginRecordingState() {
        accepted = 0
        rejected = 0
        markerPicker = MarkerFixPicker()
        motionTracker = MotionPauseTracker()
        autoPauseEnabled = settings().autoPauseEnabled()
        forgottenReminderEnabled = settings().forgottenWalkReminderEnabled()
        batteryGuard = BatteryGuard(
            warningPercent = settings().batteryWarningPercent(),
            finishPercent = settings().batteryFinishPercent(),
        )
        progressThrottler = NotificationThrottler(
            intervalMillis = settings().progressNotificationIntervalMinutes() * 60_000L,
        )
        walkRegionIds.clear()
        walkModes.clear()
        walkNewTiles = 0
        app.walkquest.domain.achievements.AchievementEvents.clear()
        walkModes += currentMode.name
        checkBattery()
        lastDetectLatitude = Double.NaN
        lastDetectLongitude = Double.NaN
        val recovered = recoverUnfinishedSession()
        if (recovered != null) {
            session = recovered.session
            journal = recovered.journal
            filter = recovered.filter
        } else {
            val now = System.currentTimeMillis()
            session = WalkSession(startedAtEpochMillis = now)
            currentMode = settings().walkMode()
            filter = PointFilter(
                limitMps = effectiveLimitMps(currentMode),
                sessionStartEpochMillis = now,
            )
            journal = TrackJournal(File(filesDir, "tracks/track-$now$JOURNAL_EXTENSION"))
        }
        persistSession()
        publishWalkStats()
        bus().walkModeChanged(currentMode)
    }

    /**
     * Восстановление после убийства процесса (2.1, AD-5): незавершённый снимок
     * сессии (ACTIVE/PAUSED) + журнал `track-<sessionId>.track` → посмертный replay
     * фильтра (startedAt из заголовка журнала, как TrackHistory) → принятые точки
     * заново засчитываются в агрегаты сессии: якорь фильтра, дистанция и счётчики
     * восстановлены детерминированно, запись продолжается в ТОТ ЖЕ журнал (append).
     * Журнал не читается или отсутствует — восстановления нет: начнётся новая
     * сессия, её первый save перезапишет бесполезный снимок.
     */
    private fun recoverUnfinishedSession(): RecoveredRecording? {
        val read = sessionStore().load()
        if (read !is WalkSessionRead.Ok) return null
        val snapshot = read.snapshot
        if (snapshot.state == WalkState.FINISHED) return null
        val file = File(filesDir, "tracks/track-${snapshot.sessionId}$JOURNAL_EXTENSION")
        val journalRead = TrackJournal(file).read()
        if (journalRead !is JournalRead.Ok) return null
        // Агрегаты пересчитываем replay'ем журнала с нуля: журнал — источник правды
        // (AD-5), а агрегаты снимка уже учтены в его точках — перенос снимка
        // удвоил бы дистанцию. Состояние после восстановления — ACTIVE: запись
        // продолжается (старт по действию пользователя; паузы приходят в 2.4).
        val restored = WalkSession(startedAtEpochMillis = snapshot.startedAtEpochMillis)
        // Replay с лимитом текущего сохранённого режима. Ограничение (deferred-work 2.2):
        // смены режима ВНУТРИ убитой прогулки в журнал не пишутся — replay повторяет
        // вердикты для одного лимита; мульти-режимные записи точатся с Room-историей.
        val restoredFilter = PointFilter(
            limitMps = effectiveLimitMps(settings().walkMode()),
            sessionStartEpochMillis = journalRead.startedAtEpochMillis,
        )
        journalRead.points.forEach { point ->
            if (restoredFilter.check(point) is FilterVerdict.Accepted) {
                restored.acceptPoint(point)
            }
        }
        return RecoveredRecording(restored, TrackJournal(file), restoredFilter)
    }

    /** Снимок сессии на диск (атомарно, WalkSessionStore) — после каждого изменения. */
    private fun persistSession() {
        val current = session ?: return
        sessionStore().save(current.snapshot())
    }

    /** Запись завершённой прогулки в историю (3.1/4.1, FR-13): append-only, AD-5. */
    private fun appendToWalkHistory(finished: WalkSession, finishedAt: Long) {
        val result = (application as WalkQuestApplication).container.walkHistoryStore.append(
            WalkRecord(
                sessionId = finished.sessionId,
                startedAtEpochMillis = finished.startedAtEpochMillis,
                finishedAtEpochMillis = finishedAt,
                distanceMeters = finished.distanceMeters,
                activeMillis = finished.activeMillis(finishedAt),
                acceptedCount = finished.acceptedCount,
                regionIds = walkRegionIds.sorted(),
                modes = walkModes.sorted(),
                newTilesOpened = walkNewTiles,
            ),
        )
        if (result !is HistoryAppendResult.Ok) {
            // История не пишется — не роняем остановку (сессия уже финализирована
            // и есть снимок), но фиксируем: без истории серии/статистика молчат.
            Log.d(TAG, "walk history append failed: ${(result as HistoryAppendResult.Error).detail}")
        }
    }

    /** Агрегаты сессии → шина (2.1): UI рендерит готовые значения, не вычисляет (AD-4). */
    private fun publishWalkStats() {
        val current = session ?: return
        bus().walkStatsUpdated(
            state = current.state,
            distanceMeters = current.distanceMeters,
            activeMillis = current.activeMillis(System.currentTimeMillis()),
        )
    }

    /** Идемпотентный старт: повторный ACTION_START при живой записи игнорируется. */
    private fun startRecording() {
        if (recording) return
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasFine) {
            stopSelf()
            return
        }
        createNotificationChannel()
        beginRecordingState()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        val thread = HandlerThread("walkquest-tracking").apply { start() }
        worker = thread
        val manager = getSystemService(LocationManager::class.java)
        try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_INTERVAL_MS,
                MIN_DISTANCE_METERS,
                locationListener,
                thread.looper,
            )
        } catch (e: SecurityException) {
            thread.quitSafely()
            worker = null
            stopSelf()
            return
        }
        // Полевой тест 3 (2026-10-05): в помещении GPS молчит — маркер «я» пропадал
        // на время записи («Определяю местоположение…»), хотя без записи точка была
        // (сетевой lastKnown). Просим и NETWORK: его фиксы питают МАРКЕР (свежайший
        // фикс любого провайдера, MarkerFixPicker); открытие тайлов отсекает
        // PointFilter (accuracy ≤ 50 м) — сетевой фикс с честной большой accuracy
        // туман не откроет. Провайдера может не быть на устройстве — живём на GPS.
        try {
            manager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                NETWORK_MIN_INTERVAL_MS,
                NETWORK_MIN_DISTANCE_METERS,
                locationListener,
                thread.looper,
            )
        } catch (e: SecurityException) {
            Log.d(TAG, "network provider unavailable (permission), gps only")
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, "network provider missing on device, gps only")
        }
        recording = true
        Log.d(TAG, "recording started (gps+network, stale-fix guard on)")
        bus().recordingStarted()
    }

    private fun onLocation(location: Location) {
        // Маркер «я» на карте кормится СВЕЖАЙШИМ фиксом любого провайдера (полевой
        // тест 3: GPS+NETWORK, MarkerFixPicker не даёт запоздалому сетевому фиксу
        // перетереть свежий GPS) — до фильтра точек: где пользователь и что
        // засчитано треку — разные вопросы (фильтр молча отсекает транспорт и
        // грубые сетевые фиксы).
        val fixTime = if (location.time > 0L) location.time else System.currentTimeMillis()
        val accuracy = if (location.hasAccuracy()) location.accuracy else null
        // Возраст фикса в момент прихода — диагностика призраков lastKnown
        // (полевой баг 2026-10-08) без координат в логе (AD-3).
        val fixAgeMs = System.currentTimeMillis() - fixTime
        if (markerPicker.acceptIfNewer(accuracy, fixTime)) {
            bus().locationUpdated(
                location.latitude,
                location.longitude,
                accuracy,
                fixTime,
                location.provider,
            )
        }
        val point = TrackPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            epochMillis = fixTime,
            accuracyMeters = accuracy,
        )
        // AD-5: точка на диске ДО обработки — убийство процесса теряет секунды, не трек.
        val appended = journal?.append(point)
        if (appended !is JournalAppendResult.Ok) {
            // Журнал не пишется — запись бессмысленна (AD-5 не выполняется), останавливаемся.
            stopRecording()
            return
        }
        when (val verdict = filter.check(point)) {
            is FilterVerdict.Accepted -> {
                accepted += 1
                // Агрегаты прогулки — только по принятой точке (2.1, FR-6/AD-4).
                session?.acceptPoint(point)
                // Автопауза (2.4, FR-7) и напоминание о забытой прогулке (2.5):
                // детектор по принятым точкам; сессия встаёт на паузу/снимается
                // по сигналам, настройки — со старта записи.
                if (autoPauseEnabled) {
                    when (motionTracker.onAcceptedPoint(point)) {
                        MotionSignal.PauseDue -> {
                            session?.pause(point.epochMillis)
                            persistSession()
                        }
                        MotionSignal.Resumed -> {
                            cancelReminderNotification()
                            session?.resume(point.epochMillis)
                            persistSession()
                        }
                        MotionSignal.ReminderDue -> {
                            if (forgottenReminderEnabled) showReminderNotification()
                        }
                        MotionSignal.None -> Unit
                    }
                }
                // Батарейный страж (2.6, FR-10) на каждой принятой точке.
                checkBattery()
                // Агрегаты публикуем один раз на точку (sweep 2.10: было по разу
                // в каждой ветке перехода + здесь).
                publishWalkStats()
                notifyProgressThrottled()
                Log.d(
                    TAG,
                    "point accepted #$accepted provider=${location.provider} " +
                        "acc=${accuracy ?: "?"} ageMs=$fixAgeMs",
                )
                val opened = (application as WalkQuestApplication).container.openTileAtPoint
                    .openAt(point.latitude, point.longitude)
                // 4.1: сигналы для достижений (регионы, новые тайлы).
                when (opened) {
                    is OpenResult.Changed -> {
                        opened.regionId?.let { walkRegionIds += it }
                        walkNewTiles += 1
                    }
                    is OpenResult.Unchanged -> opened.regionId?.let { walkRegionIds += it }
                    else -> Unit
                }
                bus().pointAccepted(
                    fogChanged = opened is OpenResult.Changed,
                    latitude = point.latitude,
                    longitude = point.longitude,
                )
                // Точка вне области установленных пакетов не должна «молчать»
                // (разбор владельца 2026-10-05): шина получает флаг «вне покрытия»,
                // UI показывает статус «Вы вне области пакета», пока запись идёт мимо.
                bus().coverageUpdated(outside = opened is OpenResult.OutOfRegion)
                // AD-9/Flow 4: точка вне установленных регионов → детект чужого региона
                // по манифест-кэшу; внутри — подсказка снимается. Не чаще, чем раз в ~500 м.
                val moved = lastDetectLatitude.isNaN() ||
                    kotlin.math.abs(point.latitude - lastDetectLatitude) > DETECT_MIN_SHIFT_DEG ||
                    kotlin.math.abs(point.longitude - lastDetectLongitude) > DETECT_MIN_SHIFT_DEG
                if (opened is OpenResult.OutOfRegion && moved) {
                    lastDetectLatitude = point.latitude
                    lastDetectLongitude = point.longitude
                    val suggestion = (application as WalkQuestApplication).container.detectForeignRegion
                        .check(point.latitude, point.longitude)
                    bus().foreignRegionChecked(suggestion?.regionId)
                } else if (opened !is OpenResult.OutOfRegion) {
                    lastDetectLatitude = point.latitude
                    lastDetectLongitude = point.longitude
                    bus().foreignRegionChecked(null)
                }
            }
            is FilterVerdict.Rejected -> {
                rejected += 1
                // Отбраковка — в лог (полевой тест 2, дефект 4: из UI «отсеяно» убрано).
                // Координат в вердикте нет — AD-3 соблюдён (только метрики отсечения).
                Log.d(
                    TAG,
                    "point rejected: $verdict provider=${location.provider} " +
                        "acc=${accuracy ?: "?"} ageMs=$fixAgeMs",
                )
                // Имя вердикта — в шину для debug-оверлея (1.14); координат нет (AD-3).
                bus().pointRejected(verdict.javaClass.simpleName)
            }
        }
        notifyProgressThrottled()
    }

    /**
     * Прогресс-уведомление через троттлер (2.7, FR-9): не чаще интервала
     * из настроек. Смены состояния (пауза/возобновление) идут напрямую —
     * они не периодические.
     */
    private fun notifyProgressThrottled() {
        if (progressThrottler.shouldNotify(System.currentTimeMillis())) {
            updateNotification()
        }
    }

    /** Идемпотентный стоп: без живой записи — no-op. */
    private fun stopRecording() {
        if (!recording) {
            stopSelf()
            return
        }
        recording = false
        // Симуляция (1.15): обрываем поток проигрывания, если он ещё жив.
        simulationThread?.interrupt()
        simulationThread = null
        try {
            getSystemService(LocationManager::class.java).removeUpdates(locationListener)
        } catch (e: SecurityException) {
            // Разрешение отозвано в настройках — обновлений и так нет.
        }
        worker?.quitSafely()
        worker = null
        journal = null
        cancelReminderNotification()
        // 2.1: сессия завершена (итоговые агрегаты — в шине и снимке на диске);
        // 3.1: запись уходит в историю (FR-13, источник истины — устройство).
        val finished = session
        if (finished != null) {
            val finishedAt = System.currentTimeMillis()
            finished.finish(finishedAt)
            appendToWalkHistory(finished, finishedAt)
            // 4.6: проверка достижений после завершённой прогулки (FR-15).
            runCatching { (application as WalkQuestApplication).container.runAchievementCheck() }
            // Эпик 5: отправка агрегатов лидерборда (outbox + попытка; FR-19).
            runCatching {
                val container = (application as WalkQuestApplication).container
                val nickname = container.settingsRepository.leaderboardNickname()
                val api = container.leaderboardApi
                if (nickname != null && container.settingsRepository.leaderboardVisible() && api != null) {
                    val fractions = container.regionPackageRepository.installedPackages().mapNotNull { pkg ->
                        when (val r = container.computeRegionProgress.compute(pkg.regionId)) {
                            is app.walkquest.domain.ProgressResult.Ok -> pkg.regionId to r.progress.fraction
                            else -> null
                        }
                    }.toMap()
                    val snapshot = app.walkquest.domain.leaderboard.LeaderboardSnapshot(
                        nickname,
                        app.walkquest.data.leaderboard.regionAggregates(container.walkHistory, fractions),
                    )
                    container.leaderboardOutbox.enqueue(snapshot)
                    if (api.publishAggregates(snapshot)) container.leaderboardOutbox.clear()
                }
            }
        }
        persistSession()
        publishWalkStats()
        Log.d(TAG, "recording stopped: accepted=$accepted rejected=$rejected")
        bus().recordingStopped()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (recording) stopRecording()
        super.onDestroy()
    }

    private fun bus(): TrackingStateBus = (application as WalkQuestApplication).container.trackingStateBus

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Запись прогулок",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Постоянное уведомление записи (требование платформы); координат нет (AD-3). */
    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        // 2.4: пауза автоматическая — она видна и в уведомлении (FR-7 «кофейня»).
        val paused = session?.state == WalkState.PAUSED
        val status = if (paused) "Прогулка на паузе" else "Идёт запись прогулки"
        // 2.7 (FR-9): прогресс в тексте — агрегаты сессии (AD-4: форматируем
        // готовые значения, не вычисляем).
        val current = session
        val progress = buildString {
            append("Открыто клеток: $accepted")
            if (current != null) {
                if (current.distanceMeters >= 50.0) {
                    append(" · %.1f км".format(current.distanceMeters / 1000.0))
                }
                val minutes = current.activeMillis(System.currentTimeMillis()) / 60_000L
                if (minutes >= 1L) append(" · $minutes мин")
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("WalkQuest: $status")
            .setContentText(progress)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    // --- Напоминание о забытой прогулке (2.5, FR-7) ---

    /**
     * Уведомление «Сделали перерыв или забыли остановить прогулку?» с действиями
     * «Продолжить» ([ACTION_RESUME]) и «Завершить» ([ACTION_STOP]). Тихий канал
     * (FR-9) — музыку не перебивает. Повторный ReminderDue не приходит (трекер
     * однократен), дубль notify безопасен.
     */
    private fun showReminderNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        ensureReminderChannel(manager)
        val resumeIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, TrackingService::class.java).setAction(ACTION_RESUME),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Сделали перерыв или забыли остановить прогулку?")
            .setContentText("Прогулка на паузе уже давно.")
            .addAction(0, "Продолжить", resumeIntent)
            .addAction(0, "Завершить", stopIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(REMINDER_NOTIFICATION_ID, notification)
    }

    private fun cancelReminderNotification() {
        getSystemService(NotificationManager::class.java).cancel(REMINDER_NOTIFICATION_ID)
    }

    /** Тихий канал «Прогулки» (FR-9) для напоминаний и предупреждений; идемпотентно. */
    private fun ensureReminderChannel(manager: NotificationManager) {
        manager.createNotificationChannel(
            NotificationChannel(REMINDER_CHANNEL_ID, "Прогулки", NotificationManager.IMPORTANCE_LOW),
        )
    }

    // --- Батарейный страж (2.6, FR-10) ---

    /**
     * Уровень заряда (BatteryManager, дёшево — без ресивера) → гвард → сигналы:
     * предупреждение — тихое уведомление с текстом PRD; автозавершение —
     * штатный stopRecording (сессия финализируется с сохранением всех данных,
     * финальный экран — история 2.8).
     */
    private fun checkBattery() {
        val level = getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: return
        when (batteryGuard.onBatteryLevel(level)) {
            BatterySignal.WarningDue -> showBatteryWarningNotification()
            BatterySignal.FinishDue -> stopRecording()
            BatterySignal.None -> Unit
        }
    }

    private fun showBatteryWarningNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        ensureReminderChannel(manager)
        val finish = settings().batteryFinishPercent()
        val notification = NotificationCompat.Builder(this, REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Смартфон скоро разрядится")
            .setContentText(
                "Прогулка завершится автоматически при $finish%, " +
                    "чтобы вы могли комфортно добраться домой.",
            )
            .setAutoCancel(true)
            .build()
        manager.notify(BATTERY_NOTIFICATION_ID, notification)
    }

    companion object {
        const val ACTION_START = "app.walkquest.tracking.action.START"
        const val ACTION_STOP = "app.walkquest.tracking.action.STOP"

        /** Симуляция трека (1.15): только debuggable-сборки, файл SIM_FILE_PATH. */
        const val ACTION_SIMULATE = "app.walkquest.tracking.action.SIMULATE"
        const val EXTRA_SIM_SPEED_FACTOR = "speedFactor"

        /** Смена режима прогулки (2.2, FR-6): extra [EXTRA_MODE] — имя WalkMode. */
        const val ACTION_SET_MODE = "app.walkquest.tracking.action.SET_MODE"
        const val EXTRA_MODE = "mode"

        /** «Продолжить» из напоминания о забытой прогулке (2.5). */
        const val ACTION_RESUME = "app.walkquest.tracking.action.RESUME"

        /** Ручная пауза с панели прогулки (DESIGN: «Пауза» — крупное частое действие). */
        const val ACTION_PAUSE = "app.walkquest.tracking.action.PAUSE"
        private const val SIM_FILE_PATH = "qa/sim-track.track"

        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1001
        private const val REMINDER_CHANNEL_ID = "walk-reminders"
        private const val REMINDER_NOTIFICATION_ID = 1002
        private const val BATTERY_NOTIFICATION_ID = 1003
        private const val JOURNAL_EXTENSION = ".track"
        private const val TAG = "TrackingService"

        /** Прототип: фикс раз в секунду, от 1 метра — частота полируется в epic-walk-tracking. */
        private const val MIN_INTERVAL_MS = 1_000L
        private const val MIN_DISTANCE_METERS = 1f

        /** Сетевой провайдер (полевой тест 3): реже GPS — маркеру хватает, батарею бережём. */
        private const val NETWORK_MIN_INTERVAL_MS = 2_000L
        private const val NETWORK_MIN_DISTANCE_METERS = 1f

        /** Минимальный сдвиг точки для повторного детекта чужого региона (~500 м). */
        private const val DETECT_MIN_SHIFT_DEG = 0.005
    }
}
