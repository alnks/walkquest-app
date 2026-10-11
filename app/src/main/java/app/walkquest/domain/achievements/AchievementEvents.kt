package app.walkquest.domain.achievements

/**
 * Мост новых достижений к финальному экрану (история 4.6): сервис после
 * прогулки прогоняет движок и кладёт id вновь полученных сюда; реализация
 * порта WalkAchievements (2.8) отдаёт их названия. Очищается на старте новой
 * записи. Простое решение без шины (порт и так опрашивается при FINISHED).
 */
object AchievementEvents {
    @Volatile
    var lastWalkNewIds: List<String> = emptyList()

    fun clear() {
        lastWalkNewIds = emptyList()
    }
}
