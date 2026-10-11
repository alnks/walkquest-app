package app.walkquest.data.fog

import java.io.File

/**
 * Тестовые фикстуры тумана: установленный пакет той же структуры, что выдаёт
 * tools/region-pack (история 1.3) и ставит data/packages (история 1.5),
 * с честным заголовком walkability.grid (формат v0) и bbox в meta.json.
 */
object TestFog {

    const val REGION_ID = "spb-city"

    // Синтетическая сетка: origin юго-западнее СПб, bbox как у эталонного пакета 1.3.
    const val COLS = 300
    const val ROWS = 300
    const val TILE_METERS = 100.0
    const val ORIGIN_LON = 30.0
    const val ORIGIN_LAT = 59.0
    val BBOX = listOf(30.0, 59.0, 30.63, 60.071)

    /** Сетка напрямую (без диска) — для тестов геометрии и биткарты. */
    fun fogGrid(
        cols: Int = COLS,
        rows: Int = ROWS,
        tileMeters: Double = TILE_METERS,
        originLon: Double = ORIGIN_LON,
        originLat: Double = ORIGIN_LAT,
        bbox: List<Double> = BBOX,
    ): FogGrid = FogGrid(cols, rows, tileMeters, originLon, originLat, bbox[1], bbox[3])

    /** Заголовок walkability.grid в формате генератора (walkability.py write_grid). */
    fun gridHeaderJson(
        cols: Int = COLS,
        rows: Int = ROWS,
        tileMeters: Double = TILE_METERS,
        originLon: Double = ORIGIN_LON,
        originLat: Double = ORIGIN_LAT,
        walkableCount: Int = 0,
    ): String =
        """{"cols": $cols, "rows": $rows, "tileSizeMeters": $tileMeters, """ +
            """"origin": [$originLon, $originLat], "order": "row-major, lsb-first", "walkableCount": $walkableCount}"""

    /**
     * Содержимое walkability.grid: строка заголовка + lsb-first битпак нужной длины
     * с выставленными проходимыми битами [walkable] (бит i = байт i/8, смещение i%8 —
     * как np.packbits(bitorder="little") у генератора).
     */
    fun gridFileBytes(cols: Int = COLS, rows: Int = ROWS, walkable: Set<Int> = emptySet()): ByteArray {
        val packed = ByteArray((cols * rows + 7) / 8)
        walkable.forEach { i ->
            require(i in 0 until cols * rows) { "проходимый индекс $i вне сетки" }
            packed[i / 8] = (packed[i / 8].toInt() or (1 shl (i % 8))).toByte()
        }
        return (gridHeaderJson(cols, rows, walkableCount = walkable.size) + "\n").toByteArray() + packed
    }

    /** meta.json с bbox (поля — как у эталонного пакета 1.3). */
    fun metaJson(
        regionId: String = REGION_ID,
        packageVersion: Int = 1,
        formatVersion: Int = 1,
        bbox: List<Double> = BBOX,
    ): String =
        """{"formatVersion":$formatVersion,"regionId":"$regionId","packageVersion":$packageVersion,""" +
            """"tileSizeMeters":$TILE_METERS,"maxzoom":14,"bbox":[${bbox.joinToString(",")}],""" +
            """"pmtilesUrlPlaceholder":"pmtiles://tiles.pmtiles","generatedBy":"test","checksums":{}}"""

    /** boundaries.geojson с одним полигоном-квадратом по bbox (формат генератора 1.3). */
    fun boundariesGeoJson(bbox: List<Double> = BBOX): String {
        val (minLon, minLat, maxLon, maxLat) = bbox
        val ring = listOf(
            listOf(minLon, minLat), listOf(maxLon, minLat), listOf(maxLon, maxLat),
            listOf(minLon, maxLat), listOf(minLon, minLat),
        ).joinToString(",") { (lon, lat) -> "[$lon,$lat]" }
        return """{"type":"FeatureCollection","features":[{"type":"Feature",""" +
            """"properties":{"admin_level":6,"name":"Тестовый регион","kind":"city"},""" +
            """"geometry":{"type":"Polygon","coordinates":[[$ring]]}}]}"""
    }

    /** Раскладывает установленный пакет на диск: packages/<regionId>/v<N>/{meta.json, walkability.grid, style.json, boundaries.geojson}. */
    fun installPackage(
        packagesRoot: File,
        regionId: String = REGION_ID,
        packageVersion: Int = 1,
        cols: Int = COLS,
        rows: Int = ROWS,
        walkable: Set<Int> = emptySet(),
        bbox: List<Double> = BBOX,
        originLon: Double = ORIGIN_LON,
        originLat: Double = ORIGIN_LAT,
    ): File {
        val dir = File(packagesRoot, "$regionId/v$packageVersion")
        dir.mkdirs()
        File(dir, "meta.json").writeText(metaJson(regionId, packageVersion, bbox = bbox))
        File(dir, "walkability.grid").writeBytes(
            (gridHeaderJson(cols, rows, originLon = originLon, originLat = originLat, walkableCount = walkable.size) + "\n")
                .toByteArray() + run {
                val packed = ByteArray((cols * rows + 7) / 8)
                walkable.forEach { i ->
                    require(i in 0 until cols * rows) { "проходимый индекс $i вне сетки" }
                    packed[i / 8] = (packed[i / 8].toInt() or (1 shl (i % 8))).toByte()
                }
                packed
            },
        )
        File(dir, "style.json").writeText("""{"version":8,"sources":{},"layers":[]}""")
        File(dir, "boundaries.geojson").writeText(boundariesGeoJson(bbox))
        return dir
    }
}
