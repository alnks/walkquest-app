package app.walkquest.tracking

/**
 * Качество фикса для маркера «я» на карте (полевой тест 2, 2026-10-05, дефект 3:
 * владелец увидела себя «в 20 минутах езды» от реального места — показан старый
 * lastKnownLocation, показанный как свежая позиция).
 *
 * Маркер показываем, только когда фикс СВЕЖИЙ (не старше [MAX_FIX_AGE_MILLIS]) и
 * ДОСТАТОЧНО ТОЧНЫЙ (accuracy не хуже [MAX_FIX_ACCURACY_METERS]; accuracy null —
 * «провайдер не дал», не брак, как в PointFilter). Пока достойного фикса нет —
 * UI показывает состояние «Определяю местоположение…» вместо вводящего в заблуждение
 * маркера. Пороги — константы-гипотезы (тот же unknown, что SpeedLimits).
 *
 * Чистый Kotlin без android.* (AD-10) — JVM-тестируемо.
 */
object LocationFixQuality {

    /** Фикс грубее 100 м (вышки/сеть) маркером не показываем — врёт о месте. */
    const val MAX_FIX_ACCURACY_METERS = 100f

    /** Фикс старше ~30 с устарел: пользователь мог уйти далеко. */
    const val MAX_FIX_AGE_MILLIS = 30_000L

    /**
     * Достоин ли фикс показа маркером «я». [nowMillis] — текущее время (мс UTC),
     * передаётся вызывающей стороной (тестируемость; без скрытых часов).
     */
    fun isMarkerWorthy(
        accuracyMeters: Float?,
        fixEpochMillis: Long?,
        nowMillis: Long,
    ): Boolean {
        if (fixEpochMillis == null) return false
        if (nowMillis - fixEpochMillis > MAX_FIX_AGE_MILLIS) return false
        if (accuracyMeters != null && accuracyMeters > MAX_FIX_ACCURACY_METERS) return false
        return true
    }
}
