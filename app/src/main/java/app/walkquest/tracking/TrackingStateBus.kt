package app.walkquest.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Мост состояния записи: сервис (android) → viewmodel (UDF).
 * Сервис публикует снимки, viewmodel собирает [snapshot] — сервис и UI
 * друг о друге не знают. Чистый Kotlin (coroutines — JVM), без android.* (AD-10).
 */
class TrackingStateBus {

    private val _snapshot = MutableStateFlow(TrackingSnapshot())
    val snapshot: StateFlow<TrackingSnapshot> = _snapshot.asStateFlow()

    /** Новая запись: счётчики, эпоха тумана и подсказка чужого региона обнуляются. */
    fun recordingStarted() {
        _snapshot.value = TrackingSnapshot(isRecording = true)
    }

    /**
     * Принятая фильтром точка; [fogChanged] — domain открыл новый тайл.
     * [latitude]/[longitude] — координаты самой принятой точки (2026-10-06, рендер
     * «свечи»: лента строится по принятым точкам, а не по маркерному фиксу —
     * маркер может идти от сетевого провайдера и не совпадать с засчитанной точкой).
     */
    fun pointAccepted(fogChanged: Boolean, latitude: Double? = null, longitude: Double? = null) {
        _snapshot.update {
            it.copy(
                acceptedCount = it.acceptedCount + 1,
                fogEpoch = if (fogChanged) it.fogEpoch + 1 else it.fogEpoch,
                lastAcceptedLatitude = latitude ?: it.lastAcceptedLatitude,
                lastAcceptedLongitude = longitude ?: it.lastAcceptedLongitude,
            )
        }
    }

    /** Точка отвергнута фильтром (скорость/телепортация/время). */
    fun pointRejected(verdict: String? = null) {
        _snapshot.update {
            it.copy(rejectedCount = it.rejectedCount + 1, lastRejectedVerdict = verdict ?: it.lastRejectedVerdict)
        }
    }

    /**
     * Итог детекта чужого региона (1.10): точка не покрыта установленными пакетами,
     * и манифест-кэш знает регион для неё. null — точка дома или регион неизвестен:
     * подсказки в UI нет.
     */
    fun foreignRegionChecked(suggestedRegionId: String?) {
        _snapshot.update { it.copy(suggestedRegionId = suggestedRegionId) }
    }

    /** Запись остановлена; счётчики сохраняются до следующего старта. */
    fun recordingStopped() {
        _snapshot.update { it.copy(isRecording = false, outsideCoverage = false) }
    }

    /**
     * Агрегаты сессии прогулки (2.1): состояние, дистанция по принятым точкам и
     * чистое время без пауз — готовые значения из WalkSession (AD-4: UI не
     * вычисляет, только форматирует). Публикуется сервисом на старте, каждой
     * принятой точке, переходах и завершении.
     */
    fun walkStatsUpdated(state: WalkState, distanceMeters: Double, activeMillis: Long) {
        _snapshot.update {
            it.copy(
                walkState = state,
                walkDistanceMeters = distanceMeters,
                walkActiveMillis = activeMillis,
            )
        }
    }

    /** Активный режим прогулки (2.2, FR-6): старт записи или ACTION_SET_MODE. */
    fun walkModeChanged(mode: WalkMode) {
        _snapshot.update { it.copy(walkMode = mode) }
    }

    /**
     * Принятая точка вне области всех установленных пакетов ([outside] = true) или
     * внутри (false) — статус «Вы вне области пакета» в UI (разбор 2026-10-05:
     * OutOfRegion не должна «молчать»). Обновляется на каждой принятой точке.
     */
    fun coverageUpdated(outside: Boolean) {
        _snapshot.update { it.copy(outsideCoverage = outside) }
    }

    /**
     * Свежая позиция устройства во время записи — для маркера «я» на карте.
     * Публикуется сервисом по свежайшему фиксу любого провайдера (GPS+NETWORK,
     * полевой тест 3, выбор — MarkerFixPicker) до фильтра точек: маркер показывает,
     * где пользователь, даже если точка не засчитана треку (лимит скорости, грубая
     * сетевая accuracy и т.п.).
     * [fixEpochMillis] — время фикса (Location.time): UI по нему отсекает устаревшие
     * позиции (дефект 3 полевого теста 2: старый lastKnownLocation маркером не показываем).
     */
    fun locationUpdated(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float?,
        fixEpochMillis: Long,
        provider: String? = null,
    ) {
        _snapshot.update {
            it.copy(
                lastLatitude = latitude,
                lastLongitude = longitude,
                lastAccuracyMeters = accuracyMeters,
                lastFixEpochMillis = fixEpochMillis,
                lastFixProvider = provider ?: it.lastFixProvider,
            )
        }
    }
}

/** Моментальный снимок состояния записи для UI. */
data class TrackingSnapshot(
    val isRecording: Boolean = false,
    val acceptedCount: Int = 0,
    val rejectedCount: Int = 0,
    /** Счётчик изменений биткарты от записи — триггер перечтения openedIndices во viewmodel. */
    val fogEpoch: Int = 0,
    /**
     * Регион, который domain предлагает загрузить для текущей локации (1.10, FR-4);
     * null — локация покрыта установленными пакетами или неизвестна манифесту.
     */
    val suggestedRegionId: String? = null,
    /** Последняя позиция устройства от записи (маркер «я»); null — фикса ещё не было. */
    val lastLatitude: Double? = null,
    val lastLongitude: Double? = null,
    /** Заявленная точность последнего фикса, метры; null — провайдер её не дал. */
    val lastAccuracyMeters: Float? = null,
    /** Время последнего фикса (Location.time, мс UTC) — отсечение устаревших позиций. */
    val lastFixEpochMillis: Long? = null,
    /**
     * Провайдер последнего маркерного фикса («gps»/«network») — debug-оверлей
     * (история 1.14): полевая диагностика «маркер живёт от сети, GPS молчит».
     */
    val lastFixProvider: String? = null,
    /**
     * Имя последнего вердикта отбраковки PointFilter («TooFast», «Inaccurate»…) —
     * debug-оверлей (1.14). Координат нет — AD-3 соблюдён (только метрики).
     */
    val lastRejectedVerdict: String? = null,
    /**
     * Координаты последней ПРИНЯТОЙ фильтром точки записи (2026-10-06, «свеча»):
     * viewmodel добавляет по ним круги ленты раскрытия. Отдельно от lastLatitude —
     * маркерный фикс может быть от другого провайдера и не засчитан треку.
     */
    val lastAcceptedLatitude: Double? = null,
    val lastAcceptedLongitude: Double? = null,
    /**
     * Последняя принятая точка вне области всех установленных пакетов (разбор
     * 2026-10-05): UI показывает статус «вне области пакета», пока флаг стоит.
     */
    val outsideCoverage: Boolean = false,
    /**
     * Состояние сессии прогулки (2.1, из WalkSession через шину): null — записи
     * в этой сессии приложения ещё не было. FINISHED — последняя прогулка
     * завершена (итог остаётся до следующего старта).
     */
    val walkState: WalkState? = null,
    /** Дистанция прогулки по принятым точкам, метры (2.1, AD-4 — готовое значение). */
    val walkDistanceMeters: Double = 0.0,
    /** Чистое (активное) время прогулки без пауз, мс (2.1). */
    val walkActiveMillis: Long = 0L,
    /** Активный режим прогулки (2.2, FR-6); null — записи ещё не было. */
    val walkMode: WalkMode? = null,
)
