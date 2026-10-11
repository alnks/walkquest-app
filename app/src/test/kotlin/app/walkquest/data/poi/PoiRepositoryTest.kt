package app.walkquest.data.poi

import app.walkquest.data.packages.InstalledPackage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Загрузка poi.json установленного пакета (история 1.9, FR-18, AD-2):
 * формат генератора 1.3 (snake_case, nullable-поля), кэш в памяти,
 * битый файл — типизированная ошибка без исключений через границу.
 */
class PoiRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** Раскладывает «установленный пакет»: packages/<regionId>/v<N>/poi.json. */
    private fun installPackage(regionId: String = "spb-city", version: Int = 1, poiJson: String): InstalledPackage {
        val dir = File(temp.root, "packages/$regionId/v$version")
        dir.mkdirs()
        File(dir, "poi.json").writeText(poiJson)
        return InstalledPackage(regionId, version, formatVersion = 1, rootDir = dir)
    }

    private val sampleJson = """
        {"pois": [
          {"osm_id": 80317281, "name": "М. И. Калинин", "name_ru": null,
           "category": "historic=memorial", "lon": 30.3861357, "lat": 59.9702963,
           "wikidata": "Q101093708", "description": "Памятник М. И. Калинину в Санкт-Петербурге."},
          {"osm_id": 21751966, "name": "Озёрная", "name_ru": "Озёрная",
           "category": "tourism=attraction", "lon": 30.2964048, "lat": 60.0392004,
           "wikidata": null, "description": null},
          {"osm_id": "w5052616", "name": "Петровский пруд", "name_ru": null,
           "category": "natural=water", "lon": 30.27546276365964, "lat": 59.95554715,
           "wikidata": null, "description": null}
        ]}
    """.trimIndent()

    @Test
    fun `parses poi json of the generator format`() {
        val pkg = installPackage(poiJson = sampleJson)
        val result = PoiRepository().loadPois(pkg)

        val ok = result as PoiResult.Ok
        assertEquals(3, ok.pois.size)
        val first = ok.pois[0]
        // osm_id узла — число в JSON, нормализуется в строку (как у путей «w…»).
        assertEquals("80317281", first.osmId)
        assertEquals("М. И. Калинин", first.name)
        assertNull(first.nameRu)
        assertEquals("historic=memorial", first.category)
        assertEquals(30.3861357, first.lon, 1e-9)
        assertEquals(59.9702963, first.lat, 1e-9)
        assertEquals("Q101093708", first.wikidata)
        assertEquals("Памятник М. И. Калинину в Санкт-Петербурге.", first.description)
        val second = ok.pois[1]
        assertNull(second.wikidata)
        assertNull(second.description)
        assertEquals("Озёрная", second.displayName)
    }

    @Test
    fun `way osm ids parse as strings (mixed format of the real package)`() {
        val pkg = installPackage(poiJson = sampleJson)
        val ok = PoiRepository().loadPois(pkg) as PoiResult.Ok
        assertEquals("w5052616", ok.pois[2].osmId)
    }

    @Test
    fun `unknown fields are ignored like in meta json`() {
        val json = """{"pois": [{"osm_id": 1, "name": null, "name_ru": null,
            "category": "shop=outpost", "lon": 30.0, "lat": 59.0,
            "wikidata": null, "description": null, "future_field": 42}], "future_root": {}}"""
        val result = PoiRepository().loadPois(installPackage(poiJson = json))
        assertEquals(1, (result as PoiResult.Ok).pois.size)
    }

    @Test
    fun `broken poi json yields typed error without exceptions`() {
        val pkg = installPackage(poiJson = "not json at all")
        val result = PoiRepository().loadPois(pkg)

        val err = result as PoiResult.Err
        val error = err.error as PoiError.Invalid
        assertEquals("spb-city", error.regionId)
        assertTrue(error.message.contains("spb-city"))
    }

    @Test
    fun `missing poi file yields typed error`() {
        val dir = File(temp.root, "packages/spb-city/v1").apply { mkdirs() }
        val pkg = InstalledPackage("spb-city", 1, formatVersion = 1, rootDir = dir)
        assertTrue(PoiRepository().loadPois(pkg) is PoiResult.Err)
    }

    @Test
    fun `photo field of package v2 is parsed, absent in v1 stays null`() {
        val json = """{"pois": [
            {"osm_id": 1, "name": "С фото", "name_ru": null,
             "category": "tourism=museum", "lon": 30.0, "lat": 59.0,
             "wikidata": null, "description": null, "photo": "photos/poi-1.jpg"},
            {"osm_id": 2, "name": "Без фото", "name_ru": null,
             "category": "tourism=museum", "lon": 30.1, "lat": 59.1,
             "wikidata": null, "description": null}
        ]}"""
        val ok = PoiRepository().loadPois(installPackage(poiJson = json)) as PoiResult.Ok
        assertEquals("photos/poi-1.jpg", ok.pois[0].photo)
        assertNull(ok.pois[1].photo) // обратная совместимость со старым пакетом v1
    }

    @Test
    fun `url field of package v5 is parsed, absent in v4 stays null`() {
        val json = """{"pois": [
            {"osm_id": 1, "name": "Со ссылкой", "name_ru": null,
             "category": "tourism=museum", "lon": 30.0, "lat": 59.0,
             "wikidata": "Q123", "description": "Длинное описание.",
             "url": "https://ru.wikipedia.org/wiki/Example"},
            {"osm_id": 2, "name": "Пакет v4", "name_ru": null,
             "category": "tourism=museum", "lon": 30.1, "lat": 59.1,
             "wikidata": null, "description": null}
        ]}"""
        val ok = PoiRepository().loadPois(installPackage(poiJson = json)) as PoiResult.Ok
        assertEquals("https://ru.wikipedia.org/wiki/Example", ok.pois[0].url)
        // Обратная совместимость: в poi.json пакета v4 поля url нет — null, без ошибок.
        assertNull(ok.pois[1].url)
    }

    @Test
    fun `second load comes from memory cache, not disk`() {
        val pkg = installPackage(poiJson = sampleJson)
        val repository = PoiRepository()
        val first = repository.loadPois(pkg) as PoiResult.Ok

        // Файл «испортился» после первой загрузки — кэш всё равно отдаёт данные (офлайн).
        File(pkg.rootDir, "poi.json").writeText("corrupted")
        val second = repository.loadPois(pkg) as PoiResult.Ok
        assertSame(first.pois, second.pois)
        assertEquals(3, second.pois.size)
    }

    @Test
    fun `cache is keyed by region and package version`() {
        val repository = PoiRepository()
        val v1 = installPackage(version = 1, poiJson = sampleJson)
        val v2 = installPackage(version = 2, poiJson = """{"pois": []}""")
        assertEquals(3, (repository.loadPois(v1) as PoiResult.Ok).pois.size)
        assertEquals(0, (repository.loadPois(v2) as PoiResult.Ok).pois.size)
    }
}
