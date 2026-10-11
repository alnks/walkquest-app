package app.walkquest.data.boundaries

import app.walkquest.data.fog.TestFog
import app.walkquest.data.packages.RegionPackageRepository
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Парсинг boundaries.geojson пакета (1.10, AD-9): Polygon/MultiPolygon → голые кольца
 * для domain; битый файл границ → деградация до bbox (туман жив); отсутствие bbox
 * в meta.json — единственный отказ.
 */
class BoundaryRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun installed(): app.walkquest.data.packages.InstalledPackage {
        val packagesRoot = File(temp.root, "packages")
        TestFog.installPackage(packagesRoot)
        return RegionPackageRepository(packagesRoot).getInstalled(TestFog.REGION_ID)!!
    }

    @Test
    fun `parses package boundaries into region boundary`() {
        val result = BoundaryRepository().load(installed())
        assertTrue(result is BoundaryResult.Ok)
        val ok = result as BoundaryResult.Ok
        assertFalse(ok.polygonsDegraded)
        assertEquals(TestFog.REGION_ID, ok.boundary.regionId)
        assertEquals(TestFog.BBOX, ok.boundary.bbox)
        assertEquals(1, ok.boundary.polygons.size)
        assertEquals(1, ok.boundary.polygons[0].rings.size) // одно внешнее кольцо
        assertEquals(10, ok.boundary.polygons[0].rings[0].size) // 5 точек × 2 координаты
    }

    @Test
    fun `broken boundaries file degrades to bbox-only`() {
        val pkg = installed()
        pkg.boundariesFile.writeText("мусор, не geojson")

        val result = BoundaryRepository().load(pkg)
        assertTrue(result is BoundaryResult.Ok)
        val ok = result as BoundaryResult.Ok
        assertTrue(ok.polygonsDegraded)
        assertTrue(ok.boundary.polygons.isEmpty())
        assertEquals(TestFog.BBOX, ok.boundary.bbox)
    }

    @Test
    fun `missing meta bbox is the only hard error`() {
        val pkg = installed()
        pkg.metaFile.writeText("""{"formatVersion":1,"regionId":"${TestFog.REGION_ID}","packageVersion":1}""")

        val result = BoundaryRepository().load(pkg)
        assertTrue(result is BoundaryResult.Err)
        assertTrue((result as BoundaryResult.Err).error is BoundaryError.NoBbox)
    }

    @Test
    fun `multipolygon geojson flattens to polygons`() {
        val pkg = installed()
        pkg.boundariesFile.writeText(
            """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"kind":"city"},""" +
                """"geometry":{"type":"MultiPolygon","coordinates":[[[[30.0,59.0],[30.3,59.0],[30.3,59.3],[30.0,59.3],[30.0,59.0]]],""" +
                """[[[30.4,59.4],[30.6,59.4],[30.6,59.6],[30.4,59.6],[30.4,59.4]]]]}}]}""",
        )
        val ok = BoundaryRepository().load(pkg) as BoundaryResult.Ok
        assertEquals(2, ok.boundary.polygons.size)
    }
}
