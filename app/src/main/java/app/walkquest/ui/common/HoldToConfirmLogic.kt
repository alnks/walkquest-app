package app.walkquest.ui.common

/**
 * Чистая логика удержания «Завершить» (7.12, AL-12; EXPERIENCE «Экран прогулки»:
 * завершение только удержанием ~600 мс — призрак-кнопка заполняется акцентом,
 * раннее отпускание откатывает заполнение и НЕ завершает прогулку; AlertDialog
 * убран — source conflict зафиксирован, спина важнее).
 *
 * Ни Compose, ни Android — юнит-тестируется напрямую (см. HoldToConfirmLogicTest).
 */
object HoldToConfirmLogic {

    /** Порог подтверждения: удержание ≥600 мс завершает прогулку. */
    const val THRESHOLD_MS: Long = 600L

    /** Откат заполнения при раннем отпускании (быстрее, чем заполнение — смысл «отмена»). */
    const val ROLLBACK_MS: Int = 200

    /** Доля заполнения 0..1 на момент [nowMs]; до старта — 0, после порога — 1. */
    fun progress(startedAtMs: Long, nowMs: Long): Float {
        if (nowMs <= startedAtMs) return 0f
        return ((nowMs - startedAtMs).toFloat() / THRESHOLD_MS).coerceIn(0f, 1f)
    }

    /** Подтверждено ли завершение при отпускании в [releasedAtMs]. */
    fun isConfirmed(startedAtMs: Long, releasedAtMs: Long): Boolean =
        releasedAtMs - startedAtMs >= THRESHOLD_MS
}
