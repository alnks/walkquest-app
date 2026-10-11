package app.walkquest.tracking

/**
 * Фильтр валидности точек трека (история 1.7, AD-4: биты открываются только
 * после фильтрации). Чистый Kotlin без android.* (AD-10) — JVM-тестируемо.
 *
 * Якорь — последняя ПРИНЯТАЯ точка: одиночный GPS-глитч якорь не сдвигает,
 * следующая точка сравнивается с последней честной. Отвергнутая точка фильтром
 * всё равно пишется в журнал сервисом (сырая правда, AD-5) — фильтр решает
 * только открытие битов тумана.
 *
 * Пороги — гипотезы из [SpeedLimits]/[TELEPORT_WINDOW_SECONDS] (unknown тикета,
 * калибровка — полевые тесты, HITL).
 */
class PointFilter(
    limitMps: Double = SpeedLimits.WALKING_MPS,
    teleportJumpMeters: Double = limitMps * TELEPORT_WINDOW_SECONDS,
    private val maxAccuracyMeters: Float = MAX_ACCURACY_METERS,
    /**
     * Время старта записи (мс UTC) — отсечение УСТАРЕВШИХ фиксов (полевой баг
     * 2026-10-08): при регистрации слушателя LocationManager немедленно отдаёт
     * кэшированный lastKnown с СТАРЫМ Location.time; такой фикс-призрак становился
     * якорем в чужом месте, а следующий живой фикс принимался (огромный dt →
     * крошечная implied speed) — на карте росла прямая линия «призрак → я».
     * Фикс старше [STALE_GRACE_MS] до старта записи — брак [Rejected.StaleFix].
     * Replay журналов передаёт startedAt заголовка — правило повторяет живую запись.
     * null — проверка выключена (старые вызовы/тесты механики фильтра).
     */
    private val sessionStartEpochMillis: Long? = null,
) {

    private var anchor: TrackPoint? = null

    /** Действующий лимит скорости, м/с (2.2 — меняется сменой режима). */
    var limitMps: Double = limitMps
        private set

    /** Порог телепортации, м — произведение лимита на окно; пересчитывается с лимитом. */
    var teleportJumpMeters: Double = teleportJumpMeters
        private set

    /**
     * Смена лимита на лету (2.2, FR-6: переключение режима во время прогулки).
     * Порог телепортации пересчитывается из нового лимита; якорь не трогаем —
     * смена режима не делает честную точку телепортацией.
     */
    fun updateLimit(newLimitMps: Double) {
        limitMps = newLimitMps
        teleportJumpMeters = newLimitMps * TELEPORT_WINDOW_SECONDS
    }

    /** Сброс якоря — новая запись начинается с чистого фильтра. */
    fun reset() {
        anchor = null
    }

    /**
     * Проверяет точку против якоря. Первая точка принимается, если она точная.
     * Принятая точка становится новым якорем; отвергнутая якорь не двигает.
     *
     * Точность проверяется ДО якоря (дефект разбора 2026-10-05: «туман не рассеялся
     * при записи + случайные квадраты»): первые фиксы после старта часто приходят
     * с вышек/сети с accuracy в сотни метров; без отсечения такая точка становилась
     * якорем и открывала тайл в случайном месте, а честный фикс рядом с пользователем
     * потом отсекался как телепортация. Грубый фикс якорем не становится — следующая
     * точная точка открывает тайл реальной позиции сразу (первый валидный фикс).
     */
    fun check(point: TrackPoint): FilterVerdict {
        val sessionStart = sessionStartEpochMillis
        if (sessionStart != null && point.epochMillis < sessionStart - STALE_GRACE_MS) {
            return FilterVerdict.Rejected.StaleFix(sessionStart - point.epochMillis)
        }
        val accuracy = point.accuracyMeters
        if (accuracy != null && accuracy > maxAccuracyMeters) {
            return FilterVerdict.Rejected.Inaccurate(accuracy, maxAccuracyMeters)
        }
        val a = anchor
        if (a == null) {
            anchor = point
            return FilterVerdict.Accepted
        }
        val dtMillis = point.epochMillis - a.epochMillis
        if (dtMillis <= 0L) {
            return FilterVerdict.Rejected.NonMonotonicTime(dtMillis)
        }
        val distance = haversineMeters(a, point)
        if (distance > teleportJumpMeters) {
            return FilterVerdict.Rejected.Teleport(distance, teleportJumpMeters)
        }
        val speedMps = distance / (dtMillis / 1000.0)
        if (speedMps > limitMps) {
            return FilterVerdict.Rejected.TooFast(speedMps, limitMps)
        }
        anchor = point
        return FilterVerdict.Accepted
    }

    companion object {
        /**
         * Порог приемлемой точности фикса, метры. Константа-гипотеза (тот же unknown,
         * что и [SpeedLimits]): калибровка — полевые тесты (HITL).
         * История: 25 м (разбор 2026-10-05) оказалось слишком строгим — полевой тест 2
         * (2026-10-05): городской GPS в застройке стабильно заявляет 20–60 м, порог 25 м
         * браковал ВСЕ фиксы («точек: 0, отсеяно: 34», туман не открывался). Поднято до
         * 50 м (городские условия); грубые фиксы с вышек/сети (сотни метров) отсекаются
         * по-прежнему. Фикс без accuracy браком не считается (null = «провайдер не дал»,
         * а не «неточный»).
         */
        const val MAX_ACCURACY_METERS = 50f

        /**
         * Допуск свежести фикса относительно старта записи, мс: живой фикс приходит
         * с задержкой до пары секунд — не брак; кэшированный lastKnown старше этого
         * окна до старта записи — призрак ([Rejected.StaleFix], полевой баг 2026-10-08).
         */
        const val STALE_GRACE_MS = 10_000L
    }
}

/** Вердикт фильтра — sealed-результат через границы (Consistency Conventions). */
sealed interface FilterVerdict {

    /** Точка валидна и стала новым якорем. */
    data object Accepted : FilterVerdict

    /** Точка отвергнута; якорь не изменился. */
    sealed class Rejected : FilterVerdict {

        /** implied speed выше лимита (скорость в м/с и действующий лимит). */
        data class TooFast(val speedMps: Double, val limitMps: Double) : Rejected()

        /** Скачок дальше порога телепортации при любом dt (дистанция и порог в метрах). */
        data class Teleport(val distanceMeters: Double, val thresholdMeters: Double) : Rejected()

        /** Время точки не позже якоря (сдвиг в мс, ≤ 0). */
        data class NonMonotonicTime(val deltaMillis: Long) : Rejected()

        /**
         * Заявленная точность хуже порога (accuracy и порог в метрах): фикс с вышек/
         * сети, а не GPS — якорем не становится, тайл не открывает (разбор 2026-10-05).
         */
        data class Inaccurate(val accuracyMeters: Float, val limitMeters: Float) : Rejected()

        /**
         * Устаревший фикс: Location.time старше окна до старта записи (возраст в мс) —
         * кэшированный lastKnown-призрак (полевой баг 2026-10-08: прямая линия тропы
         * от чужого места к реальному). Якорем не становится.
         */
        data class StaleFix(val ageMillis: Long) : Rejected()
    }
}
