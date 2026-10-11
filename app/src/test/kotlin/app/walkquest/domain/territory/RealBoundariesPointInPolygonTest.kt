package app.walkquest.domain.territory

import app.walkquest.data.boundaries.BoundaryRepository
import app.walkquest.data.boundaries.BoundaryResult
import app.walkquest.data.packages.InstalledPackage
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Модель территории против фактических boundaries.geojson обоих пакетов (1.10, AD-9):
 * центр СПб принадлежит spb-city, центр Гатчины — gatchina, кросс-принадлежности нет
 * (Гатчина — отдельный город, не часть СПб). Тест пропускается (Assume), если zip-пакеты
 * не лежат в tools/region-pack/out (они генерируются скриптом 1.3, в репозиторий не входят).
 * Независимое зеркало той же проверки — _qa/check-1-10-territory.py.
 */
class RealBoundariesPointInPolygonTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val repository = BoundaryRepository()
    private lateinit var spb: RegionBoundary
    private lateinit var gatchina: RegionBoundary

    @Before
    fun setUp() {
        spb = loadFromZip(SPB_ZIP, "spb-city")
        gatchina = loadFromZip(GATCHINA_ZIP, "gatchina")
    }

    /** Распаковывает meta.json + boundaries.geojson пакета и читает границы боевым репозиторием. */
    private fun loadFromZip(path: String, regionId: String): RegionBoundary {
        val zipFile = File(path)
        assumeTrue("пакет $regionId не сгенерирован: $path", zipFile.isFile)
        val dir = temp.newFolder(regionId)
        ZipFile(zipFile).use { zip ->
            for (name in listOf("meta.json", "boundaries.geojson")) {
                zip.getInputStream(zip.getEntry(name)).use { input ->
                    File(dir, name).writeBytes(input.readBytes())
                }
            }
        }
        val pkg = InstalledPackage(regionId, packageVersion = 1, formatVersion = 1, rootDir = dir)
        val result = repository.load(pkg)
        assumeTrue("границы $regionId не прочитались", result is BoundaryResult.Ok)
        val ok = result as BoundaryResult.Ok
        assertFalse("boundaries.geojson $regionId деградировал до bbox", ok.polygonsDegraded)
        assertTrue("в boundaries.geojson $regionId нет полигонов", ok.boundary.polygons.isNotEmpty())
        return ok.boundary
    }

    @Test
    fun `spb center belongs to spb-city among both regions`() {
        assertEquals(
            "spb-city",
            RegionLocator.locate(SPB_CENTER_LAT, SPB_CENTER_LON, listOf(spb, gatchina)),
        )
    }

    @Test
    fun `gatchina center belongs to gatchina among both regions`() {
        assertEquals(
            "gatchina",
            RegionLocator.locate(GATCHINA_CENTER_LAT, GATCHINA_CENTER_LON, listOf(spb, gatchina)),
        )
    }

    @Test
    fun `gatchina center is outside spb-city region (AD-9 — не часть СПб)`() {
        // Семантика локатора (bbox пакета + полигоны): центр Гатчины южнее bbox СПб.
        assertEquals(null, RegionLocator.locate(GATCHINA_CENTER_LAT, GATCHINA_CENTER_LON, listOf(spb)))
    }

    @Test
    fun `spb center is outside gatchina region`() {
        // В boundaries.geojson Гатчины есть отношение «Санкт-Петербург» (admin 4) с геометрией
        // за пределами bbox пакета — регион решает bbox-префильтр локатора, не сырой полигон.
        assertEquals(null, RegionLocator.locate(SPB_CENTER_LAT, SPB_CENTER_LON, listOf(gatchina)))
    }

    private companion object {
        const val SPB_ZIP = "../tools/region-pack/out/region-spb-city-v1.zip"
        const val GATCHINA_ZIP = "../tools/region-pack/out/region-gatchina-v1.zip"
        const val SPB_CENTER_LAT = 59.9343
        const val SPB_CENTER_LON = 30.3351
        const val GATCHINA_CENTER_LAT = 59.5653
        const val GATCHINA_CENTER_LON = 30.1282
    }
}
