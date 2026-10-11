package app.walkquest.data.transfer

/**
 * Ошибки чтения архива переноса (история 6.1).
 * Typed-ошибки: UI показывает понятное сообщение, импорт не применяет ничего.
 */
sealed interface ExportReadError {

    /** Архив не читается как zip (обрезан, не-zip, битый манифест,
     *  секция больше лимита) — деталь для лога/отчёта. */
    data class CorruptArchive(val detail: String) : ExportReadError

    /** formatVersion новее известного читателю — архив от будущей версии
     *  приложения; нужна свежая версия WalkQuest. */
    data class UnsupportedFormat(val foundVersion: Int) : ExportReadError
}

/** Имена секций архива (пути entry внутри zip). */
object ExportSections {
    const val MANIFEST = "manifest.json"
    const val SETTINGS = "settings/settings.json"
    const val WALKS = "walks/walks.jsonl"
    const val EARNED = "achievements/earned.json"
    const val QUESTS = "quests/quests.json"
    const val PLANS = "plans/plans.json"
    const val POI_CARDS = "poicards.json"
    const val FOG_PREFIX = "fog/"
    const val TRACKS_PREFIX = "tracks/"

    /** Имена секций, которые импорт применяет; всё остальное — предупреждение. */
    fun isKnown(name: String): Boolean = when {
        name == MANIFEST || name == SETTINGS || name == WALKS || name == EARNED ||
            name == QUESTS || name == PLANS || name == POI_CARDS -> true
        name.startsWith(FOG_PREFIX) && name.length > FOG_PREFIX.length -> true
        name.startsWith(TRACKS_PREFIX) && name.length > TRACKS_PREFIX.length -> true
        else -> false
    }
}
