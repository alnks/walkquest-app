package app.walkquest.domain.achievements

/**
 * Движок достижений (история 4.2, FR-15): по состоянию возвращает вновь
 * выполненные и фиксирует их в [EarnedStore] (idемпотентно — повторная проверка
 * не дублирует). Полная перепроверка по истории — тот же вызов: состояние
 * собирается вызывающим из всей истории, поэтому «задним числом» получается
 * автоматически (в т.ч. для новых defs из обновлений приложения).
 *
 * Чистый Kotlin (AD-10): JVM-тестируемо.
 */
class AchievementEngine(
    private val catalog: List<AchievementDef> = AchievementCatalog.ALL,
    private val earnedStore: EarnedStore,
) {

    /** Уже полученные (для экрана и фильтрации). */
    fun earned(): List<Pair<AchievementDef, Long>> =
        earnedStore.earned().mapNotNull { entry ->
            AchievementCatalog.byId(entry.id)?.let { it to entry.atEpochMillis }
        }

    /** Вновь выполненные по состоянию (без записи) — для предпросмотра/тестов. */
    fun newlyEarned(state: AchievementState): List<AchievementDef> =
        catalog.filter { def -> !earnedStore.contains(def.id) && def.check(state) }

    /** Проверка + фиксация: @return список вновь полученных (сохранены). */
    fun checkAndRecord(state: AchievementState, atEpochMillis: Long = System.currentTimeMillis()): List<AchievementDef> {
        val fresh = newlyEarned(state)
        for (def in fresh) earnedStore.record(def.id, atEpochMillis)
        return fresh
    }
}
