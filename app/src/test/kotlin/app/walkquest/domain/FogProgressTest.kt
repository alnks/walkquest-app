package app.walkquest.domain

import app.walkquest.data.fog.FogRepository
import app.walkquest.data.fog.OpenBitResult
import app.walkquest.data.fog.TestFog
import app.walkquest.data.packages.RegionPackageRepository
import java.io.File
import java.util.BitSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Процент открытия региона (AD-4: вычисляется только в domain, FR-2).
 * Вычислитель: доля = открытые проходимые / все проходимые пакета — по пересечению
 * снимка открытых битов с проходимой маской; пустой знаменатель → 0; порядок
 * открытий роли не играет. Use-case: снимок через пакет-фикстуру, NoRegion/Failure.
 */
class FogProgressTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun maskOf(vararg indices: Int): BitSet =
        BitSet().apply { indices.forEach(::set) }

    // --- Чистый вычислитель (матрица краевых случаев) ---

    @Test
    fun `empty region yields zero`() {
        val progress = FogProgress.compute(emptySet(), maskOf(0, 1, 2, 3))
        assertEquals(0.0, progress.fraction, 1e-12)
        assertEquals(0, progress.openedWalkable)
        assertEquals(4, progress.walkableTotal)
    }

    @Test
    fun `partial opening yields opened walkable over total walkable`() {
        val progress = FogProgress.compute(setOf(0, 2), maskOf(0, 1, 2, 3))
        assertEquals(0.5, progress.fraction, 1e-12)
        assertEquals(2, progress.openedWalkable)
        assertEquals(4, progress.walkableTotal)
    }

    @Test
    fun `full opening yields one`() {
        val progress = FogProgress.compute(setOf(0, 1, 2, 3), maskOf(0, 1, 2, 3))
        assertEquals(1.0, progress.fraction, 1e-12)
    }

    @Test
    fun `opened bits outside walkable mask do not inflate the fraction`() {
        // Открыты биты 0 (проходимый) и 99 (вне маски): числитель — только пересечение.
        val progress = FogProgress.compute(setOf(0, 99), maskOf(0, 1, 2, 3))
        assertEquals(0.25, progress.fraction, 1e-12)
        assertEquals(1, progress.openedWalkable)
        // Полное открытие маски + лишние биты снаружи — ровно 1.0, не больше.
        val over = FogProgress.compute(setOf(0, 1, 2, 3, 4, 5, 1000), maskOf(0, 1, 2, 3))
        assertEquals(1.0, over.fraction, 1e-12)
    }

    @Test
    fun `empty walkable mask yields zero without division by zero`() {
        val progress = FogProgress.compute(setOf(0, 1), BitSet())
        assertEquals(0.0, progress.fraction, 1e-12)
        assertEquals(0, progress.walkableTotal)
    }

    @Test
    fun `same opened set in any insertion order yields same fraction`() {
        val direct = FogProgress.compute(setOf(3, 1, 0), maskOf(0, 1, 2, 3))
        val shuffled = FogProgress.compute(linkedSetOf(1, 3, 0), maskOf(3, 2, 1, 0))
        assertEquals(direct.fraction, shuffled.fraction, 1e-12)
        assertEquals(0.75, direct.fraction, 1e-12)
    }

    // --- Use-case через пакет-фикстуру (общий путь с OpenTileAtPoint) ---

    private fun useCase(
        walkable: Set<Int>,
        withPackage: Boolean = true,
    ): Pair<ComputeRegionProgress, FogRepository> {
        val packagesRoot = File(temp.root, "packages")
        if (withPackage) TestFog.installPackage(packagesRoot, walkable = walkable)
        val fog = FogRepository(File(temp.root, "fog"))
        val packages = RegionPackageRepository(packagesRoot)
        return ComputeRegionProgress(fog, packages) to fog
    }

    @Test
    fun `use-case computes fraction from package mask and opened bitmap`() {
        val walkable = setOf(0, 1, 2, 3)
        val (useCase, fog) = useCase(walkable)
        val grid = TestFog.fogGrid()

        // Пустой регион → 0.
        val initial = useCase.compute(TestFog.REGION_ID) as ProgressResult.Ok
        assertEquals(0.0, initial.progress.fraction, 1e-12)
        assertEquals(4, initial.progress.walkableTotal)

        // Открытие проходимого бита (тот же путь, что OpenTileAtPoint → FogRepository.open).
        assertEquals(OpenBitResult.Changed(1), fog.open(TestFog.REGION_ID, grid, 2))
        val partial = useCase.compute(TestFog.REGION_ID) as ProgressResult.Ok
        assertEquals(0.25, partial.progress.fraction, 1e-12)

        // Открытие бита вне маски процент не меняет.
        assertEquals(OpenBitResult.Changed(2), fog.open(TestFog.REGION_ID, grid, 42))
        val afterOutside = useCase.compute(TestFog.REGION_ID) as ProgressResult.Ok
        assertEquals(0.25, afterOutside.progress.fraction, 1e-12)

        // Все проходимые открыты → 1.
        fog.open(TestFog.REGION_ID, grid, 0)
        fog.open(TestFog.REGION_ID, grid, 1)
        fog.open(TestFog.REGION_ID, grid, 3)
        val full = useCase.compute(TestFog.REGION_ID) as ProgressResult.Ok
        assertEquals(1.0, full.progress.fraction, 1e-12)
    }

    @Test
    fun `opening tile through OpenTileAtPoint moves the same progress`() {
        // Единый путь: и тестовая точка, и трек (1.7) открывают биты через OpenTileAtPoint —
        // прогресс обязан сдвинуться предсказуемо на 1/знаменатель.
        val grid = TestFog.fogGrid()
        val tileIndex = grid.indexAt(59.05, 30.05)!!
        val (useCase, fog) = useCase(walkable = setOf(tileIndex, 0, 1))
        val packages = RegionPackageRepository(File(temp.root, "packages"))
        val opener = OpenTileAtPoint(fog, packages, app.walkquest.data.boundaries.BoundaryRepository())

        val before = (useCase.compute(TestFog.REGION_ID) as ProgressResult.Ok).progress.fraction
        assertEquals(OpenResult.Changed(1, TestFog.REGION_ID), opener.openAt(59.05, 30.05))
        val after = (useCase.compute(TestFog.REGION_ID) as ProgressResult.Ok).progress
        assertEquals(before + 1.0 / 3.0, after.fraction, 1e-12)
        assertEquals(1, after.openedWalkable)
    }

    @Test
    fun `missing package yields NoRegion`() {
        val (useCase, _) = useCase(walkable = emptySet(), withPackage = false)
        assertEquals(ProgressResult.NoRegion, useCase.compute(TestFog.REGION_ID))
    }

    @Test
    fun `broken walkability grid yields Failure`() {
        val (useCase, _) = useCase(walkable = setOf(0))
        File(temp.root, "packages/${TestFog.REGION_ID}/v1/walkability.grid")
            .writeBytes("мусор, не формат".toByteArray())

        val result = useCase.compute(TestFog.REGION_ID)
        assertTrue(result is ProgressResult.Failure)
    }

    @Test
    fun `walkableCount mismatch with mask payload yields Failure`() {
        // Контроль целостности знаменателя: заголовок врёт про число проходимых битов.
        val packagesRoot = File(temp.root, "packages")
        val dir = TestFog.installPackage(packagesRoot, walkable = setOf(0, 1))
        val broken = TestFog.gridHeaderJson(walkableCount = 5) + "\n"
        val payload = ByteArray((TestFog.COLS * TestFog.ROWS + 7) / 8)
        payload[0] = 0b11
        File(dir, "walkability.grid").writeBytes(broken.toByteArray() + payload)

        val fog = FogRepository(File(temp.root, "fog"))
        val useCase = ComputeRegionProgress(fog, RegionPackageRepository(packagesRoot))
        assertTrue(useCase.compute(TestFog.REGION_ID) is ProgressResult.Failure)
    }
}
