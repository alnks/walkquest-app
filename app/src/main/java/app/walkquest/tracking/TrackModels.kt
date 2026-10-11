package app.walkquest.tracking

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Модели записи трека (история 1.7, AD-5).
 *
 * Чистый Kotlin без android.* (AD-10): JVM-тестируемо. Логи и уведомления
 * координат не содержат (AD-3) — модель нигде не сериализуется в лог.
 */

/**
 * Одна зафиксированная точка трека; время — epoch millis UTC (Consistency Conventions).
 * [accuracyMeters] — заявленная провайдером точность фикса; null — провайдер её не дал.
 * В журнал (JSONL) не попадает: формат PointLine {lat, lon, t} не меняется (сырая правда
 * координат по AD-5 сохраняется как была); точность нужна только фильтру в памяти.
 */
data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val epochMillis: Long,
    val accuracyMeters: Float? = null,
)

/**
 * Лимиты скорости фильтра — стартовые гипотезы (unknown тикета 1.7,
 * spine Deferred, PRD OQ-1). Точные значения — полевые тесты на устройстве (HITL),
 * здесь НЕ калибруем.
 */
object SpeedLimits {
    const val WALKING_KMH = 12.0
    const val CYCLING_KMH = 30.0
    const val WALKING_MPS = WALKING_KMH / 3.6
    const val CYCLING_MPS = CYCLING_KMH / 3.6
}

/**
 * Окно телепортации в секундах: скачок дальше, чем limit × окно, — GPS-глитч,
 * а не перемещение (даже если dt большой — «прыгнул и вернулся» при спящем экране).
 * Гипотеза, калибровка — полевые тесты (тот же unknown, что и лимиты).
 */
const val TELEPORT_WINDOW_SECONDS = 120.0

/** Ортодромическое расстояние между точками в метрах (haversine, R = 6371000 м). */
fun haversineMeters(a: TrackPoint, b: TrackPoint): Double {
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return 2.0 * EARTH_RADIUS_METERS * atan2(sqrt(h), sqrt(1.0 - h))
}

private const val EARTH_RADIUS_METERS = 6_371_000.0
