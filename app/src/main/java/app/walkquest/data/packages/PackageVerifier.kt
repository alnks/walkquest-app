package app.walkquest.data.packages

import java.io.File
import java.security.MessageDigest

/**
 * Верификация пакетов (история 1.5): sha256, версия формата, контрольные суммы.
 * Чистый JVM-код без Android — гоняется локальными unit-тестами.
 * Все проверки возвращают [PackageError]? (null = проверка пройдена).
 */
object PackageVerifier {

    /** Потоковый sha256 файла, hex в нижнем регистре (формат meta.json/manifest из 1.3–1.4). */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Пакет с formatVersion ≠ поддерживаемой отклоняется (spine: формат версионируется с первого релиза). */
    fun verifyFormatVersion(found: Int): PackageError? =
        if (found == PackageSource.SUPPORTED_FORMAT_VERSION) {
            null
        } else {
            PackageError.UnsupportedFormatVersion(found, PackageSource.SUPPORTED_FORMAT_VERSION)
        }

    /** Размер и sha256 скачанного архива против записи манифеста. */
    fun verifyArchive(archive: File, expectedSizeBytes: Long, expectedSha256: String): PackageError? {
        val actualSize = archive.length()
        if (actualSize != expectedSizeBytes) {
            return PackageError.ArchiveSizeMismatch(expectedSizeBytes, actualSize)
        }
        val actualSha = sha256(archive)
        if (!actualSha.equals(expectedSha256, ignoreCase = true)) {
            return PackageError.ArchiveChecksumMismatch(expectedSha256, actualSha)
        }
        return null
    }

    /**
     * sha256 каждого файла из `meta.json.checksums` против распакованного содержимого.
     * Файлы архива, не перечисленные в checksums, игнорируются (запас на будущие formatVersion).
     */
    fun verifyExtractedFiles(dir: File, checksums: Map<String, String>): PackageError? {
        if (checksums.isEmpty()) {
            return PackageError.PackageInvalid("meta.json не содержит контрольных сумм файлов")
        }
        for ((name, expectedSha) in checksums) {
            val file = File(dir, name)
            if (!file.isFile) {
                return PackageError.PackageInvalid("файл «$name» из meta.json отсутствует в архиве")
            }
            val actualSha = sha256(file)
            if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                return PackageError.FileChecksumMismatch(name)
            }
        }
        return null
    }
}
