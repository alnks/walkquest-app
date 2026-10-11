package app.walkquest.domain.territory

import app.walkquest.data.boundaries.BoundaryRepository
import app.walkquest.data.fog.TestFog
import app.walkquest.data.packages.RegionPackageRepository
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Детект «локация в неустановленном регионе» (1.10, FR-4): точка покрыта
 * установленным пакетом → подсказки нет; точка вне установленных, но в bbox
 * записи манифест-кэша → предложение загрузить её регион; установленные регионы
 * не предлагаются; нет кэша/bbox → нет подсказки.
 */
class DetectForeignRegionTest {

    @get:Rule
    val temp = TemporaryFolder()

    // Второй регион-фикстура не пересекается bbox с TestFog.BBOX (СПб).
    private val satelliteBbox = listOf(29.5, 59.3, 29.9, 59.6)
    private val satelliteId = "satellite"
    private val satellitePoint = 59.45 to 29.7

    private fun packagesRoot(): File = File(temp.root, "packages")

    private fun installSpb() {
        TestFog.installPackage(packagesRoot())
    }

    private fun installSatellite() {
        TestFog.installPackage(
            packagesRoot(),
            regionId = satelliteId,
            cols = 200,
            rows = 170,
            bbox = satelliteBbox,
            originLon = satelliteBbox[0],
            originLat = satelliteBbox[1],
        )
    }

    /** Манифест-кэш (как пишет RegionPackageRepository после скачивания манифеста). */
    private fun writeManifestCache(withBbox: Boolean = true) {
        val bboxFields = if (withBbox) {
            """"bbox":[${TestFog.BBOX.joinToString(",")}],"name":"Санкт-Петербург""""
        } else {
            ""
        }
        val satelliteFields = if (withBbox) {
            """"bbox":[${satelliteBbox.joinToString(",")}],"name":"Город-спутник""""
        } else {
            ""
        }
        File(packagesRoot(), "manifest-cache.json").writeText(
            """{"baseUrl":"https://example.test","packages":[""" +
                """{"regionId":"${TestFog.REGION_ID}","packageVersion":1,"formatVersion":1,""" +
                """"file":"a.zip","sizeBytes":1,"sha256":"x",$bboxFields},""" +
                """{"regionId":"$satelliteId","packageVersion":1,"formatVersion":1,""" +
                """"file":"b.zip","sizeBytes":1,"sha256":"y",$satelliteFields}]}""",
        )
    }

    private fun useCase(): DetectForeignRegion =
        DetectForeignRegion(RegionPackageRepository(packagesRoot()), BoundaryRepository())

    @Test
    fun `point inside installed region yields no suggestion`() {
        installSpb()
        writeManifestCache()
        assertNull(useCase().check(59.05, 30.05))
        assertNull(useCase().check(59.9343, 30.3351))
    }

    @Test
    fun `point in manifest bbox of not-installed region yields suggestion`() {
        installSpb()
        writeManifestCache()
        val (lat, lon) = satellitePoint
        val suggestion = useCase().check(lat, lon)
        assertEquals(satelliteId, suggestion?.regionId)
        assertEquals("Город-спутник", suggestion?.name)
    }

    @Test
    fun `already installed region is never suggested`() {
        installSpb()
        installSatellite()
        writeManifestCache()
        val (lat, lon) = satellitePoint
        assertNull(useCase().check(lat, lon))
    }

    @Test
    fun `point unknown to manifest yields no suggestion`() {
        installSpb()
        writeManifestCache()
        assertNull(useCase().check(0.0, 0.0))
        assertNull(useCase().check(55.75, 37.61)) // Москва — не в манифесте
    }

    @Test
    fun `missing manifest cache yields no suggestion`() {
        installSpb()
        val (lat, lon) = satellitePoint
        assertNull(useCase().check(lat, lon))
    }

    @Test
    fun `manifest entries without bbox never suggest`() {
        installSpb()
        writeManifestCache(withBbox = false)
        val (lat, lon) = satellitePoint
        assertNull(useCase().check(lat, lon))
    }
}
