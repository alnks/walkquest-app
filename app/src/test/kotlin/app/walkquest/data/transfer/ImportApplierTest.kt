package app.walkquest.data.transfer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Импорт: restore архива 6.1 в хранилища (история 6.2, FR-21) — round-trip,
 * честный отчёт по битым секциям, защита от path traversal, удаление сессии.
 */
class ImportApplierTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    /** Среда хранилищ: withData=true — «богатый» источник, false — пустые цели. */
    private class Env(root: File, withData: Boolean) {
        val settingsRoot = File(root, "settings").apply { mkdirs() }
        val fogRoot = File(root, "fog").apply { mkdirs() }
        val tracksRoot = File(root, "tracks").apply { mkdirs() }
        val walksFile = File(root, "walks/walks.jsonl").apply { parentFile.mkdirs() }
        val earnedFile = File(root, "achievements/earned.json").apply { parentFile.mkdirs() }
        val questsFile = File(root, "quests/quests.json").apply { parentFile.mkdirs() }
        val plansFile = File(root, "plans/plans.json").apply { parentFile.mkdirs() }
        val poiCardsFile = File(root, "poicards.json")
        val sessionFile = File(root, "session/session.json").apply { parentFile.mkdirs() }

        init {
            File(settingsRoot, "settings.json").writeText("""{"homeRegionId":"spb"}""")
            sessionFile.writeText("{\"active\":true}")
            if (withData) {
                File(fogRoot, "spb.fog").writeBytes(fogBytesValid())
                walksFile.writeText(
                    "{\"sessionId\":1,\"startedAtEpochMillis\":10,\"finishedAtEpochMillis\":20," +
                        "\"distanceMeters\":100.0,\"activeMillis\":9000,\"acceptedCount\":5}\n"
                )
                earnedFile.writeText("""{"earned":[{"id":"A1","atEpochMillis":5}]}""")
                questsFile.writeText("""{"active":"Q3"}""")
                plansFile.writeText("""{"plans":[]}""")
                poiCardsFile.writeText("""{"ids":["n1"]}""")
                File(tracksRoot, "track-1700000000000.track").writeText(
                    "{\"header\":true}\n{\"lat\":1.0,\"lon\":2.0,\"t\":3}\n"
                )
            }
        }

        fun sources() = ExportSources(
            settingsRoot = settingsRoot, fogRoot = fogRoot, walksFile = walksFile,
            earnedFile = earnedFile, questsFile = questsFile, plansFile = plansFile,
            poiCardsFile = poiCardsFile, tracksRoot = tracksRoot,
            appVersion = "1.0", installedRegionIds = listOf("spb"),
        )

        fun targets() = ImportTargets(
            settingsRoot = settingsRoot, fogRoot = fogRoot, walksFile = walksFile,
            earnedFile = earnedFile, questsFile = questsFile, plansFile = plansFile,
            poiCardsFile = poiCardsFile, tracksRoot = tracksRoot,
            sessionFile = sessionFile,
        )
    }

    private fun export(env: Env): ByteArray {
        val out = ByteArrayOutputStream()
        val result = ExportArchiveWriter().write(out, env.sources())
        assertTrue("write failed: $result", result is ExportArchiveWriter.Result.Ok)
        return out.toByteArray()
    }

    private fun readArchive(bytes: ByteArray): ExportArchiveReader.Archive {
        val result = ExportArchiveReader().read(ByteArrayInputStream(bytes))
        assertTrue("expected Ok, got $result", result is ExportArchiveReader.ReadResult.Ok)
        return (result as ExportArchiveReader.ReadResult.Ok).archive
    }

    @Test
    fun `import into empty targets restores all stores byte-for-byte`() {
        val src = Env(tmp.newFolder("src"), withData = true)
        val dst = Env(tmp.newFolder("dst"), withData = false)

        val report = ImportApplier().apply(readArchive(export(src)), dst.targets())

        assertTrue(report.sections.values.all { it is SectionStatus.Ok })
        assertTrue(report.sessionDeleted)
        assertFalse(dst.sessionFile.exists())

        assertArrayEquals(src.walksFile.readBytes(), dst.walksFile.readBytes())
        assertArrayEquals(src.earnedFile.readBytes(), dst.earnedFile.readBytes())
        assertArrayEquals(src.questsFile.readBytes(), dst.questsFile.readBytes())
        assertArrayEquals(src.plansFile.readBytes(), dst.plansFile.readBytes())
        assertArrayEquals(src.poiCardsFile.readBytes(), dst.poiCardsFile.readBytes())
        assertArrayEquals(File(src.fogRoot, "spb.fog").readBytes(), File(dst.fogRoot, "spb.fog").readBytes())
        assertArrayEquals(
            File(src.tracksRoot, "track-1700000000000.track").readBytes(),
            File(dst.tracksRoot, "track-1700000000000.track").readBytes(),
        )
        assertEquals("""{"homeRegionId":"spb"}""", File(dst.settingsRoot, "settings.json").readText())
    }

    @Test
    fun `corrupt walks section is skipped and previous walks survive`() {
        val dst = Env(tmp.newFolder("dst"), withData = true)
        dst.walksFile.writeText("{\"sessionId\":42}\n")
        val bytes = export(dst)

        // Ломаем walks секцию: подменяем содержимое архива через новый источник.
        val broken = Env(tmp.newFolder("broken"), withData = true)
        broken.walksFile.writeText("{\"sessionId\":\"not-a-number\"}\n")
        val brokenArchive = readArchive(export(broken))

        val report = ImportApplier().apply(brokenArchive, dst.targets())

        assertEquals(SectionStatus.Skipped::class, report.sections["walks/walks.jsonl"]!!::class)
        assertTrue(report.sections["fog/spb.fog"] is SectionStatus.Ok)
        assertTrue(report.sections["achievements/earned.json"] is SectionStatus.Ok)
        // Прежние данные не тронуты.
        assertEquals("{\"sessionId\":42}\n", dst.walksFile.readText())
        // Остальные секции применены.
        assertArrayEquals(File(broken.fogRoot, "spb.fog").readBytes(), File(dst.fogRoot, "spb.fog").readBytes())
    }

    @Test
    fun `path traversal fog section is skipped and writes nothing`() {
        val dst = Env(tmp.newFolder("dst"), withData = false)
        val marker = File(tmp.root, "escape-marker")
        // Строим архив вручную: manifest + traversal-секция.
        val bytes = zipOf(
            "manifest.json" to """{"formatVersion":1,"exportedAtEpochMillis":1,"appVersion":"1.0","installedRegionIds":[]}""".toByteArray(),
            "fog/../escape-marker" to "x".toByteArray(),
        )
        val report = ImportApplier().apply(readArchive(bytes), dst.targets())

        assertEquals(SectionStatus.Skipped::class, report.sections["fog/../escape-marker"]!!::class)
        assertFalse(marker.exists())
    }

    @Test
    fun `fog section with non-json header is skipped`() {
        val dst = Env(tmp.newFolder("dst"), withData = false)
        val bytes = zipOf(
            "manifest.json" to """{"formatVersion":1,"exportedAtEpochMillis":1,"appVersion":"1.0","installedRegionIds":[]}""".toByteArray(),
            "fog/spb.fog" to byteArrayOf(1, 2, 3),
        )
        val report = ImportApplier().apply(readArchive(bytes), dst.targets())

        assertEquals(SectionStatus.Skipped::class, report.sections["fog/spb.fog"]!!::class)
        assertFalse(File(dst.fogRoot, "spb.fog").exists())
    }

    @Test
    fun `earned store reads restored achievements`() {
        val src = Env(tmp.newFolder("src"), withData = true)
        val dst = Env(tmp.newFolder("dst"), withData = false)

        ImportApplier().apply(readArchive(export(src)), dst.targets())

        val restored = app.walkquest.domain.achievements.EarnedStore(dst.earnedFile)
        assertTrue(restored.contains("A1"))
    }

    /** Минимальный zip с заданными entry (DEFLATED). */
    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}

/** Валидная биткарта: JSON-заголовок + '\n' + бинарный payload (как пишет FogRepository). */
private fun fogBytesValid(): ByteArray {
    val header = """{"formatVersion":1,"regionId":"spb","cols":4,"rows":4,"tileSizeMeters":25,"origin":[30.0,59.0],"order":["row-major","lsb-first"]}"""
    return header.toByteArray() + byteArrayOf('\n'.code.toByte()) + byteArrayOf(3, 1, 0, 7)
}
