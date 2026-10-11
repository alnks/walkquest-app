package app.walkquest.tracking

/**
 * Выбор фикса для маркера «я» во время записи (полевой тест 3, 2026-10-05:
 * в помещении GPS молчит — точка «я» пропадала, пока шла запись).
 *
 * Сервис слушает GPS **и** NETWORK провайдеры; маркеру нужен СВЕЖАЙШИЙ фикс
 * независимо от провайдера — сетевой фикс в помещении лучше, чем никакого
 * (круг неточности честно показывает разброс). Этот выбор НЕ влияет на
 * открытие тайлов: биты тумана по-прежнему открывает только PointFilter
 * (accuracy ≤ 50 м) — грубые сетевые фиксы оттуда отсеются сами.
 *
 * Правило: кандидат вытесняет текущий, если его время фикса (Location.time)
 * позже; при равном времени — если точность лучше. Старый фикс, пришедший
 * с опозданием (сетевой реплей), свежий GPS-фикс не перетирает.
 *
 * Чистый Kotlin без android.* (AD-10) — JVM-тестируемо.
 */
class MarkerFixPicker {

    private var bestFixEpochMillis: Long? = null
    private var bestAccuracyMeters: Float? = null

    /**
     * true — кандидат достоин стать позицией маркера (свежее текущего, либо
     * точнее при равном времени); выбранный фикс запоминается как новый эталон.
     */
    fun acceptIfNewer(accuracyMeters: Float?, fixEpochMillis: Long): Boolean {
        val currentTime = bestFixEpochMillis
        val accept = when {
            currentTime == null -> true
            fixEpochMillis > currentTime -> true
            fixEpochMillis < currentTime -> false
            else -> isBetterAccuracy(accuracyMeters, bestAccuracyMeters)
        }
        if (accept) {
            bestFixEpochMillis = fixEpochMillis
            bestAccuracyMeters = accuracyMeters
        }
        return accept
    }

    /** Новая запись — выбор начинается с чистого листа. */
    fun reset() {
        bestFixEpochMillis = null
        bestAccuracyMeters = null
    }

    /** Точность лучше: число против null не выигрывает (нет данных — не хуже), меньшее число выигрывает. */
    private fun isBetterAccuracy(candidate: Float?, current: Float?): Boolean = when {
        current == null -> false // эталон без accuracy: кандидат с тем же временем его не перетирает
        candidate == null -> false
        else -> candidate < current
    }
}
