package app.walkquest.tracking

/**
 * Детектор неподвижности для автопаузы (история 2.4, FR-7).
 *
 * Чистый Kotlin без android.* (AD-10): вход — поток ПРИНЯТЫХ фильтром точек
 * (сервис зовёт только на FilterVerdict.Accepted), поэтому грубые сетевые фиксы,
 * телепортации и джиттер у порога accuracy сюда не доходят — решает PointFilter.
 *
 * Модель «неподвижности»: не «нет точек вообще» (GPS в здании может молчать), а
 * «нет принятой точки дальше [MIN_MOVE_METERS] от референсной» в течение
 * [INACTIVITY_WINDOW_MS]. Стоящий человек с GPS-джиттером ±20 м порог не пробивает;
 * пошёл — первая же точка за 30 м от референса = движение.
 *
 * Сервис по сигналам двигает WalkSession (pause/resume) и публикует агрегаты;
 * трекер состояния сессии сам не держит (PauseDue/Resumed — подсказки, сервис
 * решает — например, настройка автопаузы может быть выключена).
 */
class MotionPauseTracker(
    private val inactivityWindowMillis: Long = INACTIVITY_WINDOW_MS,
    private val minMoveMeters: Double = MIN_MOVE_METERS,
) {

    /** Референсная точка: от неё меряем «пошёл ли человек». */
    private var reference: TrackPoint? = null

    /** Время начала текущего окна неподвижности (последнее движение). */
    private var stillSinceMillis: Long = 0L

    /** Пауза уже предложена и не снята движением — PauseDue не дублируем. */
    private var pausePending = false

    /** Напоминание о забытой прогулке отправлено и не снято движением (2.5). */
    private var reminderFired = false

    /**
     * Принятая фильтром точка. Возвращает сигнал: [MotionSignal.PauseDue]
     * (окно неподвижности истекло — сервис ставит сессию на паузу),
     * [MotionSignal.Resumed] (точка дальше порога от референса при pending-паузе —
     * сервис снимает паузу), [MotionSignal.ReminderDue] (2.5: пауза + второе
     * окно неподвижности — сервис шлёт напоминание «забыли остановить?»),
     * [MotionSignal.None] (рабочее состояние без перехода).
     */
    fun onAcceptedPoint(point: TrackPoint): MotionSignal {
        val ref = reference
        if (ref == null) {
            reference = point
            stillSinceMillis = point.epochMillis
            return MotionSignal.None
        }
        val moved = haversineMeters(ref, point) >= minMoveMeters
        if (moved) {
            val wasPaused = pausePending
            reference = point
            stillSinceMillis = point.epochMillis
            pausePending = false
            reminderFired = false
            return if (wasPaused) {
                MotionSignal.Resumed
            } else {
                MotionSignal.None
            }
        }
        if (!pausePending && point.epochMillis - stillSinceMillis >= inactivityWindowMillis) {
            pausePending = true
            return MotionSignal.PauseDue
        }
        if (pausePending && !reminderFired &&
            point.epochMillis - stillSinceMillis >= inactivityWindowMillis * 2
        ) {
            reminderFired = true
            return MotionSignal.ReminderDue
        }
        return MotionSignal.None
    }

    /** Чистый срез: новая запись — трекер с чистым референсом и окном. */
    fun reset() {
        reference = null
        stillSinceMillis = 0L
        pausePending = false
        reminderFired = false
    }

    companion object {
        /** Окно неподвижности до автопаузы (FR-7: 15 минут). */
        const val INACTIVITY_WINDOW_MS = 15L * 60L * 1_000L

        /**
         * Минимальное перемещение «человек пошёл», метры — гипотеза: меньше порог,
         * джиттер стоящего (±20 м в городской застройке) сбрасывает окно и пауза
         * никогда не наступает; больше — короткий отход от телефона на кофе не
         * засчитывается. Калибровка — полевые тесты (гейт 2.9), не здесь.
         */
        const val MIN_MOVE_METERS = 30.0
    }
}

/** Сигнал детектора — подсказка сервису, не состояние сессии. */
enum class MotionSignal {
    /** Перехода нет. */
    None,

    /** Окно неподвижности истекло: сервис ставит сессию на паузу (если автопауза вкл). */
    PauseDue,

    /** Движение после паузы: сервис снимает паузу (session.resume). */
    Resumed,

    /** 2.5: пауза + второе окно неподвижности — напоминание «забыли остановить?». */
    ReminderDue,
}
