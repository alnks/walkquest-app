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
 * Открытие тайла тумана через domain (AD-4: единственный путь): точка → тайл сетки
 * установленного пакета → персистентный бит. Повтор идемпотентен, точка вне региона
 * и отсутствие пакета не трогают состояние.
 */
class OpenTileAtPointTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun useCase(withPackage: Boolean = true): Pair<OpenTileAtPoint, FogRepository> {
        val packagesRoot = File(temp.root, "packages")
        if (withPackage) TestFog.installPackage(packagesRoot)
        val fog = FogRepository(File(temp.root, "fog"))
        val packages = RegionPackageRepository(packagesRoot)
        return OpenTileAtPoint(fog, packages, BoundaryRepository()) to fog
    }

    @Test
    fun `point opens fog tile bit through repository`() {
        val (useCase, fog) = useCase()

        val result = useCase.openAt(59.05, 30.05)

        assertEquals(OpenResult.Changed(1, TestFog.REGION_ID), result)
        // Индекс по формулам генератора: row=floor(0.05/dy), col=floor(0.05/dx), index=row*cols+col.
        val grid = TestFog.fogGrid()
        val expectedIndex = grid.indexAt(59.05, 30.05)!!
        assertTrue(expectedIndex > 0)
        val bitmap = (fog.load(TestFog.REGION_ID, grid) as app.walkquest.data.fog.BitmapResult.Ok).bitmap
        assertTrue(bitmap.isOpen(expectedIndex))
        assertEquals(setOf(expectedIndex), bitmap.openedIndices())
    }

    @Test
    fun `opening the same tile twice is idempotent`() {
        val (useCase, _) = useCase()

        assertEquals(OpenResult.Changed(1, TestFog.REGION_ID), useCase.openAt(59.05, 30.05))
        assertEquals(OpenResult.Unchanged(TestFog.REGION_ID), useCase.openAt(59.05, 30.05))
    }

    @Test
    fun `opened bit survives repository recreation`() {
        val (useCase, _) = useCase()
        useCase.openAt(59.05, 30.05)

        // «Перезапуск приложения»: новые репозитории на тех же файлах.
        val (restarted, fog) = useCase()
        assertEquals(OpenResult.Unchanged(TestFog.REGION_ID), restarted.openAt(59.05, 30.05))
        val bitmap = (fog.load(TestFog.REGION_ID, TestFog.fogGrid()) as app.walkquest.data.fog.BitmapResult.Ok).bitmap
        assertEquals(1, bitmap.openedCount)
    }

    @Test
    fun `point outside region opens nothing`() {
        val (useCase, fog) = useCase()

        assertEquals(OpenResult.OutOfRegion, useCase.openAt(58.0, 30.0))
        assertEquals(OpenResult.OutOfRegion, useCase.openAt(59.05, 29.0))

        val bitmap = (fog.load(TestFog.REGION_ID, TestFog.fogGrid()) as app.walkquest.data.fog.BitmapResult.Ok).bitmap
        assertEquals(0, bitmap.openedCount)
    }

    @Test
    fun `missing package yields NoRegion`() {
        val (useCase, _) = useCase(withPackage = false)
        assertEquals(OpenResult.NoRegion, useCase.openAt(59.05, 30.05))
    }
}
