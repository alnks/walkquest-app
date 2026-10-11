package app.walkquest.data.fog

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Персистентная биткарта тумана (AD-4: один файл на регион, бит = тайл сетки пакета).
 * Открытие, идемпотентность, roundtrip между «запусками», формат файла (lsb-first,
 * как walkability.grid v0), восстановление после повреждения.
 */
class FogRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun repository(root: File = temp.root): FogRepository = FogRepository(File(root, "fog"))

    @Test
    fun `first load creates fully closed bitmap and materializes file`() {
        val grid = TestFog.fogGrid()
        val result = repository().load(TestFog.REGION_ID, grid)

        val ok = result as BitmapResult.Ok
        assertFalse(ok.recoveredFromCorrupt)
        assertEquals(0, ok.bitmap.openedCount)
        assertTrue(ok.bitmap.openedIndices().isEmpty())

        // Формат: строка JSON-заголовка + lsb-first битпак длиной ceil(tileCount/8).
        val file = File(temp.root, "fog/${TestFog.REGION_ID}.fog")
        assertTrue(file.isFile)
        val bytes = file.readBytes()
        val newline = bytes.indexOf('\n'.code.toByte())
        val header = bytes.decodeToString(0, newline)
        assertTrue(header.contains("\"formatVersion\":1"))
        assertTrue(header.contains("\"regionId\":\"${TestFog.REGION_ID}\""))
        assertTrue(header.contains("\"cols\":${grid.cols}"))
        assertTrue(header.contains("\"rows\":${grid.rows}"))
        assertTrue(header.contains("lsb-first"))
        assertEquals((grid.tileCount + 7) / 8, bytes.size - newline - 1)
        assertTrue(bytes.copyOfRange(newline + 1, bytes.size).all { it.toInt() == 0 })
    }

    @Test
    fun `open persists bit across repository instances`() {
        val grid = TestFog.fogGrid()
        val first = repository()
        assertEquals(OpenBitResult.Changed(1), first.open(TestFog.REGION_ID, grid, 42))

        // «Перезапуск»: новый репозиторий на том же корне читает файл с диска.
        val second = repository()
        val loaded = second.load(TestFog.REGION_ID, grid) as BitmapResult.Ok
        assertFalse(loaded.recoveredFromCorrupt)
        assertTrue(loaded.bitmap.isOpen(42))
        assertEquals(setOf(42), loaded.bitmap.openedIndices())
        assertEquals(1, loaded.bitmap.openedCount)
    }

    @Test
    fun `second open of same bit is unchanged and does not rewrite file`() {
        val grid = TestFog.fogGrid()
        val repo = repository()
        assertEquals(OpenBitResult.Changed(1), repo.open(TestFog.REGION_ID, grid, 7))
        val file = File(temp.root, "fog/${TestFog.REGION_ID}.fog")
        val bytesAfterFirst = file.readBytes()

        assertEquals(OpenBitResult.Unchanged, repo.open(TestFog.REGION_ID, grid, 7))
        assertTrue(bytesAfterFirst.contentEquals(file.readBytes()))
        assertEquals(1, (repo.load(TestFog.REGION_ID, grid) as BitmapResult.Ok).bitmap.openedCount)
    }

    @Test
    fun `open out of range bit is rejected without touching state`() {
        val grid = TestFog.fogGrid()
        val repo = repository()
        assertEquals(OpenBitResult.OutOfRange, repo.open(TestFog.REGION_ID, grid, -1))
        assertEquals(OpenBitResult.OutOfRange, repo.open(TestFog.REGION_ID, grid, grid.tileCount))
        val loaded = repo.load(TestFog.REGION_ID, grid) as BitmapResult.Ok
        assertEquals(0, loaded.bitmap.openedCount)
    }

    @Test
    fun `bit packing is lsb-first matching walkability grid v0`() {
        val grid = TestFog.fogGrid()
        val repo = repository()
        repo.open(TestFog.REGION_ID, grid, 1)
        repo.open(TestFog.REGION_ID, grid, 8)
        repo.open(TestFog.REGION_ID, grid, grid.tileCount - 1)

        val file = File(temp.root, "fog/${TestFog.REGION_ID}.fog")
        val bytes = file.readBytes()
        val newline = bytes.indexOf('\n'.code.toByte())
        val payload = bytes.copyOfRange(newline + 1, bytes.size)

        // np.packbits(bitorder="little") / BitSet: бит i живёт в байте i/8 со смещением i%8.
        assertEquals(0b0000_0010, payload[0].toInt() and 0xFF) // бит 1
        assertEquals(0b0000_0001, payload[1].toInt() and 0xFF) // бит 8
        val last = grid.tileCount - 1
        assertEquals(1 shl (last % 8), payload[last / 8].toInt() and 0xFF)
    }

    @Test
    fun `corrupt file recovers to closed bitmap`() {
        val grid = TestFog.fogGrid()
        val fogDir = File(temp.root, "fog").apply { mkdirs() }
        File(fogDir, "${TestFog.REGION_ID}.fog").writeBytes("мусор, не формат".toByteArray())

        val result = repository().load(TestFog.REGION_ID, grid)
        val ok = result as BitmapResult.Ok
        assertTrue(ok.recoveredFromCorrupt)
        assertEquals(0, ok.bitmap.openedCount)

        // После восстановления открытие работает и перезаписывает битый файл честным.
        val repo = repository()
        assertEquals(OpenBitResult.Changed(1), repo.open(TestFog.REGION_ID, grid, 3))
    }

    @Test
    fun `grid dimension mismatch recovers to closed bitmap`() {
        val grid = TestFog.fogGrid()
        val repo = repository()
        repo.open(TestFog.REGION_ID, grid, 5)

        // «Новая версия пакета»: другая размерность сетки — старая биткарта несовместима.
        val otherGrid = TestFog.fogGrid(cols = TestFog.COLS + 1)
        val fresh = repository()
        val loaded = fresh.load(TestFog.REGION_ID, otherGrid) as BitmapResult.Ok
        assertTrue(loaded.recoveredFromCorrupt)
        assertEquals(0, loaded.bitmap.openedCount)
    }

    @Test
    fun `stale cached grid reloads from disk instead of reusing old-grid bitmap`() {
        val grid = TestFog.fogGrid()
        val repo = repository()
        repo.open(TestFog.REGION_ID, grid, 5)

        // «Обновление пакета в середине сессии»: тот же репозиторий, новая размерность.
        // Кэш по старой сетке переиспользовать нельзя — биткарта сбрасывается в закрытое.
        val otherGrid = TestFog.fogGrid(cols = TestFog.COLS + 1)
        val loaded = repo.load(TestFog.REGION_ID, otherGrid) as BitmapResult.Ok
        assertTrue(loaded.recoveredFromCorrupt)
        assertEquals(0, loaded.bitmap.openedCount)

        // Возврат к исходной сетке — кэш снова невалиден, читается диск (там бит 5 на месте).
        val reloaded = repo.load(TestFog.REGION_ID, grid) as BitmapResult.Ok
        assertTrue(reloaded.bitmap.isOpen(5))
    }

    @Test
    fun `different regions have independent files`() {
        val grid = TestFog.fogGrid()
        val repo = repository()
        repo.open(TestFog.REGION_ID, grid, 9)

        val other = repo.load("other-region", grid) as BitmapResult.Ok
        assertEquals(0, other.bitmap.openedCount)
        assertTrue(File(temp.root, "fog/other-region.fog").isFile)
    }

    // --- Проходимая маска пакета (1.8): знаменатель процента ---

    private fun installed(walkable: Set<Int>) =
        app.walkquest.data.packages.RegionPackageRepository(
            File(temp.root, "packages").apply {
                TestFog.installPackage(this, walkable = walkable)
            },
        ).getInstalled(TestFog.REGION_ID)!!

    @Test
    fun `walkable mask roundtrips from package payload lsb-first`() {
        val grid = TestFog.fogGrid()
        val walkable = setOf(0, 1, 8, grid.tileCount - 1)
        val pkg = installed(walkable)

        val result = repository().loadWalkableMask(pkg)
        val mask = (result as MaskResult.Ok).mask
        assertEquals(walkable.size, mask.cardinality())
        walkable.forEach { assertTrue("бит $it проходим", mask.get(it)) }
        assertFalse(mask.get(2))
    }

    @Test
    fun `mask cardinality is validated against header walkableCount`() {
        val dir = TestFog.installPackage(File(temp.root, "packages"), walkable = setOf(0, 1))
        // Заголовок врёт: walkableCount=5, в битпаке — 2 бита.
        val header = TestFog.gridHeaderJson(walkableCount = 5) + "\n"
        val payload = ByteArray((TestFog.COLS * TestFog.ROWS + 7) / 8)
        payload[0] = 0b11
        File(dir, "walkability.grid").writeBytes(header.toByteArray() + payload)
        val pkg = app.walkquest.data.packages.RegionPackageRepository(File(temp.root, "packages"))
            .getInstalled(TestFog.REGION_ID)!!

        assertTrue(repository().loadWalkableMask(pkg) is MaskResult.Err)
    }

    @Test
    fun `broken walkability grid yields mask error`() {
        val dir = TestFog.installPackage(File(temp.root, "packages"), walkable = setOf(0))
        File(dir, "walkability.grid").writeBytes("мусор, не формат".toByteArray())
        val pkg = app.walkquest.data.packages.RegionPackageRepository(File(temp.root, "packages"))
            .getInstalled(TestFog.REGION_ID)!!

        assertTrue(repository().loadWalkableMask(pkg) is MaskResult.Err)
    }
}
