package app.walkquest.data.packages

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Минимальный HTTP-сервер на ServerSocket для тестов репозитория.
 * Заменяет com.sun.net.httpserver.HttpServer (модуль jdk.httpserver не виден
 * kotlinc при компиляции unit-тестов Android-модуля). Отвечает последовательно,
 * каждое соединение закрывается (Connection: close) — этого хватает потоку
 * «манифест → zip» HttpPackageDownloader.
 */
private class MiniHttpServer(
    private val manifest: ByteArray,
    private val zip: File,
    private val zipName: String,
    private val zipHits: AtomicInteger,
) {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    @Volatile private var running = true

    private val thread = Thread {
        while (running) {
            try {
                handle(socket.accept())
            } catch (e: SocketException) {
                if (running) throw e // закрытие сокета в stop() — штатный выход
            }
        }
    }.apply { isDaemon = true; start() }

    val baseUrl: String get() = "http://127.0.0.1:${socket.localPort}"

    private fun handle(client: Socket) {
        client.use { c ->
            val reader = c.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            while (true) { // дочитываем заголовки запроса
                val line = reader.readLine()
                if (line.isNullOrEmpty()) break
            }
            val path = requestLine.split(' ').getOrNull(1) ?: return
            val (code, body) = when (path) {
                "/manifest.json" -> 200 to manifest
                "/$zipName" -> {
                    zipHits.incrementAndGet()
                    200 to zip.readBytes()
                }
                else -> 404 to ByteArray(0)
            }
            val status = if (code == 200) "200 OK" else "404 Not Found"
            val out = c.getOutputStream()
            out.write("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(body)
            out.flush()
        }
    }

    fun stop() {
        running = false
        socket.close()
    }
}

/**
 * JVM-тесты полного потока «манифест → zip → проверка → установка» (история 1.5)
 * на локальном HTTP-сервере — URL-схема 1.4 без устройства и без SDK.
 * Поле baseUrl внутри manifest.json приложением не используется (Design Notes плана),
 * поэтому в тестах оно заполнено заглушкой.
 * Запуск: `gradlew.bat testDebugUnitTest` (на машине без JDK — прогон HITL).
 */
class RegionPackageRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var server: MiniHttpServer? = null
    private val zipHits = AtomicInteger(0)

    @After
    fun tearDown() {
        server?.stop()
    }

    /** Поднимает локальный HTTP-сервер с манифестом и zip по URL-схеме 1.4; возвращает baseUrl. */
    private fun startServer(
        zip: File,
        formatVersion: Int = 1,
        sha256Override: String? = null,
        zipName: String = "region-spb-city-v1.zip",
    ): String {
        zipHits.set(0)
        val manifest = TestPackages.manifestJson(
            zip, baseUrl = "http://unused.invalid", formatVersion = formatVersion, sha256Override = sha256Override,
        )
        val http = MiniHttpServer(manifest, zip, zipName, zipHits)
        server = http
        return http.baseUrl
    }

    private fun repository(root: File, baseUrl: String) =
        RegionPackageRepository(packagesRoot = root, baseUrl = baseUrl)

    @Test
    fun `install success — verified files on disk, absolute pmtiles path, offline readable`() = runBlocking {
        val root = temp.newFolder("packages")
        val zip = TestPackages.buildZip(temp.newFile("region-spb-city-v1.zip"))
        val baseUrl = startServer(zip)

        val result = repository(root, baseUrl).installRegion("spb-city")

        assertTrue(result is PackageInstallResult.Success)
        val installed = (result as PackageInstallResult.Success).installed
        assertEquals(File(root, "spb-city/v1").canonicalPath, installed.rootDir.canonicalPath)
        assertTrue(installed.styleFile.readText().contains("pmtiles://file://" + installed.pmtilesFile.absolutePath))
        assertEquals(1, zipHits.get())

        // Офлайн-чтение (AD-2): сервер остановлен — репозиторий отдаёт пакет с диска.
        server?.stop()
        val offline = repository(root, baseUrl)
        assertEquals(listOf("spb-city"), offline.installedPackages().map { it.regionId })
        assertNotNull(offline.getInstalled("spb-city"))
    }

    @Test
    fun `reinstall of same version is AlreadyInstalled without re-download`() = runBlocking {
        val root = temp.newFolder("packages")
        val zip = TestPackages.buildZip(temp.newFile("region-spb-city-v1.zip"))
        val baseUrl = startServer(zip)
        val repo = repository(root, baseUrl)

        assertTrue(repo.installRegion("spb-city") is PackageInstallResult.Success)
        val second = repo.installRegion("spb-city")

        assertTrue(second is PackageInstallResult.AlreadyInstalled)
        assertEquals("zip скачан ровно один раз", 1, zipHits.get())
    }

    @Test
    fun `unknown region is rejected with RegionNotFound`() = runBlocking {
        val root = temp.newFolder("packages")
        val zip = TestPackages.buildZip(temp.newFile("region-spb-city-v1.zip"))
        val baseUrl = startServer(zip)

        val result = repository(root, baseUrl).installRegion("moscow")

        assertTrue(result is PackageInstallResult.Failure)
        assertTrue((result as PackageInstallResult.Failure).error is PackageError.RegionNotFound)
        assertEquals(0, zipHits.get())
    }

    @Test
    fun `unsupported formatVersion in manifest is rejected before download`() = runBlocking {
        val root = temp.newFolder("packages")
        val zip = TestPackages.buildZip(temp.newFile("region-spb-city-v1.zip"), formatVersion = 2)
        val baseUrl = startServer(zip, formatVersion = 2)

        val result = repository(root, baseUrl).installRegion("spb-city")

        assertTrue(result is PackageInstallResult.Failure)
        val error = (result as PackageInstallResult.Failure).error
        assertTrue(error is PackageError.UnsupportedFormatVersion)
        assertEquals(2, (error as PackageError.UnsupportedFormatVersion).found)
        assertTrue(error.message.isNotBlank())
        assertEquals("zip не скачивался", 0, zipHits.get())
        assertTrue(repository(root, baseUrl).installedPackages().isEmpty())
    }

    @Test
    fun `archive checksum mismatch is rejected, nothing installed`() = runBlocking {
        val root = temp.newFolder("packages")
        val zip = TestPackages.buildZip(temp.newFile("region-spb-city-v1.zip"))
        val baseUrl = startServer(zip, sha256Override = "0".repeat(64))

        val result = repository(root, baseUrl).installRegion("spb-city")

        assertTrue(result is PackageInstallResult.Failure)
        assertTrue((result as PackageInstallResult.Failure).error is PackageError.ArchiveChecksumMismatch)
        assertTrue(repository(root, baseUrl).installedPackages().isEmpty())
        assertTrue("временных файлов не осталось", root.listFiles().orEmpty().none { it.name.startsWith(".") })
    }

    @Test
    fun `corrupted zip from network is rejected with ArchiveCorrupted`() = runBlocking {
        val root = temp.newFolder("packages")
        val garbage = temp.newFile("garbage.bin").apply { writeBytes("definitely not a zip".toByteArray()) }
        // Манифест честный (sha/size от мусора) — отказ должен случиться на распаковке.
        val baseUrl = startServer(garbage)

        val result = repository(root, baseUrl).installRegion("spb-city")

        assertTrue(result is PackageInstallResult.Failure)
        assertTrue((result as PackageInstallResult.Failure).error is PackageError.ArchiveCorrupted)
        assertTrue(repository(root, baseUrl).installedPackages().isEmpty())
    }

    @Test
    fun `network failure is reported as NetworkError`() = runBlocking {
        val root = temp.newFolder("packages")
        // Порт, на котором никто не слушает.
        val deadBase = "http://127.0.0.1:1"

        val result = repository(root, deadBase).installRegion("spb-city")

        assertTrue(result is PackageInstallResult.Failure)
        assertTrue((result as PackageInstallResult.Failure).error is PackageError.NetworkError)
    }

    @Test
    fun `newer package version installs over the older one (update from Regions tab)`() = runBlocking {
        val root = temp.newFolder("packages")
        val zipV1 = TestPackages.buildZip(temp.newFile("region-spb-city-v1.zip"), packageVersion = 1)
        val baseUrl = startServer(zipV1)
        val repo = repository(root, baseUrl)
        assertTrue(repo.installRegion("spb-city") is PackageInstallResult.Success)
        assertEquals(1, repo.getInstalled("spb-city")?.packageVersion)

        // Манифест обновился: версия 2 → установка идёт, AlreadyInstalled не срабатывает.
        server?.stop()
        val zipV2 = TestPackages.buildZip(temp.newFile("region-spb-city-v2.zip"), packageVersion = 2)
        val manifestV2 = TestPackages.manifestJson(zipV2, baseUrl = "http://unused.invalid", packageVersion = 2)
        val http2 = MiniHttpServer(manifestV2, zipV2, "region-spb-city-v2.zip", zipHits)
        server = http2
        zipHits.set(0)

        val result = repository(root, http2.baseUrl).installRegion("spb-city")

        assertTrue(result is PackageInstallResult.Success)
        assertEquals(2, (result as PackageInstallResult.Success).installed.packageVersion)
        assertEquals("zip v2 скачан", 1, zipHits.get())
        assertEquals(2, repository(root, http2.baseUrl).getInstalled("spb-city")?.packageVersion)
    }
}
