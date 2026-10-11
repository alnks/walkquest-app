package app.walkquest.data.packages

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM-тесты верификации и установки пакета (история 1.5, матрица краёв).
 * Запуск: `gradlew.bat testDebugUnitTest` (на машине без JDK — прогон HITL).
 */
class PackageVerifierTest {

    @get:Rule
    val temp = TemporaryFolder()

    // --- PackageVerifier: примитивы ---

    @Test
    fun `sha256 matches known digest`() {
        val file = temp.newFile("a.bin").apply { writeBytes("abc".toByteArray()) }
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            PackageVerifier.sha256(file),
        )
    }

    @Test
    fun `supported formatVersion passes, unsupported is rejected`() {
        assertNull(PackageVerifier.verifyFormatVersion(1))
        val error = PackageVerifier.verifyFormatVersion(2)
        assertTrue(error is PackageError.UnsupportedFormatVersion)
        assertEquals(2, (error as PackageError.UnsupportedFormatVersion).found)
        assertEquals(PackageSource.SUPPORTED_FORMAT_VERSION, error.supported)
        assertTrue(error.message.isNotBlank())
    }

    @Test
    fun `archive verification — ok, size mismatch, checksum mismatch`() {
        val zip = TestPackages.buildZip(temp.newFile("ok.zip"))

        assertNull(PackageVerifier.verifyArchive(zip, zip.length(), TestPackages.sha256Hex(zip.readBytes())))
        assertTrue(
            PackageVerifier.verifyArchive(zip, zip.length() + 1, TestPackages.sha256Hex(zip.readBytes()))
                is PackageError.ArchiveSizeMismatch,
        )
        assertTrue(
            PackageVerifier.verifyArchive(zip, zip.length(), "0".repeat(64))
                is PackageError.ArchiveChecksumMismatch,
        )
    }

    @Test
    fun `extracted files verification — ok, missing file, checksum mismatch, empty checksums`() {
        val dir = temp.newFolder("pkg")
        val files = TestPackages.packageFiles()
        files.forEach { (name, bytes) -> File(dir, name).writeBytes(bytes) }
        val checksums = files.mapValues { TestPackages.sha256Hex(it.value) }

        assertNull(PackageVerifier.verifyExtractedFiles(dir, checksums))
        assertTrue(
            PackageVerifier.verifyExtractedFiles(dir, checksums - "poi.json" + ("poi.json" to "0".repeat(64)))
                is PackageError.FileChecksumMismatch,
        )
        assertTrue(
            PackageVerifier.verifyExtractedFiles(dir, checksums + ("missing.bin" to "0".repeat(64)))
                is PackageError.PackageInvalid,
        )
        assertTrue(PackageVerifier.verifyExtractedFiles(dir, emptyMap()) is PackageError.PackageInvalid)
    }

    // --- PackageInstaller: установка и отказы ---

    private fun installer(root: File = temp.newFolder("packages")) = PackageInstaller(root)

    @Test
    fun `valid package installs and style json gets absolute pmtiles path`() {
        val root = temp.newFolder("packages")
        val zip = TestPackages.buildZip(temp.newFile("region-spb-city-v1.zip"))

        val result = installer(root).install(zip)

        assertTrue(result is PackageInstallResult.Success)
        val installed = (result as PackageInstallResult.Success).installed
        assertEquals("spb-city", installed.regionId)
        assertEquals(1, installed.packageVersion)
        assertTrue(installed.pmtilesFile.isFile)
        assertTrue(installed.walkabilityFile.isFile)

        val style = installed.styleFile.readText()
        val expectedUrl = "pmtiles://file://" + installed.pmtilesFile.absolutePath
        assertTrue("style.json содержит абсолютный pmtiles-путь", style.contains(expectedUrl))
        assertFalse("плейсхолдер заменён", style.contains(TestPackages.PLACEHOLDER))
    }

    @Test
    fun `corrupted zip is rejected with ArchiveCorrupted`() {
        val root = temp.newFolder("packages")
        val garbage = temp.newFile("broken.zip").apply { writeBytes("not a zip at all".toByteArray()) }

        val result = installer(root).install(garbage)

        assertTrue(result is PackageInstallResult.Failure)
        assertTrue((result as PackageInstallResult.Failure).error is PackageError.ArchiveCorrupted)
    }

    @Test
    fun `truncated zip is rejected with ArchiveCorrupted`() {
        val root = temp.newFolder("packages")
        val zip = TestPackages.buildZip(temp.newFile("full.zip"))
        val truncated = temp.newFile("truncated.zip")
        val bytes = zip.readBytes()
        truncated.writeBytes(bytes.copyOfRange(0, bytes.size / 2))

        val result = installer(root).install(truncated)

        assertTrue(result is PackageInstallResult.Failure)
        assertTrue((result as PackageInstallResult.Failure).error is PackageError.ArchiveCorrupted)
    }

    @Test
    fun `file checksum mismatch from meta json is rejected`() {
        val root = temp.newFolder("packages")
        val zip = TestPackages.buildZip(temp.newFile("bad-file.zip"), corruptChecksumFor = "tiles.pmtiles")

        val result = installer(root).install(zip)

        assertTrue(result is PackageInstallResult.Failure)
        val error = (result as PackageInstallResult.Failure).error
        assertTrue(error is PackageError.FileChecksumMismatch)
        assertEquals("tiles.pmtiles", (error as PackageError.FileChecksumMismatch).file)
    }

    @Test
    fun `unsupported formatVersion from meta json is rejected`() {
        val root = temp.newFolder("packages")
        val zip = TestPackages.buildZip(temp.newFile("v2.zip"), formatVersion = 2)

        val result = installer(root).install(zip)

        assertTrue(result is PackageInstallResult.Failure)
        assertTrue((result as PackageInstallResult.Failure).error is PackageError.UnsupportedFormatVersion)
    }

    @Test
    fun `failed install leaves no target dir and no temp dirs behind`() {
        val root = temp.newFolder("packages")
        val zip = TestPackages.buildZip(temp.newFile("bad.zip"), formatVersion = 99)

        installer(root).install(zip)

        val leftovers = root.listFiles().orEmpty().toList()
        assertTrue("после отказа хранилище пусто: $leftovers", leftovers.isEmpty())
    }
}
