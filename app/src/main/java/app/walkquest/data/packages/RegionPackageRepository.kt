package app.walkquest.data.packages

import app.walkquest.logging.WqLayer
import app.walkquest.logging.WqLog
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Репозиторий пакетов регионов (история 1.5, AD-2, AD-6).
 *
 * Единая точка работы с пакетами для domain: установка по URL-схеме 1.4
 * (манифест → выбор региона → zip → верификация → атомарная распаковка),
 * чтение установленных — только с диска, без сети (офлайн-first).
 *
 * Чистый JVM-код: корень хранилища инжектируется (`File(context.filesDir, "packages")`
 * из AppContainer), поэтому весь класс гоняется локальными unit-тестами.
 */
class RegionPackageRepository(
    private val packagesRoot: File,
    /** Источник пакетов (1.5); читается debug-оверлеем карты (1.14). */
    val baseUrl: String = PackageSource.DEFAULT_BASE_URL,
    private val downloader: PackageDownloader = HttpPackageDownloader(),
) {

    private val installer = PackageInstaller(packagesRoot)
    private val installMutex = Mutex()

    /**
     * Скачивает и устанавливает пакет региона. Идемпотентно: уже установленная
     * версия (по манифесту) не скачивается повторно. Ошибки — только внутри
     * [PackageInstallResult.Failure], исключения через границу не летят.
     */
    suspend fun installRegion(regionId: String): PackageInstallResult = withContext(Dispatchers.IO) {
        installMutex.withLock {
            val tempFile = File(packagesRoot, ".dl-${UUID.randomUUID()}")
            try {
                packagesRoot.mkdirs()

                val manifest = when (val fetched = fetchManifest(tempFile)) {
                    is ManifestFetch.Err -> return@withLock PackageInstallResult.Failure(fetched.error)
                    is ManifestFetch.Ok -> fetched.manifest
                }
                val entry = manifest.packages.find { it.regionId == regionId }
                    ?: return@withLock PackageInstallResult.Failure(PackageError.RegionNotFound(regionId))

                PackageVerifier.verifyFormatVersion(entry.formatVersion)
                    ?.let { return@withLock PackageInstallResult.Failure(it) }

                getInstalled(regionId)?.takeIf { it.packageVersion == entry.packageVersion }
                    ?.let { return@withLock PackageInstallResult.AlreadyInstalled(it) }

                try {
                    downloader.download(PackageSource.packageUrl(baseUrl, entry.file), tempFile)
                } catch (e: IOException) {
                    return@withLock PackageInstallResult.Failure(
                        PackageError.NetworkError(PackageSource.packageUrl(baseUrl, entry.file), e.message ?: e.toString())
                    )
                }

                PackageVerifier.verifyArchive(tempFile, entry.sizeBytes, entry.sha256)
                    ?.let { return@withLock PackageInstallResult.Failure(it) }

                installer.install(tempFile)
            } finally {
                tempFile.delete()
            }
        }
    }

    /** Установленные пакеты — только чтение с диска (AD-2: офлайн после установки). */
    fun installedPackages(): List<InstalledPackage> {
        val root = packagesRoot.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: return emptyList()
        return root.flatMap { regionDir ->
            regionDir.listFiles { f -> f.isDirectory && f.name.startsWith("v") }
                ?.mapNotNull(::readInstalled)
                .orEmpty()
        }
    }

    /**
     * Скачивает манифест пакетов и обновляет дисковый кэш, ничего не устанавливая
     * (первый запуск без регионов, дефект D1 прогона 2026-10-05: выбор домашнего
     * региона до первой установки). null при сетевой/форматной ошибке —
     * предложение первого региона просто не рождается.
     */
    suspend fun fetchManifestPreview(): PackageManifest? = withContext(Dispatchers.IO) {
        val tempFile = File(packagesRoot, ".mf-${UUID.randomUUID()}")
        try {
            packagesRoot.mkdirs()
            when (val fetched = fetchManifest(tempFile)) {
                is ManifestFetch.Ok -> fetched.manifest
                is ManifestFetch.Err -> null
            }
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Последний успешно скачанный манифест — кэш на диске (история 1.10): детект
     * «локация в неустановленном регионе» по bbox записей работает офлайн (AD-2).
     * Только чтение с диска; битый кэш → null (детект молча не срабатывает).
     */
    fun cachedManifest(): PackageManifest? {
        val file = File(packagesRoot, MANIFEST_CACHE_FILE)
        if (!file.isFile) {
            WqLog.v(WqLayer.CACHE) { "manifest MISS (no cache file)" }
            return null
        }
        return try {
            PackageJson.decodeFromString<PackageManifest>(file.readText()).also {
                WqLog.v(WqLayer.CACHE) { "manifest HIT (disk) packages=${it.packages.size}" }
            }
        } catch (e: Exception) {
            WqLog.w(WqLayer.CACHE) { "manifest cache parse FAILED: ${e.message}" }
            null
        }
    }

    /** Последняя установленная версия региона или null. Чтение только с диска. */
    fun getInstalled(regionId: String): InstalledPackage? =
        installedPackages().filter { it.regionId == regionId }.maxByOrNull { it.packageVersion }

    private sealed class ManifestFetch {
        data class Ok(val manifest: PackageManifest) : ManifestFetch()
        data class Err(val error: PackageError) : ManifestFetch()
    }

    private companion object {
        /** Имя кэша манифеста пакетов рядом с пакетами (не папка — installedPackages его не видит). */
        const val MANIFEST_CACHE_FILE = "manifest-cache.json"
    }

    /** Скачивает и парсит манифест пакетов. */
    private fun fetchManifest(tempFile: File): ManifestFetch {
        val url = PackageSource.manifestUrl(baseUrl)
        val started = System.nanoTime()
        WqLog.d(WqLayer.NET) { "→ GET manifest $url" }
        try {
            downloader.download(url, tempFile)
        } catch (e: IOException) {
            WqLog.e(WqLayer.NET, e) { "← GET manifest FAILED (${elapsedMs(started)} ms)" }
            return ManifestFetch.Err(PackageError.NetworkError(url, e.message ?: e.toString()))
        }
        return try {
            val manifest = PackageJson.decodeFromString<PackageManifest>(tempFile.readText())
            if (manifest.packages.isEmpty()) {
                WqLog.w(WqLayer.NET) { "← GET manifest → пустой список packages" }
                ManifestFetch.Err(PackageError.ManifestInvalid("список packages пуст"))
            } else {
                // Кэш манифеста рядом с пакетами (1.10): детект чужого региона офлайн.
                // Сбой записи кэша установку не ломает — это не критичный артефакт.
                try {
                    packagesRoot.mkdirs()
                    File(packagesRoot, MANIFEST_CACHE_FILE).writeText(tempFile.readText())
                    WqLog.d(WqLayer.CACHE) {
                        "manifest cache WRITE packages=${manifest.packages.size} (${elapsedMs(started)} ms)"
                    }
                } catch (e: Exception) {
                    // Кэш перезапишется при следующем успешном скачивании манифеста.
                    WqLog.w(WqLayer.CACHE) { "manifest cache WRITE failed: ${e.message}" }
                }
                WqLog.d(WqLayer.NET) {
                    "← GET manifest → OK packages=${manifest.packages.size} (${elapsedMs(started)} ms)"
                }
                ManifestFetch.Ok(manifest)
            }
        } catch (e: Exception) {
            WqLog.e(WqLayer.NET, e) { "← GET manifest → parse FAILED (${elapsedMs(started)} ms)" }
            ManifestFetch.Err(PackageError.ManifestInvalid(e.message ?: e.toString()))
        }
    }

    /** Читает meta.json установленного пакета; битая запись пропускается (не роняет чтение остальных). */
    private fun readInstalled(versionDir: File): InstalledPackage? {
        val metaFile = File(versionDir, "meta.json")
        if (!metaFile.isFile) return null
        return try {
            val meta = PackageJson.decodeFromString<PackageMeta>(metaFile.readText())
            InstalledPackage(meta.regionId, meta.packageVersion, meta.formatVersion, versionDir)
        } catch (e: Exception) {
            null
        }
    }
}

/** Миллисекунды от метки System.nanoTime() для логов запрос-ответ. */
private fun elapsedMs(startedNanos: Long): Long =
    (System.nanoTime() - startedNanos) / 1_000_000
