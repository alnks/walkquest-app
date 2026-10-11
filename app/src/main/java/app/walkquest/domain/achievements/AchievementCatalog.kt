package app.walkquest.domain.achievements

import app.walkquest.domain.DistrictProgress
import app.walkquest.tracking.WalkRecord
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Достижения (история 4.2, FR-15): каталог из content-catalog (38 в движке v1;
 * D4 «В строю» и D5 «Вместе весело шагать» — рейтинг/приглашения, нужен
 * Supabase, отложены в epic-leaderboard). Условия — функции над [AchievementState]:
 * проверка после каждой завершённой прогулки И полная перепроверка по истории
 * (задним числом, FR-15 — новые достижения из обновлений выдаются по истории).
 *
 * Чистый Kotlin (AD-10): состояние собирает Android-слой, правила — здесь.
 */

enum class AchCategory(val title: String) {
    EXPLORATION("Исследование"),
    REGULARITY("Регулярность"),
    DISTANCE("Дистанция и время"),
    QUESTS("Задания и рейтинг"),
    TRAVEL("Путешествия"),
}

/** Состояние для проверки условий: собирается вызывающим из истории/прогресса/счётчиков. */
data class AchievementState(
    val records: List<WalkRecord>,
    val homeRegionId: String?,
    /** Доля открытия домашнего города 0..1 (null — не вычислена). */
    val homeCityFraction: Double?,
    /** Районы домашнего региона (4.3). */
    val homeDistricts: List<DistrictProgress>,
    /** Доля открытия по другим регионам (regionId → fraction, 4.3/E2). */
    val otherRegionFractions: Map<String, Double>,
    val poiReadCount: Int,
    /** Всего POI региона на момент проверки (A14 — по снимку). */
    val totalPoiCount: Int,
    /** Прочитано разных парков (категория leisure=park, 4.1). */
    val parksReadCount: Int,
    val streakDays: Int,
    val questsAcceptedCount: Int,
    val questsCompletedCount: Int,
    /** D4: профиль опубликован (эпик 5). */
    val leaderboardJoined: Boolean = false,
    /** D5: друг принял приглашение (эпик 5). */
    val friendAcceptedInvite: Boolean = false,
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    val totalDistanceMeters: Double get() = records.sumOf { it.distanceMeters }

    fun startedAt(record: WalkRecord): LocalTime =
        LocalTime.ofInstant(Instant.ofEpochMilli(record.startedAtEpochMillis), zone)

    fun localDate(record: WalkRecord): LocalDate =
        LocalDate.ofInstant(Instant.ofEpochMilli(record.startedAtEpochMillis), zone)

    /** Регионы с прогулками (по сигналам 4.1). */
    fun walkedRegionIds(): Set<String> = records.flatMap { it.regionIds }.toSet()

    fun nonHomeWalkedRegionIds(): Set<String> =
        walkedRegionIds().filterNot { it == homeRegionId }.toSet()
}

/** Определение достижения: метаданные каталога + правило. */
class AchievementDef(
    val id: String,
    val icon: String,
    val name: String,
    val conditionText: String,
    val category: AchCategory,
    val rare: Boolean = false,
    val hasProgress: Boolean = false,
    val check: (AchievementState) -> Boolean,
    /** Прогресс-бар: (текущее, цель) для экрана наград; null у без-барных. */
    val progress: ((AchievementState) -> Pair<Double, Double>)? = null,
)

/** Каталог достижений (content-catalog.md, 2026-10-01, final). */
object AchievementCatalog {

