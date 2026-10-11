package app.walkquest.data.transfer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Формат архива переноса (история 6.1, FR-21): round-trip, валидация версии
 * и целостности, пропуски отсутствующих источников, неизвестные секции.
 */
class ExportArchiveTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private fun sources(
        settingsRoot: File = tmp.newFolder("settings"),
        fogRoot: File = tmp.newFolder("fog"),
        tracksRoot: File = tmp.newFolder("tracks"),
        withHistory: Boolean = true,
    ): ExportSources {
        File(settingsRoot, "settings.json").writeText("""{"homeRegionId":"spb"}""")
        if (withHistory) {
            File(fogRoot, "spb.fog").writeBytes(byteArrayOf(1, 2, 3, -1, 0, 7))
            File(fogRoot, "msk.fog").writeBytes(byteArrayOf(9))
            File(tracksRoot, "track-1700000000000.track").writeText("{\"a\":1}\n")
        }
        return ExportSources(
            settingsRoot = settingsRoot,
            fogRoot = fogRoot,
            walksFile = File(tmp.newFolder("walks"), "walks.jsonl").apply {
                if (withHistory) writeText("{\"sessionId\":1}\n{\"sessionId\":2}\n")
            },
            earnedFile = File(tmp.newFolder("achievements"), "earned.json").apply {
                if (withHistory) writeText("{\"earned\":[\"A1\"]}")
            },
            questsFile = File(tmp.newFolder("quests"), "quests.json").apply {
                if (withHistory) writeText("{\"active\":\"Q3\"}")
            },
            plansFile = File(tmp.newFolder("plans"), "plans.json").apply {
                if (withHistory) writeText("{\"plans\":[]}")
            },
            poiCardsFile = File(tmp.newFolder("poicard"), "poicards.json").apply {
                if (withHistory) writeText("{\"ids\":[\"n1\"]}")
            },
            tracksRoot = tracksRoot,
            appVersion = "1.0",
            installedRegionIds = if (withHistory) listOf("spb", "msk") else emptyList(),
        )
    }

    private fun exportToBytes(s: ExportSources): ByteArray {
        val out = ByteArrayOutputStream()
        val result = ExportArchiveWriter().write(out, s)
        assertTrue("write failed: $result", result is ExportArchiveWriter.Result.Ok)
        return out.toByteArray()
    }

    @Test
    fun `round-trip preserves all sections byte-for-byte`() {
        val s = sources()
        val archive = readOk(exportToBytes(s))

        assertEquals(EXPORT_FORMAT_VERSION, archive.manifest.formatVersion)
        assertEquals("1.0", archive.manifest.appVersion)
        assertEquals(listOf("spb", "msk"), archive.manifest.installedRegionIds)
        assertTrue(archive.manifest.exportedAtEpochMillis > 0)

        assertArrayEquals(byteArrayOf(1, 2, 3, -1, 0, 7), archive.sections["fog/spb.fog"])
        assertArrayEquals(byteArrayOf(9), archive.sections["fog/msk.fog"])
        assertEquals("{\"a\":1}\n", archive.sections["tracks/track-1700000000000.track"]!!.decodeToString())
        assertEquals("{\"homeRegionId\":\"spb\"}", archive.sections["settings/settings.json"]!!.decodeToString())
        assertEquals("{\"sessionId\":1}\n{\"sessionId\":2}\n", archive.sections["walks/walks.jsonl"]!!.decodeToString())
        assertEquals("{\"earned\":[\"A1\"]}", archive.sections["achievements/earned.json"]!!.decodeToString())
        assertEquals("{\"active\":\"Q3\"}", archive.sections["quests/quests.json"]!!.decodeToString())
        assertEquals("{\"plans\":[]}", archive.sections["plans/plans.json"]!!.decodeToString())
        assertEquals("{\"ids\":[\"n1\"]}", archive.sections["poicards.json"]!!.decodeToString())
        assertTrue(archive.warnings.isEmpty())
    }

    @Test
    fun `empty sources produce valid archive with manifest only`() {
        val s = sources(withHistory = false)
        val archive = readOk(exportToBytes(s))

        assertEquals(EMPTY_LIST, archive.manifest.installedRegionIds)
        assertEquals(setOf("settings/settings.json"), archive.sections.keys)
    }

    @Test
    fun `temp leftovers and foreign files in roots are not exported`() {
        val s = sources()
        // Остаток атомарной записи FogRepository и посторонний файл.
        File(s.fogRoot, ".fog-spb-12345.tmp").writeBytes(byteArrayOf(1))
        File(s.fogRoot, "notes.txt").writeText("junk")
        // Посторонний файл в tracks/.
        File(s.tracksRoot, "random.bin").writeBytes(byteArrayOf(2))

        val archive = readOk(exportToBytes(s))
        assertEquals(setOf("fog/spb.fog", "fog/msk.fog"), archive.sections.keys.filter { it.startsWith("fog/") }.toSet())
        assertEquals(setOf("tracks/track-1700000000000.track"), archive.sections.keys.filter { it.startsWith("tracks/") }.toSet())
    }

    @Test
    fun `truncated zip is corrupt archive`() {
        val bytes = exportToBytes(sources())
        val cut = bytes.copyOfRange(0, bytes.size / 2)
        val result = ExportArchiveReader().read(ByteArrayInputStream(cut))
        assertTrue(
            "expected CorruptArchive, got $result",
            result is ExportArchiveReader.ReadResult.Failure &&
                result.error is ExportReadError.CorruptArchive,
        )
    }

    @Test
    fun `not a zip at all is corrupt archive`() {
        val result = ExportArchiveReader().read(ByteArrayInputStream("plain text".toByteArray()))
        assertTrue(
            "expected CorruptArchive, got $result",
            result is ExportArchiveReader.ReadResult.Failure &&
                result.error is ExportReadError.CorruptArchive,
        )
    }

    @Test
    fun `newer formatVersion is unsupported`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(
                """{"formatVersion":999,"exportedAtEpochMillis":1,"appVersion":"9.9","installedRegionIds":[]}"""
                    .toByteArray()
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("walks/walks.jsonl"))
            zip.write("x".toByteArray())
            zip.closeEntry()
        }
        val result = ExportArchiveReader().read(ByteArrayInputStream(out.toByteArray()))
        assertTrue(
            "expected UnsupportedFormat(999), got $result",
            result is ExportArchiveReader.ReadResult.Failure &&
                result.error is ExportReadError.UnsupportedFormat &&
                (result.error as ExportReadError.UnsupportedFormat).foundVersion == 999,
        )
    }

    @Test
    fun `missing manifest is corrupt archive`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("walks/walks.jsonl"))
            zip.write("x".toByteArray())
            zip.closeEntry()
        }
        val result = ExportArchiveReader().read(ByteArrayInputStream(out.toByteArray()))
        assertTrue(
            "expected CorruptArchive, got $result",
            result is ExportArchiveReader.ReadResult.Failure &&
                result.error is ExportReadError.CorruptArchive,
        )
    }

    @Test
    fun `unknown sections are ignored with warning`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(
                """{"formatVersion":1,"exportedAtEpochMillis":1,"appVersion":"1.0","installedRegionIds":[]}"""
                    .toByteArray()
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("future/section.bin"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }
        val archive = readOk(out.toByteArray())
        assertTrue(archive.sections.isEmpty())
        assertEquals(listOf("unknown section ignored: future/section.bin"), archive.warnings)
    }

    @Test
    fun `duplicate sections are applied last-wins with warning`() {
        // ZipOutputStream дубликаты не пишет — «чужой» архив собираем вручную (STORED).
        val bytes = rawZip(
            "manifest.json" to """{"formatVersion":1,"exportedAtEpochMillis":1,"appVersion":"1.0","installedRegionIds":[]}""".toByteArray(),
            "walks/walks.jsonl" to "{\"sessionId\":1}\n".toByteArray(),
            "walks/walks.jsonl" to "{\"sessionId\":2}\n".toByteArray(),
        )
        val archive = readOk(bytes)
        assertEquals("{\"sessionId\":2}\n", archive.sections["walks/walks.jsonl"]!!.decodeToString())
        assertEquals(listOf("duplicate section: walks/walks.jsonl (применена последняя копия)"), archive.warnings)
    }

    @Test
    fun `corrupted payload fails crc on read`() {
        // STORED-запись: байты лежат открыто, порча гарантированно ломает CRC.
        val payload = """{"formatVersion":1,"exportedAtEpochMillis":1,"appVersion":"1.0","installedRegionIds":[]}"""
            .toByteArray()
        val crc = java.util.zip.CRC32().apply { update(payload) }.value
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val entry = ZipEntry("manifest.json").apply {
                method = ZipEntry.STORED
                size = payload.size.toLong()
                compressedSize = payload.size.toLong()
                this.crc = crc
            }
            zip.putNextEntry(entry)
            zip.write(payload)
            zip.closeEntry()
        }
        val bytes = out.toByteArray()
        // Портим байт внутри payload (после локального заголовка ~30 байт + имени 12).
        val pos = 30 + "manifest.json".length + 5
        bytes[pos] = (bytes[pos] + 1).toByte()
        val result = ExportArchiveReader().read(ByteArrayInputStream(bytes))
        assertTrue(
            "expected CorruptArchive, got $result",
            result is ExportArchiveReader.ReadResult.Failure &&
                result.error is ExportReadError.CorruptArchive,
        )
    }

    private fun readOk(bytes: ByteArray): ExportArchiveReader.Archive {
        val result = ExportArchiveReader().read(ByteArrayInputStream(bytes))
        assertTrue("expected Ok, got $result", result is ExportArchiveReader.ReadResult.Ok)
        return (result as ExportArchiveReader.ReadResult.Ok).archive
    }

    private companion object {
        val EMPTY_LIST: List<String> = emptyList()
    }
}

