package app.walkquest.data.boundaries

import app.walkquest.data.packages.InstalledPackage
import app.walkquest.data.packages.PackageJson
import app.walkquest.data.packages.PackageMeta
import app.walkquest.domain.territory.GeoPolygon
import app.walkquest.domain.territory.RegionBoundary
import app.walkquest.logging.WqLayer
import app.walkquest.logging.WqLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Границы региона из пакета (история 1.10, AD-9): читает boundaries.geojson
 * установленного пакета (Polygon/MultiPolygon, координаты lon/lat) и bbox из
 * meta.json и отдаёт domain голую геометрию ([RegionBoundary]) — domain сам
 * решает принадлежность точки (AD-10: domain не парсит JSON, data не содержит
 * правил территории).
 *
 * Деградация без отказа: битый/пустой boundaries.geojson → границы только по
 * bbox meta.json ([BoundaryResult.Ok.polygonsDegraded] = true) — туман и трек
 * продолжают работать; ошибка видна вызывающему. Кэш в памяти по regionId
 * (файл пакета на диске неизменен для данной версии: новая версия — новый каталог).
 *
 * Чистый JVM-код: Android не импортируется, гоняется unit-тестами.
 */
class BoundaryRepository {

    private val cache = HashMap<String, BoundaryResult.Ok>()

    /** Границы пакета (кэшируются); [BoundaryResult.Err] — только когда нет даже bbox. */
    @Synchronized
    fun load(pkg: InstalledPackage): BoundaryResult {
        cache[pkg.rootDir.absolutePath]?.let {
            WqLog.v(WqLayer.CACHE) { "boundaries HIT (memory) region=${pkg.regionId}" }
            return it
        }
        val result = loadFromDisk(pkg)
        if (result is BoundaryResult.Ok) cache[pkg.rootDir.absolutePath] = result
        WqLog.d(WqLayer.CACHE) {
            when (result) {
                is BoundaryResult.Ok ->
                    "boundaries MISS → disk region=${pkg.regionId} " +
                        "polygons=${result.boundary.polygons.size} degraded=${result.polygonsDegraded}"
                is BoundaryResult.Err ->
                    "boundaries MISS → disk FAILED region=${pkg.regionId}: ${result.error.message}"
            }
        }
        return result
    }

    /**
     * Районы пакета (4.3, достижения A8–A10): features boundaries.geojson с
     * properties.kind == "district". Что пакет даёт — то и считаем; в малых
     * городах без деления список пуст (районные достижения скрываются).
     * Кэш в памяти, как у [load].
     */
    @Synchronized
    fun loadDistricts(pkg: InstalledPackage): List<DistrictBoundary> {
        districtsCache[pkg.rootDir.absolutePath]?.let {
            WqLog.v(WqLayer.CACHE) { "districts HIT (memory) region=${pkg.regionId}" }
            return it
        }
        val districts = try {
            parseDistricts(pkg.boundariesFile.readText(), pkg.regionId)
        } catch (e: Exception) {
            emptyList()
        }
        districtsCache[pkg.rootDir.absolutePath] = districts
        WqLog.d(WqLayer.CACHE) {
            "districts MISS → disk region=${pkg.regionId} count=${districts.size}"
        }
        return districts
    }

    private val districtsCache = HashMap<String, List<DistrictBoundary>>()

    private fun parseDistricts(text: String, regionId: String): List<DistrictBoundary> {
        val root = PackageJson.parseToJsonElement(text).jsonObject
        val features = root["features"]?.jsonArray ?: return emptyList()
        val districts = ArrayList<DistrictBoundary>()
        for (feature in features) {
            val obj = feature as? JsonObject ?: continue
            val properties = obj["properties"] as? JsonObject ?: continue
            if (properties["kind"]?.jsonPrimitive?.content != "district") continue
            val name = properties["name"]?.jsonPrimitive?.content
            val geometry = obj["geometry"] as? JsonObject ?: continue
            val type = geometry["type"]?.jsonPrimitive?.content
            val coordinates = geometry["coordinates"] as? JsonArray ?: continue
            val polygons = ArrayList<GeoPolygon>()
            when (type) {
                "Polygon" -> polygons.add(GeoPolygon(ringsOf(coordinates)))
                "MultiPolygon" -> coordinates.forEach { polygon ->
                    polygons.add(GeoPolygon(ringsOf(polygon.jsonArray)))
                }
            }
            if (polygons.isNotEmpty()) districts.add(DistrictBoundary(regionId, name, polygons))
        }
        return districts
    }