    val ALL: List<AchievementDef> = listOf(
        // --- Исследование (16) ---
        AchievementDef("A1", "🌱", "Первый шаг", "Откройте первый участок тумана", AchCategory.EXPLORATION,
            check = { it.records.any { r -> r.acceptedCount > 0 } }),
        AchievementDef("A2", "🧭", "Картограф", "Откройте 10% своего города", AchCategory.EXPLORATION,
            hasProgress = true,
            check = { (it.homeCityFraction ?: 0.0) >= 0.10 },
            progress = { (it.homeCityFraction ?: 0.0) to 0.10 }),
        AchievementDef("A3", "🏙️", "Знаток района", "Откройте 25% своего города", AchCategory.EXPLORATION,
            hasProgress = true,
            check = { (it.homeCityFraction ?: 0.0) >= 0.25 },
            progress = { (it.homeCityFraction ?: 0.0) to 0.25 }),
        AchievementDef("A4", "🏘️", "Свой человек", "Откройте 50% своего города", AchCategory.EXPLORATION,
            hasProgress = true,
            check = { (it.homeCityFraction ?: 0.0) >= 0.50 },
            progress = { (it.homeCityFraction ?: 0.0) to 0.50 }),
        AchievementDef("A5", "🏰", "Почти весь город", "Откройте 70% своего города", AchCategory.EXPLORATION,
            hasProgress = true,
            check = { (it.homeCityFraction ?: 0.0) >= 0.70 },
            progress = { (it.homeCityFraction ?: 0.0) to 0.70 }),
        AchievementDef("A6", "👑", "Хозяин города", "Откройте 90% своего города", AchCategory.EXPLORATION,
            rare = true, hasProgress = true,
            check = { (it.homeCityFraction ?: 0.0) >= 0.90 },
            progress = { (it.homeCityFraction ?: 0.0) to 0.90 }),
        AchievementDef("A7", "🌳", "Исследователь пригорода", "Прогулки в 2+ населённых пунктах области кроме домашнего",
            AchCategory.EXPLORATION, hasProgress = true,
            check = { it.nonHomeWalkedRegionIds().size >= 2 },
            progress = { it.nonHomeWalkedRegionIds().size.toDouble() to 2.0 }),
        AchievementDef("A8", "🎶", "Районы-кварталы", "Полностью откройте один район города", AchCategory.EXPLORATION,
            hasProgress = true,
            check = { it.homeDistricts.count { d -> d.fullyOpened } >= 1 },
            progress = { it.homeDistricts.count { d -> d.fullyOpened }.toDouble() to 1.0 }),
        AchievementDef("A9", "🗺️", "Два района", "Полностью откройте 2 района", AchCategory.EXPLORATION,
            hasProgress = true,
            check = { it.homeDistricts.count { d -> d.fullyOpened } >= 2 },
            progress = { it.homeDistricts.count { d -> d.fullyOpened }.toDouble() to 2.0 }),
        AchievementDef("A10", "🏆", "Три района", "Полностью откройте 3 района", AchCategory.EXPLORATION,
            rare = true, hasProgress = true,
            check = { it.homeDistricts.count { d -> d.fullyOpened } >= 3 },
            progress = { it.homeDistricts.count { d -> d.fullyOpened }.toDouble() to 3.0 }),
        AchievementDef("A11", "💡", "Путеводный огонь", "Откройте карточку первой точки интереса",
            AchCategory.EXPLORATION,
            check = { it.poiReadCount >= 1 }),
        AchievementDef("A12", "🔦", "Коллекционер огней", "Прочитайте 10 точек интереса", AchCategory.EXPLORATION,
            hasProgress = true,
            check = { it.poiReadCount >= 10 },
            progress = { it.poiReadCount.toDouble() to 10.0 }),
        AchievementDef("A13", "✨", "Тридцать огней", "Прочитайте 30 точек интереса", AchCategory.EXPLORATION,
            hasProgress = true,
            check = { it.poiReadCount >= 30 },
            progress = { it.poiReadCount.toDouble() to 30.0 }),
        AchievementDef("A14", "📖", "Все истории города", "Прочитайте все точки интереса региона",
            AchCategory.EXPLORATION, rare = true, hasProgress = true,
            check = { it.totalPoiCount > 0 && it.poiReadCount >= it.totalPoiCount },
            progress = { it.poiReadCount.toDouble() to it.totalPoiCount.coerceAtLeast(1).toDouble() }),
        AchievementDef("A15", "🌲", "Частичка природы", "Посетите 1 парк", AchCategory.EXPLORATION,
            check = { it.parksReadCount >= 1 }),
        AchievementDef("A16", "🌲🌲", "Натуралист", "Посетите 3 разных парка", AchCategory.EXPLORATION,
            hasProgress = true,
            check = { it.parksReadCount >= 3 },
            progress = { it.parksReadCount.toDouble() to 3.0 }),

        // --- Регулярность (7) ---
        AchievementDef("B1", "🔥", "Три подряд", "Серия 3 дня", AchCategory.REGULARITY, hasProgress = true,
            check = { it.streakDays >= 3 }, progress = { it.streakDays.toDouble() to 3.0 }),
        AchievementDef("B2", "📅", "Неделька", "Серия 7 дней", AchCategory.REGULARITY, hasProgress = true,
            check = { it.streakDays >= 7 }, progress = { it.streakDays.toDouble() to 7.0 }),
        AchievementDef("B3", "🗓️", "Две недели", "Серия 14 дней", AchCategory.REGULARITY, hasProgress = true,
            check = { it.streakDays >= 14 }, progress = { it.streakDays.toDouble() to 14.0 }),
        AchievementDef("B4", "🌟", "Месяц в строю", "Серия 30 дней", AchCategory.REGULARITY,
            rare = true, hasProgress = true,
            check = { it.streakDays >= 30 }, progress = { it.streakDays.toDouble() to 30.0 }),
        AchievementDef("B5", "🌅", "Ранняя птица", "Прогулка, начатая до 8:00", AchCategory.REGULARITY,
            check = { it.records.any { r -> it.startedAt(r).hour < 8 } }),
        AchievementDef("B6", "🦉", "Полуночник", "Прогулка, начатая после 22:00", AchCategory.REGULARITY,
            check = { it.records.any { r -> it.startedAt(r).hour >= 22 } }),
        AchievementDef("B7", "🥪", "Обеденный перерыв", "Прогулка в будний день между 12:00 и 14:00",
            AchCategory.REGULARITY,
            check = {
                it.records.any { r ->
                    val day = it.localDate(r).dayOfWeek.value // 1=пн..7=вс
                    day <= 5 && it.startedAt(r).hour in 12..13
                }
            }),

        // --- Дистанция и время (8) ---
        AchievementDef("C1", "👟", "Первые пять", "5 км суммарно", AchCategory.DISTANCE, hasProgress = true,
            check = { it.totalDistanceMeters >= 5_000.0 },
            progress = { it.totalDistanceMeters to 5_000.0 }),
        AchievementDef("C2", "🎽", "Марафонец", "42 км суммарно", AchCategory.DISTANCE, hasProgress = true,
            check = { it.totalDistanceMeters >= 42_000.0 },
            progress = { it.totalDistanceMeters to 42_000.0 }),
        AchievementDef("C3", "🚂", "Сотня", "100 км суммарно", AchCategory.DISTANCE,
            rare = true, hasProgress = true,
            check = { it.totalDistanceMeters >= 100_000.0 },
            progress = { it.totalDistanceMeters to 100_000.0 }),
        AchievementDef("C4", "⏱️", "Хороший темп", "Одна прогулка длиннее 45 минут", AchCategory.DISTANCE,
            check = { it.records.any { r -> r.activeMillis > 45L * 60_000L } }),
        AchievementDef("C5", "⌛", "Долгая прогулка", "Одна прогулка длиннее 90 минут", AchCategory.DISTANCE,
            check = { it.records.any { r -> r.activeMillis > 90L * 60_000L } }),
        AchievementDef("C6", "🚲", "Ветер в волосах", "Первая велопрогулка", AchCategory.DISTANCE,
            check = { it.records.any { r -> "CYCLING" in r.modes } }),
        AchievementDef("C7", "🌄", "Дважды за день", "Две прогулки в один день", AchCategory.DISTANCE,
            check = { it.records.groupingBy { r -> it.localDate(r) }.eachCount().values.any { n -> n >= 2 } }),
        AchievementDef("C8", "💛", "Любимая тропа",
            "Прогулка от 1 км по уже открытому месту: ничего нового не открыто", AchCategory.DISTANCE,
            check = {
                it.records.any { r -> r.distanceMeters >= 1_000.0 && r.newTilesOpened == 0 }
            }),

        // --- Задания и рейтинг (3 в движке v1; D4/D5 — epic-leaderboard) ---
        AchievementDef("D1", "🎲", "Азарт", "Примите первое задание", AchCategory.QUESTS,
            check = { it.questsAcceptedCount >= 1 }),
        AchievementDef("D2", "✅", "Слово держу", "Выполните 5 заданий", AchCategory.QUESTS, hasProgress = true,
            check = { it.questsCompletedCount >= 5 },
            progress = { it.questsCompletedCount.toDouble() to 5.0 }),
        AchievementDef("D3", "🃏", "Старательный", "Выполните 15 заданий", AchCategory.QUESTS, hasProgress = true,
            check = { it.questsCompletedCount >= 15 },
            progress = { it.questsCompletedCount.toDouble() to 15.0 }),
        // D4/D5 (эпик 5): флаги выдачи — состояние; RPC accept_invite поднимает hasFriendAccepted.
        AchievementDef("D4", "🪪", "В строю", "Вступите в рейтинг (задайте ник)", AchCategory.QUESTS,
            check = { it.leaderboardJoined }),
        AchievementDef("D5", "🤝", "Вместе весело шагать", "Друг принял приглашение", AchCategory.QUESTS,
            check = { it.friendAcceptedInvite }),

        // --- Путешествия (4) ---
        AchievementDef("E1", "🧳", "Гость", "Прогулка в новом регионе", AchCategory.TRAVEL,
            check = { it.nonHomeWalkedRegionIds().isNotEmpty() }),
        AchievementDef("E2", "🥾", "Освоение", "Откройте 10% любого другого региона", AchCategory.TRAVEL,
            hasProgress = true,
            check = { it.otherRegionFractions.values.any { f -> f >= 0.10 } },
            progress = {
                (it.otherRegionFractions.values.filter { f -> f < 0.10 }.maxOrNull() ?: 0.0) to 0.10
            }),
        AchievementDef("E3", "🌍", "След на карте", "Прогулки в 3 регионах", AchCategory.TRAVEL,
            rare = true, hasProgress = true,
            check = { it.walkedRegionIds().size >= 3 },
            progress = { it.walkedRegionIds().size.toDouble() to 3.0 }),
        AchievementDef("E4", "🧭", "Путешественник", "Прогулки в 5 регионах", AchCategory.TRAVEL,
            hasProgress = true,
            check = { it.walkedRegionIds().size >= 5 },
            progress = { it.walkedRegionIds().size.toDouble() to 5.0 }),
    )

    fun byId(id: String): AchievementDef? = ALL.firstOrNull { it.id == id }
}
