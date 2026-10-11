package app.walkquest.tracking

import java.io.File

/**
 * История треков на диске → принятые точки для рендер-ленты «свечи»
 * (полевой тест 4, 2026-10-06). Источник — append-only журналы `tracks/track-*.track`
 * (история 1.7, AD-5): лента восстанавливается после перезапуска из тех же
 * журналов, что писал TrackingService, отдельного хранилища не заводится.
 *
 * Журнал пишет ВСЕ точки (сырая правда, AD-5), поэтому принятые восстанавливаются
 * посмертным replay того же [PointFilter] над точками журнала: фильтр детерминирован
 * (состояние — только якорь последней принятой), а с 2026-10-06 журнал хранит
 * accuracy каждой точки ("acc"), так что replay повторяет вердикты живой записи
 * один-в-один. Журналы старого формата (без "acc") replay'ятся с accuracy = null:
 * фикс без accuracy браком не считается (правило PointFilter) — расхождение
 * возможно только для грубых сетевых фиксов старых записей, зафиксировано как
 * допущение миграции (новые записи точные).
 *
 * Каждый журнал — отдельная запись: фильтр сбрасывается на файл (якорь не
 * перетекает между прогулками). Битый/несовместимый журнал пропускается,
 * усечённый хвост (убийство процесса) отбрасывается — как в [TrackJournal.read].
 * Приписывание точек региону — НЕ здесь, а у вызывающего (RegionLocator,
 * AD-9: принадлежность решает domain по границам пакетов).
 *
 * Чистый JVM-код (AD-10): корень журналов инжектируется, Android не импортируется.
 */
class TrackHistory(private val tracksRoot: File) {

    /**
     * Принятые фильтром точки, СГРУППИРОВАННЫЕ по журналам-записям в хронологии
     * (файлы `track-<epochMillis>.track` — сортировка по имени = по старту записи;
     * внутри файла — порядок журнала). Группировка — основа ленты по сессиям
     * (полевой баг 2026-10-08): между записями тропа НЕ соединяется — иначе буфер
     * полилинии рисовал прямые линии через весь регион между разрозненными точками
     * разных дней. Пустые записи (все точки отсеяны) группы не дают.
     *
     * Replay фильтра — с временем старта записи из заголовка журнала: устаревшие
     * фиксы-призраки отсекаются как в живой записи (PointFilter, 2026-10-08).
     */
    fun acceptedPointsByJournal(): List<List<TrackPoint>> {
        val files = tracksRoot.listFiles { file ->
            file.isFile && file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_EXTENSION)
        }?.sortedBy { it.name } ?: return emptyList()
        val sessions = ArrayList<List<TrackPoint>>()
        for (file in files) {
            val read = TrackJournal(file).read()
            if (read !is JournalRead.Ok) continue // битый журнал — не наша забота здесь
            // Новая запись — чистый якорь (как у сервиса) и её время старта.
            val filter = PointFilter(sessionStartEpochMillis = read.startedAtEpochMillis)
            val accepted = read.points.filter { filter.check(it) is FilterVerdict.Accepted }
            if (accepted.isNotEmpty()) sessions.add(accepted)
        }
        return sessions
    }

    /**
     * Все принятые точки всех журналов плоским списком (хронология записей).
     * Для рендера ленты использовать [acceptedPointsByJournal] — плоский список
     * соединяет разные записи в одну полилинию (баг «прямых линий» 2026-10-08).
     */
    fun acceptedPoints(): List<TrackPoint> = acceptedPointsByJournal().flatten()

    companion object {
        /** Имя файла журнала — по конвенции TrackingService: track-<epochMillis>.track. */
        internal const val FILE_PREFIX = "track-"
        internal const val FILE_EXTENSION = ".track"
    }
}
