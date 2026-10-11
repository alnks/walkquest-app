package app.walkquest.data.fog

import kotlin.math.cos
import kotlin.math.floor
import kotlinx.serialization.Serializable

/**
 * Сетка тайлов региона — знаменатель тумана (история 1.6, AD-4).
 *
 * Геометрия обязана совпадать с генератором пакета
 * (`tools/region-pack/region_pack/walkability.py`, формат v0):
 *   origin = [minLon, minLat] (заголовок walkability.grid);
 *   dy = tileSizeMeters / 111320.0;
 *   dx = tileSizeMeters / (111320.0 * cos(центр bbox по широте)) — bbox из meta.json;
 *   index = row * cols + col (row-major); битпак lsb-first.
 *
 * Чистый JVM-код (AD-10): без android.* и MapLibre, гоняется unit-тестами.
 */

/** JSON-заголовок walkability.grid (первая строка файла, формат v0). */
@Serializable
data class GridHeader(
    val cols: Int,
    val rows: Int,
    val tileSizeMeters: Double,
    val origin: List<Double>,
    val order: String = "",
    val walkableCount: Int = 0,
)

/** Сетка тайлов пакета: проекция (lat, lon) ↔ индекс бита и геометрия тайлов для слоя. */
class FogGrid(
    val cols: Int,
    val rows: Int,
    val tileSizeMeters: Double,
    val originLon: Double,
    val originLat: Double,
    bboxMinLat: Double,
    bboxMaxLat: Double,
) {
    val tileCount: Int = cols * rows

    /** Шаг тайла по широте/долготе — формулы генератора (walkability.py build_grid). */
    val dy: Double = tileSizeMeters / 111320.0
    val dx: Double = tileSizeMeters / (111320.0 * cos(Math.toRadians((bboxMinLat + bboxMaxLat) / 2.0)))

    /** Северная и восточная границы сетки (последний тайл может выходить за bbox пакета — ceil). */
    val northLat: Double get() = originLat + rows * dy
    val eastLon: Double get() = originLon + cols * dx

    /**
     * Та же ли сетка (размерность, шаг, origin): биткарта валидна только против
     * идентичной сетки — новая версия пакета с другой сеткой сбрасывает туман.
     */
    fun sameGridAs(other: FogGrid): Boolean =
        cols == other.cols && rows == other.rows &&
            tileSizeMeters == other.tileSizeMeters &&
            originLon == other.originLon && originLat == other.originLat

    /**
     * Индекс бита тайла, содержащего точку, или null вне сетки.
     * Точка на восточной/северной границе сетки — вне региона (row == rows / col == cols).
     */
    fun indexAt(latitude: Double, longitude: Double): Int? {
        val col = floor((longitude - originLon) / dx).toInt()
        val row = floor((latitude - originLat) / dy).toInt()
        if (col !in 0 until cols || row !in 0 until rows) return null
        return row * cols + col
    }

    /**
     * Замкнутое кольцо тайла (5 точек lon/lat) — исторически дырка завесы открытого
     * тайла; с полевого теста 4 (2026-10-06, «свеча») рендер-просветы — круги по точкам
     * трека ([app.walkquest.tracking.circleRing]), а кольца тайлов остаются для тестов
     * геометрии и возможной отладочной рамки. Учёт открытия (биткарта) не изменился.
     * Обход по часовой (CW), противоположно экстерьеру ([worldRing]; ранее — [bboxRing]): правило правой руки
     * GeoJSON (RFC 7946) и classifyRings в MapLibre признают дыркой только кольцо
     * обратной ориентации; одинаковая ориентация рисовала дырку как залитый полигон
     * (дефект D2 эмуляторного прогона 2026-10-05 — туман визуально не гаснул).
     */
    fun tileRing(index: Int): List<DoubleArray> {
        require(index in 0 until tileCount) { "индекс тайла $index вне сетки 0..$tileCount" }
        val row = index / cols
        val col = index % cols
        val west = originLon + col * dx
        val east = west + dx
        val south = originLat + row * dy
        val north = south + dy
        return listOf(
            doubleArrayOf(west, south),
            doubleArrayOf(west, north),
            doubleArrayOf(east, north),
            doubleArrayOf(east, south),
            doubleArrayOf(west, south),
        )
    }

    /**
     * Замкнутое кольцо всей сетки — обход против часовой (CCW).
     * Исторически было экстерьером завесы; с полевого теста 3 (2026-10-05) экстерьер —
     * [worldRing], а это кольцо остаётся для тестов геометрии и отладочной рамки региона.
     */
    fun bboxRing(): List<DoubleArray> = listOf(
        doubleArrayOf(originLon, originLat),
        doubleArrayOf(eastLon, originLat),
        doubleArrayOf(eastLon, northLat),
        doubleArrayOf(originLon, northLat),
        doubleArrayOf(originLon, originLat),
    )

    /**
     * Замкнутое кольцо прямоугольника тайлов [run] (строка, колонки colStart..colEnd) —
     * дырка завесы для прогона водяных тайлов (разбор 2026-10-05: вода сквозь туман).
     * Та же ориентация обхода (CW), что у [tileRing]: MapLibre признаёт кольцо дыркой.
     */
    fun runRing(run: RowRun): List<DoubleArray> {
        require(run.row in 0 until rows) { "строка прогона ${run.row} вне сетки 0..$rows" }
        require(run.colStart in 0..run.colEnd && run.colEnd < cols) {
            "прогон колонок ${run.colStart}..${run.colEnd} вне сетки 0..$cols"
        }
        val west = originLon + run.colStart * dx
        val east = originLon + (run.colEnd + 1) * dx
        val south = originLat + run.row * dy
        val north = south + dy
        return listOf(
            doubleArrayOf(west, south),
            doubleArrayOf(west, north),
            doubleArrayOf(east, north),
            doubleArrayOf(east, south),
            doubleArrayOf(west, south),
        )
    }

    /** Узел решётки (col, row) в lon/lat — концы отрезков границы водяной маски. */
    fun gridPoint(col: Int, row: Int): DoubleArray =
        doubleArrayOf(originLon + col * dx, originLat + row * dy)

    companion object {
        /**
         * Предел широт веб-меркатора (±85.05112878°): дальше MapLibre мир не рендерит,
         * поэтому мировая завеса упирается именно в него, а не в полюса.
         */
        const val WEB_MERCATOR_MAX_LATITUDE = 85.05112877980659

        /**
         * Замкнутое кольцо ВСЕГО мира ([-180, -85.05]..[180, 85.05]) — экстерьер завесы
         * тумана (полевой тест 3, 2026-10-05: квадратная кромка по bbox региона некрасива).
         * Завеса покрывает всю видимую область «просто по границе» мира; за пределами
         * пакета тайлов и воды нет — там сплошная завеса. Механика по-прежнему только
         * внутри сетки региона: дырки — открытые тайлы + водяные прогоны пакета.
         * Обход против часовой (CCW) — как у [bboxRing]; дырки ([tileRing], [runRing])
         * по-прежнему обратной ориентации (CW), иначе MapLibre их не признаёт.
         */
        fun worldRing(): List<DoubleArray> = listOf(
            doubleArrayOf(-180.0, -WEB_MERCATOR_MAX_LATITUDE),
            doubleArrayOf(180.0, -WEB_MERCATOR_MAX_LATITUDE),
            doubleArrayOf(180.0, WEB_MERCATOR_MAX_LATITUDE),
            doubleArrayOf(-180.0, WEB_MERCATOR_MAX_LATITUDE),
            doubleArrayOf(-180.0, -WEB_MERCATOR_MAX_LATITUDE),
        )

        /**
         * Сетка из заголовка walkability.grid + bbox из meta.json пакета.
         * @throws IllegalArgumentException при несогласованных полях (ловится репозиторием в FogError).
         */
        fun fromPackage(header: GridHeader, bbox: List<Double>): FogGrid {
            require(header.cols > 0 && header.rows > 0) { "размерность сетки должна быть положительной" }
            require(header.tileSizeMeters > 0) { "tileSizeMeters должен быть положительным" }
            require(header.origin.size == 2) { "origin сетки должен быть [minLon, minLat]" }
            require(bbox.size == 4) { "bbox пакета должен быть [minLon, minLat, maxLon, maxLat]" }
            require(header.order.contains("row-major") && header.order.contains("lsb-first")) {
                "неподдерживаемый порядок битпака сетки: «${header.order}»"
            }
            return FogGrid(
                cols = header.cols,
                rows = header.rows,
                tileSizeMeters = header.tileSizeMeters,
                originLon = header.origin[0],
                originLat = header.origin[1],
                bboxMinLat = bbox[1],
                bboxMaxLat = bbox[3],
            )
        }
    }
}
