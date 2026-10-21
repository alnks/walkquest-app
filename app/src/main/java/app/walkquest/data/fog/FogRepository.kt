package app.walkquest.data.fog

import app.walkquest.data.packages.InstalledPackage
import app.walkquest.data.packages.PackageJson
import app.walkquest.data.packages.PackageMeta
import app.walkquest.logging.WqLayer
import app.walkquest.logging.WqLog
import java.io.File
import java.io.IOException
import java.util.BitSet
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Персистентная биткарта тумана региона (история 1.6, AD-4).
 *
 * Один файл на регион: `fog/<regionId>.fog`. Формат зеркалит walkability.grid v0 —
 * строка JSON-заголовка + row-major lsb-first битпак — плюс formatVersion и regionId
 * (Consistency Conventions: версионируемые форматы). Бит i здесь и в walkability.grid —
 * один и тот же тайл сетки (index = row * cols + col).
 *
 * Файл создаётся при первом обращении полностью закрытым (весь регион в тумане).
 * Запись атомарная: temp-файл → rename (как PackageInstaller 1.5); при сбое записи
 * состояние в памяти не меняется. Битый/несовместимый файл → биткарта пересоздаётся
 * закрытой (туман «на всякий случай»), факт восстановления возвращается вызывающему.
 *
 * Чистый JVM-код (AD-10): корень хранилища инжектируется из AppContainer
 * (`File(context.filesDir, "fog")`), Android не импортируется.
 */
class FogRepository(private val fogRoot: File) {

    private val cache = HashMap<String, FogBitmap>()

    /** Читает сетку-знаменатель из установленного пакета (walkability.grid + meta.json bbox). */
    fun loadGrid(pkg: InstalledPackage): GridResult {
        return try {
            val headerLine = pkg.walkabilityFile.bufferedReader().use { it.readLine() }
                ?: return GridResult.Err(FogError.GridInvalid(pkg.regionId, "walkability.grid пуст"))
            val header = FogJson.decodeFromString<GridHeader>(headerLine)
            val meta = PackageJson.decodeFromString<PackageMeta>(pkg.metaFile.readText())
            val bbox = meta.bbox
                ?: return GridResult.Err(FogError.GridInvalid(pkg.regionId, "в meta.json нет bbox"))
            GridResult.Ok(FogGrid.fromPackage(header, bbox))
        } catch (e: Exception) {
            GridResult.Err(FogError.GridInvalid(pkg.regionId, e.message ?: e.toString()))
        }
    }

    /**
     * Читает проходимую маску пакета — знаменатель процента открытия (история 1.8, AD-4):
     * payload walkability.grid (row-major lsb-first, бит 1 = проходимый тайл).
     * Заголовочный walkableCount (> 0) сверяется с маской как контроль целостности.
     */
    fun loadWalkableMask(pkg: InstalledPackage): MaskResult =
        parseGridMask(pkg.walkabilityFile, pkg.regionId, "walkability.grid")

    /**
     * Читает водяную маску пакета (разбор владельца 2026-10-05: вода видна сквозь туман).
     * `water.grid` опционален (формат идентичен walkability.grid v0, бит 1 = вода):
     * файла нет → пустая маска, поведение тумана прежнее (обратная совместимость).
     * Битый файл — типизированная ошибка, как у проходимой маски.
     */
    fun loadWaterMask(pkg: InstalledPackage): MaskResult {
        if (!pkg.waterFile.isFile) return MaskResult.Ok(BitSet())
        return parseGridMask(pkg.waterFile, pkg.regionId, "water.grid")
    }

    /** Общий разбор файла маски формата v0: строка JSON-заголовка + row-major lsb-first битпак. */
    private fun parseGridMask(file: File, regionId: String, fileName: String): MaskResult {
        return try {
            val bytes = file.readBytes()
            val newline = bytes.indexOf('\n'.code.toByte())
            if (newline <= 0) {
                return MaskResult.Err(FogError.GridInvalid(regionId, "$fileName: нет строки заголовка"))
            }
            val header = FogJson.decodeFromString<GridHeader>(bytes.decodeToString(0, newline))
            if (header.cols <= 0 || header.rows <= 0) {
                return MaskResult.Err(FogError.GridInvalid(regionId, "размерность сетки неположительна"))
            }
            val payload = bytes.copyOfRange(newline + 1, bytes.size)
            val expected = (header.cols * header.rows + 7) / 8
            if (payload.size != expected) {
                return MaskResult.Err(
                    FogError.GridInvalid(regionId, "битпак маски ${payload.size} байт вместо $expected"),
                )
            }
            // BitSet.valueOf: lsb-first — то же, что np.packbits(bitorder="little") у генератора.
            val mask = BitSet.valueOf(payload)
            if (header.walkableCount > 0 && mask.cardinality() != header.walkableCount) {
                return MaskResult.Err(
                    FogError.GridInvalid(
                        regionId,
                        "walkableCount=${header.walkableCount} не совпадает с маской (${mask.cardinality()})",
                    ),
                )
            }
            MaskResult.Ok(mask)
        } catch (e: Exception) {
            MaskResult.Err(FogError.GridInvalid(regionId, e.message ?: e.toString()))
        }
    }

