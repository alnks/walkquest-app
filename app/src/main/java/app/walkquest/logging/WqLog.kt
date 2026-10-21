package app.walkquest.logging

/**
 * Разноуровневое debug-логирование WalkQuest.
 *
 * Зачем свой фасад вместо Timber/Kermit/Napier (исследование 2026-10-21):
 * - Timber — Android-only: data-слои проекта чистый JVM (AD-10) и Android
 *   не импортируют, Timber из них недоступен; тег через stacktrace — лишний мусор.
 * - Kermit/Napier — KMP-логгеры, избыточны для Android-only приложения.
 * - Chucker/OkHttp LoggingInterceptor — требуют OkHttp; сеть проекта —
 *   HttpURLConnection.
 * Свой фасад — ~70 строк, ноль зависимостей, и data (JVM), и UI (Compose) звать
 * одну точку входа.
 *
 * Фильтрация по слоям — тегами logcat (единый префикс WQ/):
 * - `WQ/NET`     — каждый запрос-ответ (LeaderboardApi, манифест/пакеты);
 * - `WQ/SCREEN`  — открытие/закрытие экранов и вкладок;
 * - `WQ/GESTURE` — тапы/жесты (Compose-модификатор, тапы карты);
 * - `WQ/CACHE`   — попадания/промахи кэшей (туман, POI, границы, манифест);
 * - `WQ/UI`      — расположение/изменение границ UI-элементов.
 *
 * Примеры фильтров: `adb logcat -s WQ/NET:D`, `adb logcat -s WQ/GESTURE:D WQ/SCREEN:D`,
 * всё сразу: `adb logcat -s WQ/NET:D WQ/SCREEN:D WQ/GESTURE:D WQ/CACHE:D WQ/UI:D`
 * (или по префиксу: `adb logcat | grep "WQ/"`).
 *
 * Только debug-сборка: sink высаживается в WalkQuestApplication при
 * ApplicationInfo.FLAG_DEBUGGABLE; в release флаг выключен, sink = null и
 * каждый вызов уходит через проверку флага до вычисления строки (inline +
 * ленивая лямбда) — накладных расходов нет.
 *
 * Чистый JVM-код (AD-10): Android не импортируется; sink получает
 * приоритеты 2..6 (совпадают с android.util.Log), Android-слой сам маппит
 * их на Log.v/d/i/w/e. Логирование обёрнуто в runCatching — потеря строки
 * лога лучше падения приложения.
 */

/** Слой логирования = тег фильтрации в logcat. */
enum class WqLayer(val tag: String) {
    NET("WQ/NET"),
    SCREEN("WQ/SCREEN"),
    GESTURE("WQ/GESTURE"),
    CACHE("WQ/CACHE"),
    UI("WQ/UI"),
}

/** Приоритеты 2..6 — совпадают с android.util.Log (VERBOSE..ERROR). */
const val WQ_VERBOSE = 2
const val WQ_DEBUG = 3
const val WQ_INFO = 4
const val WQ_WARN = 5
const val WQ_ERROR = 6

/** Писатель логов: реализация живёт в Android-слое (WalkQuestApplication). */
fun interface WqLogSink {
    fun log(priority: Int, tag: String, message: String, throwable: Throwable?)
}

object WqLog {

    @PublishedApi
    @Volatile
    internal var enabled: Boolean = false

    @PublishedApi
    @Volatile
    internal var sink: WqLogSink? = null

    /**
     * Высаживает/убирает писателя. Вызывается один раз из Application.onCreate:
     * debug = (ApplicationInfo.FLAG_DEBUGGABLE != 0). Повторный вызов с
     * enabled=false (release) снимает sink — логи гарантированно не пишутся.
     */
    fun plant(enabled: Boolean, sink: WqLogSink) {
        this.enabled = enabled
        this.sink = if (enabled) sink else null
    }

    /** Жив ли логгер (debug-сборка) — для решений «собирать ли данные для лога». */
    fun isEnabled(): Boolean = enabled

    inline fun v(layer: WqLayer, crossinline message: () -> String) {
        if (enabled) emit(WQ_VERBOSE, layer, message(), null)
    }

    inline fun d(layer: WqLayer, crossinline message: () -> String) {
        if (enabled) emit(WQ_DEBUG, layer, message(), null)
    }

    inline fun i(layer: WqLayer, crossinline message: () -> String) {
        if (enabled) emit(WQ_INFO, layer, message(), null)
    }

    inline fun w(layer: WqLayer, crossinline message: () -> String) {
        if (enabled) emit(WQ_WARN, layer, message(), null)
    }

    inline fun e(layer: WqLayer, throwable: Throwable? = null, crossinline message: () -> String) {
        if (enabled) emit(WQ_ERROR, layer, message(), throwable)
    }

    @PublishedApi
    internal fun emit(priority: Int, layer: WqLayer, message: String, throwable: Throwable?) {
        val current = sink ?: return
        runCatching { current.log(priority, layer.tag, message, throwable) }
    }
}
