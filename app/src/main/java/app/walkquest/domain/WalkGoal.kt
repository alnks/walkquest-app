package app.walkquest.domain

/**
 * Цель прогулки (история 3.2, FR-11): тип и значение. Обязательна к выбору
 * при регистрации (онбординг 3.3), изменяема в любой момент. Тексты цели —
 * готовые строки из domain (AD-4: UI рендерит, не склеивает). Чистый Kotlin.
 */
enum class GoalType {
    /** N прогулок в неделю (неделя — пн–вс локального календаря). */
    WALKS_PER_WEEK,

    /** N минут прогулки в день. */
    MINUTES_PER_DAY,

    /** N километров прогулки в день. */
    KM_PER_DAY,
}

/** Цель: тип + значение; диапазоны значений — [GoalType.validate]. */
data class WalkGoal(val type: GoalType, val value: Int) {

    init {
        require(isValid(type, value)) {
            "значение $value вне допустимого диапазона для $type " +
                "(${rangeText(type)}): ${validate(type, value)}"
        }
    }

    /** Человекочитаемое описание цели («3 прогулки в неделю») — готово для UI. */
    fun describe(): String = when (type) {
        GoalType.WALKS_PER_WEEK -> "$value ${plural(value, "прогулка", "прогулки", "прогулок")} в неделю"
        GoalType.MINUTES_PER_DAY -> "$value ${plural(value, "минута", "минуты", "минут")} в день"
        GoalType.KM_PER_DAY -> "%s км в день".format(kmText())
    }

    companion object {

        /** Допустимый диапазон значений типа (включительно). */
        fun range(type: GoalType): IntRange = when (type) {
            GoalType.WALKS_PER_WEEK -> 1..7
            GoalType.MINUTES_PER_DAY -> 10..600
            // км в день храним удвоенным (1 = 0,5 км): 1..100 → 0,5..50 км.
            GoalType.KM_PER_DAY -> 1..100
        }

        /** Валидация: null — значение допустимо, иначе причина отказа. */
        fun validate(type: GoalType, value: Int): String? = when (type) {
            GoalType.WALKS_PER_WEEK ->
                if (value in range(type)) null else "от 1 до 7 прогулок в неделю"
            GoalType.MINUTES_PER_DAY ->
                if (value in range(type)) null else "от 10 до 600 минут в день"
            GoalType.KM_PER_DAY ->
                if (value in range(type)) null else "от 0,5 до 50 км в день"
        }

        fun isValid(type: GoalType, value: Int): Boolean = validate(type, value) == null

        /** Текст диапазона для подсказок ввода. */
        fun rangeText(type: GoalType): String = when (type) {
            GoalType.WALKS_PER_WEEK -> "1–7"
            GoalType.MINUTES_PER_DAY -> "10–600"
            GoalType.KM_PER_DAY -> "0,5–50"
        }
    }

    /** Значение в км (храним удвоенное Int: 3 = 1,5 км). */
    fun kmValue(): Double = value / 2.0

    private fun kmText(): String {
        val km = kmValue()
        return if (km % 1.0 == 0.0) km.toInt().toString() else "%.1f".format(km).replace('.', ',')
    }

    private fun plural(n: Int, one: String, few: String, many: String): String {
        val mod100 = n % 100
        val mod10 = n % 10
        return when {
            mod100 in 11..19 -> many
            mod10 == 1 -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }
}
