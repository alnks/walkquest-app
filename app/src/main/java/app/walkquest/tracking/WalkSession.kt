package app.walkquest.tracking

/**
 * Сессия прогулки — полный жизненный цикл (история 2.1, FR-5, AD-5).
 *
 * Чистый Kotlin без android.* (AD-10): JVM-тестируемо. Это domain-логика записи:
 * сервис только вызывает переходы по вердиктам/действиям, UI — только рендерит
 * агрегаты (AD-4): дистанция считается здесь, форматируется на UI.
 *
 * Модель честных точек: [acceptPoint] вызывается сервисом ТОЛЬКО для точек,
 * принятых PointFilter (вердикт Accepted) — расстояние и счётчики идут только
 * по ним (FR-6: участок быстрее лимита в дистанцию не попадает, потому что
 * отсеянный фильтром сюда не доходит). Сырая правда всех точек живёт в журнале
 * (AD-5), а не здесь.
 *
 * Сессия 1:1 к журналу `track-<sessionId>.track` (sessionId = epochMillis старта,
 * конвенция TrackingService/TrackHistory): восстановление после убийства процесса
 * находит журнал по id, не гадая (WalkSessionStore + replay, AD-5).
 */
class WalkSession private constructor(
    /** Идентификатор = время старта, epoch millis UTC (= имя файла журнала). */
    val sessionId: Long,
    /** Время старта сессии, epoch millis UTC. */
    val startedAtEpochMillis: Long,
    state: WalkState,
    distanceMeters: Double,
    acceptedCount: Int,
    lastAcceptedLatitude: Double?,
    lastAcceptedLongitude: Double?,
    pauses: List<PauseInterval>,
    currentPauseStartedAt: Long?,
    finishedAtEpochMillis: Long?,
) {

    /** Текущее состояние сессии; меняется только через переходы ниже. */
    var state: WalkState = state
        private set

    /** Накопленное расстояние по принятым точкам, метры. */
    var distanceMeters: Double = distanceMeters
        private set

    /** Число принятых фильтром точек этой прогулки. */
    var acceptedCount: Int = acceptedCount
        private set

    private var lastAcceptedLatitude = lastAcceptedLatitude
    private var lastAcceptedLongitude = lastAcceptedLongitude
    private val pauses = pauses.toMutableList()
    private var currentPauseStartedAt = currentPauseStartedAt
    private var finishedAtEpochMillis = finishedAtEpochMillis

    /** Новая активная сессия, стартующая «сейчас». */
    constructor(startedAtEpochMillis: Long = System.currentTimeMillis()) : this(
        sessionId = startedAtEpochMillis,
        startedAtEpochMillis = startedAtEpochMillis,
        state = WalkState.ACTIVE,
        distanceMeters = 0.0,
        acceptedCount = 0,
        lastAcceptedLatitude = null,
        lastAcceptedLongitude = null,
        pauses = emptyList(),
        currentPauseStartedAt = null,
        finishedAtEpochMillis = null,
    )

    /**
     * Учёт принятой фильтром точки: дистанция — haversine от предыдущей принятой
     * (первая точка дистанции не даёт, только счётчик). Только в ACTIVE: в паузе
     * запись координат приостановлена (FR-7), точка не засчитывается; FINISHED —
     * терминальное.
     */
    fun acceptPoint(point: TrackPoint): AcceptResult {
        if (state == WalkState.FINISHED) return AcceptResult.Rejected.SessionFinished
        if (state == WalkState.PAUSED) return AcceptResult.Rejected.SessionPaused
        val lastLat = lastAcceptedLatitude
        val lastLon = lastAcceptedLongitude
        if (lastLat != null && lastLon != null) {
            distanceMeters += haversineMeters(
                TrackPoint(lastLat, lastLon, point.epochMillis),
                point,
            )
        }
        lastAcceptedLatitude = point.latitude
        lastAcceptedLongitude = point.longitude
        acceptedCount += 1
        return AcceptResult.Ok
    }

    /** ACTIVE → PAUSED; [now] — время паузы (epoch millis). */
    fun pause(now: Long): PauseResult {
        if (state == WalkState.FINISHED) return PauseResult.Invalid("сессия завершена")
        if (state == WalkState.PAUSED) return PauseResult.Invalid("сессия уже на паузе")
        state = WalkState.PAUSED
        currentPauseStartedAt = now
        return PauseResult.Ok
    }

    /** PAUSED → ACTIVE; интервал паузы фиксируется в списке. */
    fun resume(now: Long): ResumeResult {
        if (state != WalkState.PAUSED) return ResumeResult.Invalid("сессия не на паузе")
        val pauseStart = currentPauseStartedAt
            ?: return ResumeResult.Invalid("нет открытой паузы")
        if (now < pauseStart) return ResumeResult.Invalid("время возобновления раньше начала паузы")
        pauses += PauseInterval(pauseStart, now)
        currentPauseStartedAt = null
        state = WalkState.ACTIVE
        return ResumeResult.Ok
    }

    /**
     * Завершение (ACTIVE или PAUSED): открытую паузу закрывает как
     * [finishedAtEpochMillis] − start (прогулка закончилась в момент финиша),
     * дальнейшие переходы запрещены.
     */
    fun finish(now: Long): FinishResult {
        if (state == WalkState.FINISHED) return FinishResult.Invalid("сессия уже завершена")
        if (now < startedAtEpochMillis) return FinishResult.Invalid("финиш раньше старта")
        val pauseStart = currentPauseStartedAt
        if (state == WalkState.PAUSED && pauseStart != null) {
            pauses += PauseInterval(pauseStart, now)
            currentPauseStartedAt = null
        }
        state = WalkState.FINISHED
        finishedAtEpochMillis = now
        return FinishResult.Ok
    }

    /**
     * Чистое (активное) время прогулки на момент [now], мс: от старта минус все
     * паузы, включая открытую (считается до now). FINISHED — до момента финиша.
     */
    fun activeMillis(now: Long): Long {
        val end = finishedAtEpochMillis ?: now
        var total = (end - startedAtEpochMillis).coerceAtLeast(0L)
        for (pause in pauses) {
            total -= (pause.endedAtEpochMillis - pause.startedAtEpochMillis).coerceAtLeast(0L)
        }
        // Открытая пауза (сессия на паузе сейчас) тоже не считается активным временем.
        val openStart = currentPauseStartedAt
        if (openStart != null) {
            total -= (end - openStart).coerceAtLeast(0L)
        }
        return total.coerceAtLeast(0L)
    }

    /** Паузы сессии (закрытые интервалы; открытой здесь нет). */
    fun pauseIntervals(): List<PauseInterval> = pauses.toList()

    /** Время финиша или null, если сессия не завершена. */
    fun finishedAtEpochMillis(): Long? = finishedAtEpochMillis

    /** Снимок для персистентности (WalkSessionStore). */
    fun snapshot(): WalkSessionSnapshot = WalkSessionSnapshot(
        sessionId = sessionId,
        startedAtEpochMillis = startedAtEpochMillis,
        state = state,
        distanceMeters = distanceMeters,
        acceptedCount = acceptedCount,
        lastAcceptedLatitude = lastAcceptedLatitude,
        lastAcceptedLongitude = lastAcceptedLongitude,
        pauses = pauses.toList(),
        currentPauseStartedAt = currentPauseStartedAt,
        finishedAtEpochMillis = finishedAtEpochMillis,
    )

    companion object {
        /** Восстановление сессии из снимка (WalkSessionStore после убийства процесса). */
        fun restore(snapshot: WalkSessionSnapshot): WalkSession = WalkSession(
            sessionId = snapshot.sessionId,
            startedAtEpochMillis = snapshot.startedAtEpochMillis,
            state = snapshot.state,
            distanceMeters = snapshot.distanceMeters,
            acceptedCount = snapshot.acceptedCount,
            lastAcceptedLatitude = snapshot.lastAcceptedLatitude,
            lastAcceptedLongitude = snapshot.lastAcceptedLongitude,
            pauses = snapshot.pauses,
            currentPauseStartedAt = snapshot.currentPauseStartedAt,
            finishedAtEpochMillis = snapshot.finishedAtEpochMillis,
        )
    }
}

