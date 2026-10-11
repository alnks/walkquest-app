package app.walkquest.domain.territory

/**
 * Модель территории (история 1.10, AD-9): принадлежность точки региону определяется
 * только здесь, в domain, по границам пакета (boundaries.geojson) — не «по текущему
 * экрану карты» и не по сетке тумана.
 *
 * Чистый Kotlin без Android/MapLibre (AD-10): геометрия приходит из data-слоя голыми
 * кольцами координат; point-in-polygon — ray casting (чётное/нечётное пересечение
 * луча +∞ по долготе), внешние кольца добавляют, дырки вычитают, multipolygon —
 * объединение. Точка на ребре считается внутренней (детерминированно).
 */

/** Один полигон границы: первое кольцо — внешнее, остальные — дырки. Кольцо — плоский массив [lon0, lat0, lon1, lat1, …]. */
class GeoPolygon(val rings: List<DoubleArray>) {
    init {
        require(rings.isNotEmpty()) { "полигон без колец" }
    }
}

/**
 * Границы одного установленного региона: bbox из meta.json ([minLon, minLat, maxLon, maxLat])
 * и полигоны из boundaries.geojson. Пустой [polygons] — деградация (файл границ битый/пустой):
 * принадлежность определяется по bbox.
 */
class RegionBoundary(
    val regionId: String,
    val bbox: List<Double>,
    val polygons: List<GeoPolygon>,
) {
    init {
        require(bbox.size == 4) { "bbox региона должен быть [minLon, minLat, maxLon, maxLat]" }
    }

    fun inBbox(latitude: Double, longitude: Double): Boolean =
        longitude >= bbox[0] && longitude <= bbox[2] && latitude >= bbox[1] && latitude <= bbox[3]
}

/** Ray casting по плоским кольцам GeoJSON (порядок координат lon, lat). */
object PointInPolygon {

    /** Точка внутри полигона: внутри внешнего кольца и не в дырках. */
    fun contains(latitude: Double, longitude: Double, polygon: GeoPolygon): Boolean {
        if (!ringContains(latitude, longitude, polygon.rings[0])) return false
        for (i in 1 until polygon.rings.size) {
            if (ringContains(latitude, longitude, polygon.rings[i])) return false
        }
        return true
    }

    /** Точка внутри хотя бы одного полигона (multipolygon / набор границ региона). */
    fun containsAny(latitude: Double, longitude: Double, polygons: List<GeoPolygon>): Boolean =
        polygons.any { contains(latitude, longitude, it) }

    /**
     * Чётно-нечётный ray casting: луч от точки на +∞ по долготе; пересечение с ребром —
     * когда широта точки в полуинтервале широт ребра и точка западнее пересечения.
     * Точка на ребре → true (детерминированная принадлежность граничных точек).
     */
    private fun ringContains(lat: Double, lon: Double, ring: DoubleArray): Boolean {
        val n = ring.size / 2
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = ring[i * 2]
            val yi = ring[i * 2 + 1]
            val xj = ring[j * 2]
            val yj = ring[j * 2 + 1]
            if (onSegment(lat, lon, xi, yi, xj, yj)) return true
            if ((yi > lat) != (yj > lat)) {
                val crossLon = xi + (lat - yi) / (yj - yi) * (xj - xi)
                if (lon < crossLon) inside = !inside
            }
            j = i
        }
        return inside
    }

    /** Точка лежит на отрезке (в пределах точности двойных координат GeoJSON). */
    private fun onSegment(lat: Double, lon: Double, x1: Double, y1: Double, x2: Double, y2: Double): Boolean {
        val cross = (lat - y1) * (x2 - x1) - (lon - x1) * (y2 - y1)
        if (kotlin.math.abs(cross) > EPSILON) return false
        return lon >= minOf(x1, x2) - EPSILON && lon <= maxOf(x1, x2) + EPSILON &&
            lat >= minOf(y1, y2) - EPSILON && lat <= maxOf(y1, y2) + EPSILON
    }

    private const val EPSILON = 1e-9
}

/**
 * Локатор региона точки: снимок границ установленных регионов → regionId.
 * bbox — дешёвый префильтр; полигоны решают. Регион без полигонов (деградация)
 * матчится по bbox. Пересекающиеся регионы (нештатно) — первый по списку.
 */
object RegionLocator {

    fun locate(latitude: Double, longitude: Double, regions: List<RegionBoundary>): String? {
        for (region in regions) {
            if (!region.inBbox(latitude, longitude)) continue
            if (region.polygons.isEmpty() ||
                PointInPolygon.containsAny(latitude, longitude, region.polygons)
            ) {
                return region.regionId
            }
        }
        return null
    }
}

/**
 * Правило домашнего региона (AD-9): сохранённый выбор пользователя, если его пакет
 * всё ещё установлен; иначе первый установленный; без пакетов — null (домашнего нет).
 */
object HomeRegionPolicy {

    fun resolve(storedHomeRegionId: String?, installedRegionIds: List<String>): String? =
        storedHomeRegionId?.takeIf { it in installedRegionIds } ?: installedRegionIds.firstOrNull()
}
