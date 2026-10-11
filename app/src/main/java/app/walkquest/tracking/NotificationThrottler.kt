package app.walkquest.tracking

/**
 * Троттлер прогресс-уведомлений (история 2.7, FR-9): частота ограничена.
 *
 * Чистый Kotlin без android.* (AD-10): вход — время «сейчас», интервал —
 * параметр из настроек. Первый показ — сразу (старт прогулки пользователь
 * должен увидеть), дальше — не чаще интервала. Сервис держит экземпляр
 * на запись и сбрасывает на старте.
 */
class NotificationThrottler(
    private val intervalMillis: Long = DEFAULT_INTERVAL_MS,
) {

    private var lastNotifiedAtMillis: Long? = null

    /** true — уведомление показывать: первый вызов или интервал истёк. */
    fun shouldNotify(nowMillis: Long): Boolean {
        val last = lastNotifiedAtMillis
        if (last == null || nowMillis - last >= intervalMillis) {
            lastNotifiedAtMillis = nowMillis
            return true
        }
        return false
    }

    /** Чистый срез: новая запись — прогресс показываем сразу. */
    fun reset() {
        lastNotifiedAtMillis = null
    }

    companion object {
        /** Дефолт интервала прогресс-уведомлений: 5 минут (тюнер FR-9). */
        const val DEFAULT_INTERVAL_MS = 5L * 60L * 1_000L
    }
}
