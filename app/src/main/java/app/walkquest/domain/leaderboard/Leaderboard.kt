package app.walkquest.domain.leaderboard

/**
 * Лидерборд (эпик 5, FR-19): доменные правила. На сервер уходят ТОЛЬКО ник и
 * агрегаты (AD-3): суммарное расстояние прогулок по регионам (по regionIds
 * записей истории) и проценты открытия. Ник — при первом открытии рейтинга,
 * участие опционально. Чистый Kotlin (AD-10).
 */

/** Агрегаты одного региона для рейтинга (AD-3: только это уходит на сервер). */
@kotlinx.serialization.Serializable
data class RegionAggregate(
    val regionId: String,
    val distanceMeters: Double,
    /** Доля открытия 0..1. */
    val fraction: Double,
)

/** Снимок для публикации: ник + агрегаты по регионам. Никаких треков. */
@kotlinx.serialization.Serializable
data class LeaderboardSnapshot(
    val nickname: String,
    val regions: List<RegionAggregate>,
)

/** Политика ника (FR-19): 3..24 символа, буквы/цифры/подчёркивание/дефис. */
object NicknamePolicy {
    private val allowed = Regex("^[A-Za-zА-Яа-яЁё0-9_-]{3,24}$")

    /** null — ник допустим, иначе причина отказа. */
    fun validate(nickname: String): String? = when {
        nickname != nickname.trim() -> "уберите пробелы по краям"
        nickname.length < 3 -> "ник короче 3 символов"
        nickname.length > 24 -> "ник длиннее 24 символов"
        !allowed.matches(nickname) -> "только буквы, цифры, «_» и «-»"
        else -> null
    }

    fun isValid(nickname: String): Boolean = validate(nickname) == null
}
