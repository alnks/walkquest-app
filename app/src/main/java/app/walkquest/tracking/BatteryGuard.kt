package app.walkquest.tracking

/**
 * Батарейный страж (история 2.6, FR-10): пороги предупреждения и автозавершения.
 *
 * Чистый Kotlin без android.* (AD-10): вход — уровень заряда (%) по событиям
 * (сервис читает BatteryManager на принятых точках и на старте), пороги —
 * параметры из настроек. Сервис по сигналам шлёт тихое уведомление
 * (WarningDue) и завершает прогулку штатно (FinishDue → stopRecording:
 * сессия финализируется, агрегаты сохранены).
 *
 * Однократность: сигнал каждого типа — не более раза за жизнь гварда
 * (одна прогулка). Проверка «<= порог»: предупреждение не повторяется при
 * дальнейшем разряде, автозавершение — тем более. Рост заряда (зарядка)
 * сигналов не даёт и счётчики не сбрасывает — прогулка всё равно бережно
 * завершится при продолжающемся разряде.
 */
class BatteryGuard(
    private val warningPercent: Int = DEFAULT_WARNING_PERCENT,
    private val finishPercent: Int = DEFAULT_FINISH_PERCENT,
) {

    private var warningSent = false
    private var finishSent = false

    /** Уровень заряда пришел (0..100). Возвращает сигнал для сервиса. */
    fun onBatteryLevel(percent: Int): BatterySignal {
        if (finishSent) return BatterySignal.None
        if (!finishSent && percent <= finishPercent) {
            finishSent = true
            return BatterySignal.FinishDue
        }
        if (!warningSent && percent <= warningPercent) {
            warningSent = true
            return BatterySignal.WarningDue
        }
        return BatterySignal.None
    }

    /** Чистый срез на старте новой записи. */
    fun reset() {
        warningSent = false
        finishSent = false
    }

    init {
        require(warningPercent in 1..100 && finishPercent in 1..100) {
            "пороги батарейного стража должны быть в 1..100"
        }
        require(warningPercent >= finishPercent) {
            "порог предупреждения ($warningPercent%) не может быть ниже автозавершения ($finishPercent%)"
        }
    }

    companion object {
        /** Дефолт порога предупреждения (PRD FR-10). */
        const val DEFAULT_WARNING_PERCENT = 15

        /** Дефолт порога автозавершения (PRD FR-10). */
        const val DEFAULT_FINISH_PERCENT = 10
    }
}

/** Сигнал батарейного стража — подсказка сервису. */
enum class BatterySignal {
    /** Перехода нет. */
    None,

    /** Достигнут порог предупреждения: тихое уведомление (однократно). */
    WarningDue,

    /** Достигнут порог автозавершения: штатный стоп прогулки (однократно). */
    FinishDue,
}
