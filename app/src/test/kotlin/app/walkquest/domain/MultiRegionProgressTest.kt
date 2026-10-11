package app.walkquest.domain

import app.walkquest.data.boundaries.BoundaryRepository
import app.walkquest.data.fog.FogRepository
import app.walkquest.data.fog.TestFog
import app.walkquest.data.packages.RegionPackageRepository
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Раздельность прогресса двух регионов (1.10, AD-9, verify FR-4 «прогресс не смешивается»):
 * точка открывает биты только своего региона — биткарты `fog/<regionId>.fog` раздельны
 * с 1.6; процент каждого региона считается по его пакету и его биткарте и не зависит
 * от открытий в соседнем регионе.
 */
class MultiRegionProgressTest {

    @get:Rule
    val temp = TemporaryFolder()

    // Второй регион-фикстура: bbox не пересекается с TestFog.BBOX (СПб).
    private val satelliteId = "satellite"
    private val satelliteBbox = listOf(29.5, 59.3, 29.9, 59.6)
    private val satelliteCols = 200
    private val satelliteRows = 170
    private val spbPoint = 59.05 to 30.05
    private val satellitePoint = 59.45 to 29.7

    private fun satelliteGrid(): app.walkquest.data.fog.FogGrid = TestFog.fogGrid(
        cols = satelliteCols,
        rows = satelliteRows,
        originLon = satelliteBbox[0],
        originLat = satelliteBbox[1],
        bbox = satelliteBbox,
    )

    /** Два установленных региона; проходимые тайлы — вокруг точек прогулок. */
    private fun setUp(): Triple<OpenTileAtPoint, ComputeRegionProgress, FogRepository> {
        val packagesRoot = File(temp.root, "packages")
        val spbTile = TestFog.fogGrid().indexAt(spbPoint.first, spbPoint.second)!!
        val satTile = satelliteGrid().indexAt(satellitePoint.first, satellitePoint.second)!!
        TestFog.installPackage(packagesRoot, walkable = setOf(spbTile, 0, 1, 2))
        TestFog.installPackage(
            packagesRoot,
            regionId = satelliteId,
            cols = satelliteCols,
            rows = satelliteRows,
            walkable = setOf(satTile, 0, 1),
            bbox = satelliteBbox,
            originLon = satelliteBbox[0],
            originLat = satelliteBbox[1],
        )
        val fog = FogRepository(File(temp.root, "fog"))
        val packages = RegionPackageRepository(packagesRoot)
        val boundaries = BoundaryRepository()
        return Triple(
            OpenTileAtPoint(fog, packages, boundaries),
            ComputeRegionProgress(fog, packages),
            fog,
        )
    }

    private fun fraction(progress: ComputeRegionProgress, regionId: String): Double =
        (progress.compute(regionId) as ProgressResult.Ok).progress.fraction

    @Test
    fun `opening in one region does not move the other's progress`() {
        val (opener, progress, _) = setUp()
        val satelliteBefore = fraction(progress, satelliteId)

        // Прогулка по СПб: бит открыт в spb-city, прогресс спутника не тронут.
        assertEquals(OpenResult.Changed(1, TestFog.REGION_ID), opener.openAt(spbPoint.first, spbPoint.second))
        assertEquals(0.25, fraction(progress, TestFog.REGION_ID), 1e-12) // 1/4 проходимых
        assertEquals(satelliteBefore, fraction(progress, satelliteId), 1e-12)

        // Поездка в город-спутник: бит открыт только там; СПб остался на 0.25.
        assertEquals(OpenResult.Changed(1, satelliteId), opener.openAt(satellitePoint.first, satellitePoint.second))
        assertEquals(0.25, fraction(progress, TestFog.REGION_ID), 1e-12)
        assertEquals(1.0 / 3.0, fraction(progress, satelliteId), 1e-12)
    }

    @Test
    fun `bitmaps are stored per region`() {
        val (opener, _, fog) = setUp()
        opener.openAt(spbPoint.first, spbPoint.second)
        opener.openAt(satellitePoint.first, satellitePoint.second)

        val spbBitmap = (fog.load(TestFog.REGION_ID, TestFog.fogGrid()) as app.walkquest.data.fog.BitmapResult.Ok).bitmap
        val satBitmap = (fog.load(satelliteId, satelliteGrid()) as app.walkquest.data.fog.BitmapResult.Ok).bitmap
        assertEquals(1, spbBitmap.openedCount)
        assertEquals(1, satBitmap.openedCount)
        // Бит спутника не попал в биткарту СПб и наоборот.
        val spbTile = TestFog.fogGrid().indexAt(spbPoint.first, spbPoint.second)!!
        val satTile = satelliteGrid().indexAt(satellitePoint.first, satellitePoint.second)!!
        assertTrue(spbBitmap.isOpen(spbTile))
        assertTrue(satBitmap.isOpen(satTile))
    }

    @Test
    fun `point between regions opens nothing`() {
        val (opener, progress, _) = setUp()
        // Точка между bbox двух регионов — вне границ обоих пакетов.
        assertEquals(OpenResult.OutOfRegion, opener.openAt(59.45, 30.0))
        assertEquals(0.0, fraction(progress, TestFog.REGION_ID), 1e-12)
        assertEquals(0.0, fraction(progress, satelliteId), 1e-12)
    }
}
