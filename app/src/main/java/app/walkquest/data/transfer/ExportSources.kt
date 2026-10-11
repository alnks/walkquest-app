package app.walkquest.data.transfer

import java.io.File

/**
 * Источники данных для экспорта (история 6.1): те же корни, что инжектируются
 * в сторы через AppContainer. Чистый JVM-код (AD-10): файлы приходят снаружи.
 *
 * Пакеты регионов (`packages/`) и leaderboard/outbox в перенос не входят —
 * пакеты — скачиваемый контент, outbox восстанавливается синком агрегатов.
 */
data class ExportSources(
    /** Директория `settings/` (внутри — settings.json). */
    val settingsRoot: File,
    /** Директория `fog/` — биткарты `<regionId>.fog` (бинарные, копируются побайтово). */
    val fogRoot: File,
    /** Файл `walks/walks.jsonl` — append-only история завершённых прогулок (FR-13). */
    val walksFile: File,
    /** Файл `achievements/earned.json` — полученные достижения. */
    val earnedFile: File,
    /** Файл `quests/quests.json` — состояние заданий. */
    val questsFile: File,
    /** Файл `plans/plans.json` — запланированные прогулки. */
    val plansFile: File,
    /** Файл `poicards.json` — прочтённые карточки POI. */
    val poiCardsFile: File,
    /** Директория `tracks/` — журналы треков «свечи». */
    val tracksRoot: File,
    /** versionName приложения — в манифест. */
    val appVersion: String,
    /** Установленные регионы — в манифест (импорт предложит докачку пакетов). */
    val installedRegionIds: List<String> = emptyList(),
)
