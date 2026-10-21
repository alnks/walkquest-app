package app.walkquest.data.poi

import app.walkquest.data.packages.InstalledPackage
import app.walkquest.data.packages.PackageJson
import app.walkquest.logging.WqLayer
import app.walkquest.logging.WqLog

/** Типизированные ошибки слоя POI (Consistency Conventions: sealed-результаты через границы). */
sealed class PoiError {
    abstract val message: String

    data class Invalid(val regionId: String, val detail: String) : PoiError() {
        override val message get() = "poi.json региона «$regionId» повреждён или имеет неожиданный формат: $detail"
    }
}

/** Результат загрузки POI — единственный тип возврата через границу data → viewmodel. */
sealed class PoiResult {
    data class Ok(val pois: List<Poi>) : PoiResult()
    data class Err(val error: PoiError) : PoiResult()
}

/**
 * Репозиторий POI (история 1.9, FR-18, AD-2).
 *
 * Единственный источник — poi.json установленного пакета региона: читается один раз
 * с диска (офлайн после установки 1.5, сеть не нужна), разобранный список кэшируется
 * в памяти по regionId+packageVersion. Битый файл — типизированная ошибка, исключения
 * через границу не летят, карта и туман продолжают работать без POI.
 *
 * Объём пакета (~58 580 записей, 11 МБ JSON) держится в памяти целиком — решение
 * зафиксировано в плане истории; давление памяти меряет гейт 1.12.
 *
 * Чистый JVM-код (AD-10): Android не импортируется, локальные unit-тесты гоняют класс.
 */
class PoiRepository {

    private val cache = HashMap<String, List<Poi>>()

    /** Читает и парсит poi.json установленного пакета; повторные вызовы — из кэша. */
    fun loadPois(pkg: InstalledPackage): PoiResult {
        val key = "${pkg.regionId}@${pkg.packageVersion}"
        cache[key]?.let {
            WqLog.v(WqLayer.CACHE) { "poi HIT (memory) key=$key count=${it.size}" }
            return PoiResult.Ok(it)
        }
        return try {
            val parsed = PackageJson.decodeFromString<PoiFile>(pkg.poiFile.readText())
            val pois = parsed.pois.map { it.toPoi() }
            cache[key] = pois
            WqLog.d(WqLayer.CACHE) { "poi MISS → disk parse key=$key count=${pois.size}" }
            PoiResult.Ok(pois)
        } catch (e: Exception) {
            WqLog.w(WqLayer.CACHE) { "poi MISS → parse FAILED key=$key: ${e.message}" }
            PoiResult.Err(PoiError.Invalid(pkg.regionId, e.message ?: e.toString()))
        }
    }
}