    /**
     * Загружает биткарту региона (или создаёт закрытую при первом обращении).
     * Битый файл → новая закрытая биткарта, [BitmapResult.Ok.recoveredFromCorrupt] = true.
     */
    @Synchronized
    fun load(regionId: String, grid: FogGrid): BitmapResult {
        // Кэш валиден только против той же сетки: обновление пакета с другой
        // размерностью в середине сессии не должно работать со старой биткартой.
        cache[regionId]?.takeIf { it.grid.sameGridAs(grid) }?.let {
            WqLog.v(WqLayer.CACHE) { "fog bitmap HIT (memory) region=$regionId" }
            return BitmapResult.Ok(it)
        }
        val file = fileFor(regionId)
        val loaded = readBitmap(file, regionId, grid)
        val bitmap: FogBitmap
        val recovered: Boolean
        if (loaded != null) {
            bitmap = loaded
            recovered = false
            WqLog.d(WqLayer.CACHE) {
                "fog bitmap MISS (memory) → disk region=$regionId tiles=${bitmap.grid.tileCount}"
            }
        } else {
            bitmap = FogBitmap(regionId, grid, BitSet(grid.tileCount), file)
            recovered = file.exists() // файл был, но не прочитался → сброшен в закрытое
            WqLog.d(WqLayer.CACHE) {
                "fog bitmap MISS (memory+disk) region=$regionId " +
                    if (recovered) "corrupt file → reset to closed" else "new closed bitmap"
            }
            if (!recovered) {
                // Первый запуск: материализуем закрытую биткарту, чтобы формат жил на диске сразу.
                persist(bitmap)?.let { return BitmapResult.Err(it) }
            }
        }
        cache[regionId] = bitmap
        return BitmapResult.Ok(bitmap, recovered)
    }

    /**
     * Открывает бит и атомарно сохраняет биткарту. Идемпотентно.
     * При сбое записи бит в памяти не выставляется.
     */
    @Synchronized
    fun open(regionId: String, grid: FogGrid, index: Int): OpenBitResult {
        if (index !in 0 until grid.tileCount) return OpenBitResult.OutOfRange
        val bitmap = when (val loaded = load(regionId, grid)) {
            is BitmapResult.Err -> return OpenBitResult.Failure(loaded.error)
            is BitmapResult.Ok -> loaded.bitmap
        }
        if (bitmap.bits.get(index)) return OpenBitResult.Unchanged
        val error = persistWith(bitmap) { it.set(index) }
        return if (error == null) OpenBitResult.Changed(bitmap.openedCount) else OpenBitResult.Failure(error)
    }

    /** Битовая развёртка для слоя: индексы открытых тайлов (моментальный снимок). */
    fun openedIndices(regionId: String, grid: FogGrid): Set<Int> =
        when (val loaded = load(regionId, grid)) {
            is BitmapResult.Err -> emptySet()
            is BitmapResult.Ok -> loaded.bitmap.openedIndices()
        }

    private fun fileFor(regionId: String): File = File(fogRoot, "$regionId$FILE_EXTENSION")

    /**
     * Читает файл биткарты; любая несогласованность (формат, регион, размерность,
     * порядок битпака) → null (вызывающий пересоздаёт закрытую).
     */
    private fun readBitmap(file: File, regionId: String, grid: FogGrid): FogBitmap? {
        if (!file.isFile) return null
        return try {
            val bytes = file.readBytes()
            val newline = bytes.indexOf('\n'.code.toByte())
            if (newline <= 0) return null
            val header = FogJson.decodeFromString<BitmapHeader>(bytes.decodeToString(0, newline))
            if (header.formatVersion != FORMAT_VERSION) return null
            if (header.regionId != regionId) return null
            if (header.cols != grid.cols || header.rows != grid.rows) return null
            if (header.tileSizeMeters != grid.tileSizeMeters) return null
            if (header.origin != listOf(grid.originLon, grid.originLat)) return null
            if (!header.order.contains("row-major") || !header.order.contains("lsb-first")) return null
            val payload = bytes.copyOfRange(newline + 1, bytes.size)
            if (payload.size != (grid.tileCount + 7) / 8) return null
            // BitSet.valueOf: lsb-first — то же, что np.packbits(bitorder="little") у генератора.
            FogBitmap(regionId, grid, BitSet.valueOf(payload), file)
        } catch (e: Exception) {
            null
        }
    }