    private fun loadFromDisk(pkg: InstalledPackage): BoundaryResult {
        val bbox = try {
            PackageJson.decodeFromString<PackageMeta>(pkg.metaFile.readText()).bbox
        } catch (e: Exception) {
            null
        }
        if (bbox == null || bbox.size != 4) {
            return BoundaryResult.Err(BoundaryError.NoBbox(pkg.regionId))
        }
        val polygons = try {
            parseGeoJson(pkg.boundariesFile.readText())
        } catch (e: Exception) {
            null
        }
        return BoundaryResult.Ok(
            RegionBoundary(pkg.regionId, bbox, polygons.orEmpty()),
            polygonsDegraded = polygons == null,
        )
    }

    /**
     * GeoJSON FeatureCollection → полигоны. Поддерживаются geometry Polygon и
     * MultiPolygon; точечные/линейные геометрии пропускаются (в boundaries.geojson
     * генератора их нет). Кольца берутся как есть (замкнутость не требуется —
     * ray casting работает по рёбрам между соседними вершинами).
     */
    private fun parseGeoJson(text: String): List<GeoPolygon> {
        val root = PackageJson.parseToJsonElement(text).jsonObject
        val features = root["features"]?.jsonArray ?: return emptyList()
        val polygons = ArrayList<GeoPolygon>()
        for (feature in features) {
            val geometry = (feature as? JsonObject)?.get("geometry") as? JsonObject ?: continue
            val type = geometry["type"]?.jsonPrimitive?.content
            val coordinates = geometry["coordinates"] as? JsonArray ?: continue
            when (type) {
                "Polygon" -> polygons.add(GeoPolygon(ringsOf(coordinates)))
                "MultiPolygon" -> coordinates.forEach { polygon ->
                    polygons.add(GeoPolygon(ringsOf(polygon.jsonArray)))
                }
            }
        }
        return polygons
    }

    /** Кольца полигона GeoJSON → плоские массивы [lon0, lat0, lon1, lat1, …]. */
    private fun ringsOf(polygon: JsonArray): List<DoubleArray> =
        polygon.map { ring ->
            val flat = DoubleArray(ring.jsonArray.size * 2)
            ring.jsonArray.forEachIndexed { i, point ->
                val pair = point.jsonArray
                flat[i * 2] = pair[0].jsonPrimitive.double
                flat[i * 2 + 1] = pair[1].jsonPrimitive.double
            }
            flat
        }
}

/** Результат чтения границ пакета (sealed-результат, не исключения через границы). */
sealed class BoundaryResult {
    /** Границы прочитаны; [polygonsDegraded] — boundaries.geojson битый, границы только по bbox. */
    data class Ok(val boundary: RegionBoundary, val polygonsDegraded: Boolean = false) : BoundaryResult()

    /** Нет даже bbox meta.json — регион для локатора недоступен. */
    data class Err(val error: BoundaryError) : BoundaryResult()
}

/** Типизированные ошибки data/boundaries. */
sealed class BoundaryError {
    abstract val message: String

    data class NoBbox(val regionId: String) : BoundaryError() {
        override val message get() = "В meta.json пакета региона «$regionId» нет корректного bbox"
    }
}

/** Район региона (4.3): полигоны из boundaries.geojson (kind=district); имя может отсутствовать. */
class DistrictBoundary(
    val regionId: String,
    val name: String?,
    val polygons: List<GeoPolygon>,
) {
    /** Bbox полигонов района (префильтр клеток сетки перед ray casting). */
    val bbox: DoubleArray = run {
        var minLon = Double.MAX_VALUE
        var minLat = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        for (polygon in polygons) {
            for (ring in polygon.rings) {
                var i = 0
                while (i < ring.size) {
                    if (ring[i] < minLon) minLon = ring[i]
                    if (ring[i] > maxLon) maxLon = ring[i]
                    if (ring[i + 1] < minLat) minLat = ring[i + 1]
                    if (ring[i + 1] > maxLat) maxLat = ring[i + 1]
                    i += 2
                }
            }
        }
        doubleArrayOf(minLon, minLat, maxLon, maxLat)
    }

    fun inBbox(latitude: Double, longitude: Double): Boolean =
        longitude >= bbox[0] && longitude <= bbox[2] && latitude >= bbox[1] && latitude <= bbox[3]
}
