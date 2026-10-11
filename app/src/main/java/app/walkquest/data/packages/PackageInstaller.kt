package app.walkquest.data.packages

import java.io.File
import java.util.UUID
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Атомарная установка пакета из уже скачанного и проверенного архива.
 *
 * Видимое состояние меняет один rename временной папки в
 * `packages/<regionId>/v<packageVersion>/`; при любой ошибке temp удаляется,
 * прежняя установка (если была) не трогается. Чистый JVM-код.
 */
class PackageInstaller(private val packagesRoot: File) {

    /**
     * Распаковывает [archive], проверяет meta.json и контрольные суммы файлов,
     * подставляет абсолютный pmtiles-путь в style.json и атомарно ставит пакет.
     */
    fun install(archive: File): PackageInstallResult {
        val tempDir = File(packagesRoot, ".install-${UUID.randomUUID()}")
        return try {
            extractZip(archive, tempDir)

            val metaFile = File(tempDir, "meta.json")
            if (!metaFile.isFile) {
                return failure(tempDir, PackageError.PackageInvalid("в архиве нет meta.json"))
            }
            val meta = try {
                PackageJson.decodeFromString<PackageMeta>(metaFile.readText())
            } catch (e: Exception) {
                return failure(tempDir, PackageError.PackageInvalid("meta.json не парсится: ${e.message}"))
            }

            PackageVerifier.verifyFormatVersion(meta.formatVersion)?.let { return failure(tempDir, it) }
            PackageVerifier.verifyExtractedFiles(tempDir, meta.checksums)?.let { return failure(tempDir, it) }

            val targetDir = File(packagesRoot, "${meta.regionId}/v${meta.packageVersion}")
            patchStylePmtilesUrl(tempDir, targetDir, meta.pmtilesUrlPlaceholder)
                ?.let { return failure(tempDir, it) }

            moveIntoPlace(tempDir, targetDir)?.let { return failure(tempDir, it) }

            PackageInstallResult.Success(
                InstalledPackage(meta.regionId, meta.packageVersion, meta.formatVersion, targetDir)
            )
        } catch (e: PackageInstallException) {
            PackageInstallResult.Failure(e.error)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /** Распаковка с защитой от zip-slip (записи с `../`). ZipFile валидирует центральный каталог. */
    private fun extractZip(archive: File, destDir: File) {
        val destCanonical = destDir.canonicalPath + File.separator
        try {
            ZipFile(archive).use { zipFile ->
                val entries = zipFile.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val outFile = File(destDir, entry.name)
                    if (!outFile.canonicalPath.startsWith(destCanonical)) {
                        throw ZipException("запись выходит за пределы папки распаковки: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        zipFile.getInputStream(entry).use { input ->
                            outFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
            }
        } catch (e: ZipException) {
            throw PackageInstallException(PackageError.ArchiveCorrupted(e.message ?: "некорректный zip"))
        } catch (e: java.io.IOException) {
            throw PackageInstallException(PackageError.ArchiveCorrupted(e.message ?: "ошибка чтения архива"))
        }
    }

    /**
     * Конвенция 1.3: style.json хранит плейсхолдер (`pmtiles://tiles.pmtiles`),
     * приложение при установке подставляет абсолютный `pmtiles://file://<путь>`
     * уже финального расположения tiles.pmtiles (MapLibre Native требует абсолютный).
     */
    private fun patchStylePmtilesUrl(dir: File, targetDir: File, placeholder: String): PackageError? {
        val styleFile = File(dir, "style.json")
        if (!styleFile.isFile) {
            return PackageError.PackageInvalid("в архиве нет style.json")
        }
        val original = styleFile.readText()
        if (!original.contains(placeholder)) {
            return PackageError.PackageInvalid("style.json не содержит плейсхолдер «$placeholder»")
        }
        val absoluteUrl = "pmtiles://file://" + File(targetDir, "tiles.pmtiles").absolutePath
        styleFile.writeText(original.replace(placeholder, absoluteUrl))
        return null
    }

    /** Замена целевой папки: удаление остатков прежней установки той же версии + rename. */
    private fun moveIntoPlace(tempDir: File, targetDir: File): PackageError? {
        return try {
            targetDir.parentFile?.mkdirs()
            if (targetDir.exists() && !targetDir.deleteRecursively()) {
                return PackageError.StorageError("не удалось удалить прежнюю папку ${targetDir.path}")
            }
            if (!tempDir.renameTo(targetDir)) {
                return PackageError.StorageError("не удалось переместить пакет в ${targetDir.path}")
            }
            null
        } catch (e: Exception) {
            PackageError.StorageError(e.message ?: e.toString())
        }
    }

    private fun failure(tempDir: File, error: PackageError): PackageInstallResult.Failure {
        tempDir.deleteRecursively()
        return PackageInstallResult.Failure(error)
    }
}

/** Внутреннее исключение распаковки; за границу data не выходит (конвертируется в Failure). */
internal class PackageInstallException(val error: PackageError) : RuntimeException(error.message)
