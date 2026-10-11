package app.walkquest.data.fog

import app.walkquest.data.packages.RegionPackageRepository
import java.io.File
import java.util.BitSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Водяная маска пакета (разбор владельца 2026-10-05: «вода видна сквозь туман»):
 * опциональный water.grid (формат v0, бит 1 = вода), слияние в прогоны/границы,
 * обратная совместимость с пакетами без файла.
 */
class WaterMaskTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun maskOf(cols: Int, rows: Int, water: Set<Int>): BitSet {
        val mask = BitSet(cols * rows)
        water.forEach { mask.set(it) }
        return mask
    }

    // --- Слияние в прогоны (дырки завесы) ---

    @Test
    fun `empty mask gives no runs`() {
        assertEquals(emptyList<RowRun>(), BitSet().toRowRuns(cols = 10, tileCount = 100))
    }

    @Test
    fun `contiguous tiles in one row merge into one run`() {
        // Сетка 10×10: тайлы (row=2, col=3..5) — индексы 23, 24, 25.
        val runs = maskOf(10, 10, setOf(23, 24, 25)).toRowRuns(cols = 10, tileCount = 100)
        assertEquals(listOf(RowRun(row = 2, colStart = 3, colEnd = 5)), runs)
    }

    @Test
    fun `tiles in different rows and gaps stay separate runs`() {
        // (0,0..1) — индексы 0,1; (0,3) — индекс 3; (1,0) — индекс 10.
        val runs = maskOf(10, 10, setOf(0, 1, 3, 10)).toRowRuns(cols = 10, tileCount = 100)
        assertEquals(
            listOf(RowRun(0, 0, 1), RowRun(0, 3, 3), RowRun(1, 0, 0)),
            runs,
        )
    }

    @Test
    fun `run does not cross row boundary`() {
        // Индексы 9 (row 0, col 9) и 10 (row 1, col 0) подряд в битпаке, но не соседи в сетке.
        val runs = maskOf(10, 10, setOf(9, 10)).toRowRuns(cols = 10, tileCount = 100)
        assertEquals(listOf(RowRun(0, 9, 9), RowRun(1, 0, 0)), runs)
    }

    // --- Граница воды слитыми отрезками (мягкая кромка) ---

    @Test
    fun `single water tile gives its four edges merged into segments`() {
        // Сетка 10×10, один тайл (row=2, col=3) — индекс 23.
        val edges = maskOf(10, 10, setOf(23)).toBoundaryEdges(cols = 10, rows = 10)
        assertEquals(
            setOf(
                GridEdge(3, 2, 3, 3), // западная сторона (вертикаль)
                GridEdge(4, 2, 4, 3), // восточная сторона (вертикаль)
                GridEdge(3, 2, 4, 2), // южная сторона (горизонталь)
                GridEdge(3, 3, 4, 3), // северная сторона (горизонталь)
            ),
            edges.toSet(),
        )
    }

    @Test
    fun `adjacent water tiles share no internal edges`() {
        // Квадрат 2×2: (1,1),(1,2),(2,1),(2,2) — индексы 11, 12, 21, 22.
        val edges = maskOf(10, 10, setOf(11, 12, 21, 22)).toBoundaryEdges(cols = 10, rows = 10)
        // Внутренних границ нет; внешний контур — 4 слитых отрезка по 2 ячейки.
        assertEquals(
            setOf(
                GridEdge(1, 1, 1, 3),
                GridEdge(3, 1, 3, 3),
                GridEdge(1, 1, 3, 1),
                GridEdge(1, 3, 3, 3),
            ),
            edges.toSet(),
        )
    }

    @Test
    fun `water at grid edge treats outside as land`() {
        // Тайл (0,0) — граница проходит и по краю сетки.
        val edges = maskOf(5, 5, setOf(0)).toBoundaryEdges(cols = 5, rows = 5)
        assertTrue(GridEdge(0, 0, 0, 1) in edges) // западный край сетки
        assertTrue(GridEdge(0, 0, 1, 0) in edges) // южный край сетки
    }

    // --- Чтение water.grid из пакета (FogRepository.loadWaterMask) ---

    private fun installWithWater(waterBytes: ByteArray?): RegionPackageRepository {
        val packagesRoot = File(temp.root, "packages")
        val dir = TestFog.installPackage(packagesRoot)
        if (waterBytes != null) File(dir, "water.grid").writeBytes(waterBytes)
        return RegionPackageRepository(packagesRoot)
    }

    @Test
    fun `missing water grid gives empty mask — backward compatible`() {
        val packages = installWithWater(waterBytes = null)
        val pkg = packages.getInstalled(TestFog.REGION_ID)!!
        val result = FogRepository(File(temp.root, "fog")).loadWaterMask(pkg)
        assertTrue(result is MaskResult.Ok)
        assertEquals(0, (result as MaskResult.Ok).mask.cardinality())
    }

    @Test
    fun `valid water grid parses like walkability format v0`() {
        // Те же байты, что пишет TestFog для walkability.grid (заголовок + lsb-first битпак).
        val bytes = TestFog.gridFileBytes(walkable = setOf(5, 6, 7))
        val packages = installWithWater(waterBytes = bytes)
        val pkg = packages.getInstalled(TestFog.REGION_ID)!!
        val result = FogRepository(File(temp.root, "fog")).loadWaterMask(pkg)
        assertTrue(result is MaskResult.Ok)
        val mask = (result as MaskResult.Ok).mask
        assertEquals(setOf(5, 6, 7), mask.stream().boxed().toList().toSet())
    }

    @Test
    fun `corrupt water grid is a typed error`() {
        val packages = installWithWater(waterBytes = "не grid-файл".toByteArray())
        val pkg = packages.getInstalled(TestFog.REGION_ID)!!
        val result = FogRepository(File(temp.root, "fog")).loadWaterMask(pkg)
        assertTrue(result is MaskResult.Err)
    }

    // --- Геометрия прогона (дырка завесы) ---

    @Test
    fun `run ring spans merged columns with hole winding`() {
        val grid = TestFog.fogGrid(cols = 10, rows = 10, originLon = 30.0, originLat = 59.0)
        val ring = grid.runRing(RowRun(row = 2, colStart = 3, colEnd = 5))
        assertEquals(5, ring.size)
        assertEquals(ring.first().toList(), ring.last().toList()) // замкнуто
        // Ширина кольца — 3 тайла по долготе, высота — 1 тайл по широте.
        val width = ring[2][0] - ring[0][0]
        val height = ring[1][1] - ring[0][1]
        assertEquals(3 * grid.dx, width, 1e-12)
        assertEquals(grid.dy, height, 1e-12)
        // Обход по часовой (CW), как у tileRing: MapLibre признаёт кольцо дыркой.
        // Shoelace по lon/lat: отрицательная площадь = CW (y вверх).
        var area = 0.0
        for (i in 0 until ring.size - 1) {
            area += ring[i][0] * ring[i + 1][1] - ring[i + 1][0] * ring[i][1]
        }
        assertTrue("кольцо прогона должно быть CW (дырка)", area < 0)
    }
}
