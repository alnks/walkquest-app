package app.walkquest.tracking

/**
 * Режим прогулки и лимит скорости по режиму (история 2.2, FR-6).
 *
 * Чистый Kotlin без android.* (AD-10). Дефолты — стартовые гипотезы PRD OQ-1
 * (~12 км/ч пеший, ~30 км/ч вело); точные значения калибрует spike 2.3
 * (полевые тесты) — здесь НЕ подбираем, только механика режимов и override'ов.
 */
enum class WalkMode {
    /** Пеший режим (по умолчанию). */
    WALKING,

    /** Вело/самокат: лимит выше пешего, но ниже городского автомобиля. */
    CYCLING,
}

/**
 * Единый резолвер эффективного лимита скорости режима (2.2): пользовательский
 * override из настроек имеет приоритет над дефолтом режима. UI показывает
 * эффективное значение, фильтр получает его готовым (AD-4: решает не UI).
 */
object SpeedLimitPolicy {

    /** Эффективный лимит режима, км/ч: [overrideKmh] из настроек или дефолт [mode]. */
    fun effectiveLimitKmh(mode: WalkMode, overrideKmh: Double?): Double =
        overrideKmh?.takeIf { it > 0.0 } ?: defaultLimitKmh(mode)

    /** Дефолт режима, км/ч (гипотезы OQ-1, не калиброваны). */
    fun defaultLimitKmh(mode: WalkMode): Double = when (mode) {
        WalkMode.WALKING -> SpeedLimits.WALKING_KMH
        WalkMode.CYCLING -> SpeedLimits.CYCLING_KMH
    }

    /** Эффективный лимит в м/с для PointFilter. */
    fun effectiveLimitMps(mode: WalkMode, overrideKmh: Double?): Double =
        effectiveLimitKmh(mode, overrideKmh) / 3.6
}