/** Состояния сессии прогулки (2.1). */
enum class WalkState { ACTIVE, PAUSED, FINISHED }

/** Закрытый интервал паузы, epoch millis UTC. */
data class PauseInterval(val startedAtEpochMillis: Long, val endedAtEpochMillis: Long)

/** Персистентный снимок сессии (формат WalkSessionStore). */
data class WalkSessionSnapshot(
    val sessionId: Long,
    val startedAtEpochMillis: Long,
    val state: WalkState,
    val distanceMeters: Double,
    val acceptedCount: Int,
    val lastAcceptedLatitude: Double?,
    val lastAcceptedLongitude: Double?,
    val pauses: List<PauseInterval>,
    val currentPauseStartedAt: Long?,
    val finishedAtEpochMillis: Long?,
)

/** Результат учёта принятой точки. */
sealed class AcceptResult {
    data object Ok : AcceptResult()
    sealed class Rejected : AcceptResult() {
        data object SessionPaused : Rejected()
        data object SessionFinished : Rejected()
    }
}

/** Результат паузы. */
sealed class PauseResult {
    data object Ok : PauseResult()
    data class Invalid(val reason: String) : PauseResult()
}

/** Результат возобновления. */
sealed class ResumeResult {
    data object Ok : ResumeResult()
    data class Invalid(val reason: String) : ResumeResult()
}

/** Результат завершения. */
sealed class FinishResult {
    data object Ok : FinishResult()
    data class Invalid(val reason: String) : FinishResult()
}
