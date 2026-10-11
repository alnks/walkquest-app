package app.walkquest.data.poi

import app.walkquest.data.packages.InstalledPackage
import app.walkquest.data.packages.PackageJson

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
        cache[key]?.let { return PoiResult.Ok(it) }
        return try {
            val parsed = PackageJson.decodeFromString<PoiFile>(pkg.poiFile.readText())
            val pois = parsed.pois.map { it.toPoi() }
            cache[key] = pois
            PoiResult.Ok(pois)
        } catch (e: Exception) {
            PoiResult.Err(PoiError.Invalid(pkg.regionId, e.message ?: e.toString()))
        }
    }
}