    /** Атомарная запись биткарты: temp → rename. @return ошибку или null при успехе. */
    private fun persist(bitmap: FogBitmap): FogError? = persistWith(bitmap) {}

    private inline fun persistWith(bitmap: FogBitmap, mutate: (BitSet) -> Unit): FogError? {
        // Мутацию применяем к копии: при сбое записи состояние в памяти не тронуто.
        val next = bitmap.bits.clone() as BitSet
        mutate(next)
        val header = BitmapHeader(
            formatVersion = FORMAT_VERSION,
            regionId = bitmap.regionId,
            cols = bitmap.grid.cols,
            rows = bitmap.grid.rows,
            tileSizeMeters = bitmap.grid.tileSizeMeters,
            origin = listOf(bitmap.grid.originLon, bitmap.grid.originLat),
        )
        val payload = next.toByteArray().copyOf((bitmap.grid.tileCount + 7) / 8)
        val temp = File(fogRoot, ".fog-${bitmap.regionId}-${System.nanoTime()}.tmp")
        return try {
            fogRoot.mkdirs()
            temp.writeBytes(FogJson.encodeToString(header).encodeToByteArray() + '\n'.code.toByte() + payload)
            if (!temp.renameTo(bitmap.file)) {
                // renameTo через существующий файл на части FS не работает — перезапись копированием.
                bitmap.file.delete()
                if (!temp.renameTo(bitmap.file)) throw IOException("rename $temp → ${bitmap.file} не удался")
            }
            bitmap.bits.clear()
            bitmap.bits.or(next)
            null
        } catch (e: Exception) {
            FogError.StorageError(e.message ?: e.toString())
        } finally {
            temp.delete()
        }
    }

    /** Биткарта одного региона в памяти; диск — источник истины между запусками. */
    class FogBitmap internal constructor(
        val regionId: String,
        val grid: FogGrid,
        internal val bits: BitSet,
        internal val file: File,
    ) {
        val openedCount: Int get() = bits.cardinality()

        fun isOpen(index: Int): Boolean = index in 0 until grid.tileCount && bits.get(index)

        fun openedIndices(): Set<Int> {
            val result = HashSet<Int>(bits.cardinality())
            var i = bits.nextSetBit(0)
            while (i >= 0) {
                result.add(i)
                i = bits.nextSetBit(i + 1)
            }
            return result
        }
    }

    /** Заголовок файла биткарты (первая строка JSON). */
    @Serializable
    private data class BitmapHeader(
        val formatVersion: Int,
        val regionId: String,
        val cols: Int,
        val rows: Int,
        val tileSizeMeters: Double,
        val origin: List<Double>,
        val order: String = BIT_ORDER,
    )

    companion object {
        const val FORMAT_VERSION = 1
        const val BIT_ORDER = "row-major, lsb-first"
        const val FILE_EXTENSION = ".fog"
    }
}

/** Типизированные ошибки data/fog (sealed-результаты, не исключения через границы). */
sealed class FogError {
    abstract val message: String

    data class GridInvalid(val regionId: String, val detail: String) : FogError() {
        override val message get() = "Сетка проходимости региона «$regionId» повреждена: $detail"
    }

    data class StorageError(val detail: String) : FogError() {
        override val message get() = "Ошибка хранилища биткарты тумана: $detail"
    }
}

/** Результат чтения сетки из пакета. */
sealed class GridResult {
    data class Ok(val grid: FogGrid) : GridResult()
    data class Err(val error: FogError) : GridResult()
}

/** Результат чтения проходимой маски пакета (знаменатель процента, история 1.8). */
sealed class MaskResult {
    data class Ok(val mask: BitSet) : MaskResult()
    data class Err(val error: FogError) : MaskResult()
}

/** Результат загрузки биткарты; [Ok.recoveredFromCorrupt] — битый файл сброшен в закрытое. */
sealed class BitmapResult {
    data class Ok(val bitmap: FogRepository.FogBitmap, val recoveredFromCorrupt: Boolean = false) : BitmapResult()
    data class Err(val error: FogError) : BitmapResult()
}

/** Результат открытия бита. */
sealed class OpenBitResult {
    /** Бит был закрыт и открыт; [openedCount] — всего открыто в регионе после мутации. */
    data class Changed(val openedCount: Int) : OpenBitResult()
    data object Unchanged : OpenBitResult()
    data object OutOfRange : OpenBitResult()
    data class Failure(val error: FogError) : OpenBitResult()
}

/** Единый JSON-парсер data/fog: неизвестные поля заголовков игнорируем. */
private val FogJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