/** Сырой zip (STORED, little-endian) — для архивов, которые ZipOutputStream не пишет. */
private fun rawZip(vararg entries: Pair<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    val central = ByteArrayOutputStream()
    val crc32 = java.util.zip.CRC32()
    val offsets = ArrayList<Int>()

    fun w16(stream: ByteArrayOutputStream, v: Int) {
        stream.write(v and 0xff)
        stream.write((v shr 8) and 0xff)
    }

    fun w32(stream: ByteArrayOutputStream, v: Long) {
        for (i in 0..3) stream.write(((v shr (8 * i)) and 0xffL).toInt())
    }

    for ((name, data) in entries) {
        val nameBytes = name.toByteArray()
        crc32.reset()
        crc32.update(data)
        offsets.add(out.size())
        w32(out, 0x04034b50)
        w16(out, 20) // version needed
        w16(out, 0) // flags
        w16(out, 0) // method: STORED
        w16(out, 0); w16(out, 0) // time, date
        w32(out, crc32.value)
        w32(out, data.size.toLong())
        w32(out, data.size.toLong())
        w16(out, nameBytes.size)
        w16(out, 0) // extra len
        out.write(nameBytes)
        out.write(data)
    }
    for ((index, entry) in entries.withIndex()) {
        val (name, data) = entry
        val nameBytes = name.toByteArray()
        crc32.reset()
        crc32.update(data)
        w32(central, 0x02014b50)
        w16(central, 20); w16(central, 20) // version made by / needed
        w16(central, 0); w16(central, 0); w16(central, 0); w16(central, 0) // flags, method, time, date
        w32(central, crc32.value)
        w32(central, data.size.toLong())
        w32(central, data.size.toLong())
        w16(central, nameBytes.size)
        w16(central, 0); w16(central, 0); w16(central, 0); w16(central, 0) // extra, comment, disk, attrs
        w32(central, 0) // external attrs
        w32(central, offsets[index].toLong())
        central.write(nameBytes)
    }
    val centralOffset = out.size()
    out.write(central.toByteArray())
    w32(out, 0x06054b50)
    w16(out, 0); w16(out, 0) // disk, cd start disk
    w16(out, entries.size); w16(out, entries.size)
    w32(out, central.size().toLong())
    w32(out, centralOffset.toLong())
    w16(out, 0) // comment len
    return out.toByteArray()
}
