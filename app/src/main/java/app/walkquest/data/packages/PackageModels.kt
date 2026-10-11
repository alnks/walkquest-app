package app.walkquest.data.packages

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Контракты data/packages (история 1.5). Поля моделей сверены с фактическими
 * `tools/region-pack/out/region-spb-city-v1.zip` (meta.json, история 1.3)
 * и шаблоном manifest.json из `tools/region-pack/publish.md` (история 1.4).
 */

/** Единый JSON-парсер слоя: неизвестные поля игнорируем (meta.json растёт между formatVersion). */
val PackageJson: Json = Json { ignoreUnknownKeys = true }

/** Стабильный индекс `<BASE>/manifest.json` (URL-схема 1.4). */
@Serializable
data class PackageManifest(
    val baseUrl: String = "",
    val packages: List<ManifestPackage> = emptyList(),
)

/** Запись манифеста о одном пакете: `<BASE>/<file>` даёт URL архива.
 *  [bbox] и [name] — опциональные поля (история 1.10): bbox ([minLon, minLat, maxLon, maxLat])
 *  нужен детекту «локация в неустановленном регионе» без скачивания пакета, name — подписи
 *  региона в UI; старые манифесты без них валидны (ignoreUnknownKeys + дефолты). */
@Serializable
data class ManifestPackage(
    val regionId: String,
    val packageVersion: Int,
    val formatVersion: Int,
    val file: String,
    val sizeBytes: Long,
    val sha256: String,
    val bbox: List<Double>? = null,
    val name: String? = null,
)

/**
 * meta.json из архива пакета. Парсим только поля, нужные установщику и
 * туману (bbox — сетка dx в 1.6); остальное (grid, wikipedia, …) читают другие истории.
 */
@Serializable
data class PackageMeta(
    val formatVersion: Int,
    val regionId: String,
    val packageVersion: Int,
    val tileSizeMeters: Double? = null,
    val maxzoom: Int? = null,
    val bbox: List<Double>? = null,
    val pmtilesUrlPlaceholder: String = "pmtiles://tiles.pmtiles",
    val checksums: Map<String, String> = emptyMap(),
)

/** Установленный пакет: запись на диске `packages/<regionId>/v<packageVersion>/`. */
data class InstalledPackage(
    val regionId: String,
    val packageVersion: Int,
    val formatVersion: Int,
    val rootDir: File,
) {
    val styleFile: File get() = File(rootDir, "style.json")
    val pmtilesFile: File get() = File(rootDir, "tiles.pmtiles")
    val walkabilityFile: File get() = File(rootDir, "walkability.grid")
    val boundariesFile: File get() = File(rootDir, "boundaries.geojson")
    val poiFile: File get() = File(rootDir, "poi.json")
    val metaFile: File get() = File(rootDir, "meta.json")
    /**
     * Водяная маска (опционально, разбор 2026-10-05): формат идентичен walkability.grid
     * v0, бит 1 = вода. У пакетов v1/v2 файла нет — читатели обязаны терпеть отсутствие.
     */
    val waterFile: File get() = File(rootDir, "water.grid")
}

/** Типизированные ошибки слоя (Consistency Conventions: sealed-результаты, не исключения через границы). */
sealed class PackageError {
    abstract val message: String

    data class NetworkError(val url: String, val detail: String) : PackageError() {
        override val message get() = "Не удалось скачать $url: $detail"
    }

    data class ManifestInvalid(val detail: String) : PackageError() {
        override val message get() = "Манифест пакетов повреждён или имеет неожиданный формат: $detail"
    }

    data class RegionNotFound(val regionId: String) : PackageError() {
        override val message get() = "Регион «$regionId» отсутствует в манифесте пакетов"
    }

    data class UnsupportedFormatVersion(val found: Int, val supported: Int) : PackageError() {
        override val message get() =
            "Несовместимая версия формата пакета: $found (поддерживается: $supported). Обновите приложение."
    }

    data class ArchiveSizeMismatch(val expected: Long, val actual: Long) : PackageError() {
        override val message get() =
            "Архив пакета повреждён при скачивании: ожидалось $expected байт, получено $actual"
    }

    data class ArchiveChecksumMismatch(val expected: String, val actual: String) : PackageError() {
        override val message get() = "Контрольная сумма архива пакета не совпала — файл повреждён при скачивании"
    }

    data class FileChecksumMismatch(val file: String) : PackageError() {
        override val message get() = "Контрольная сумма файла «$file» внутри пакета не совпала — пакет повреждён"
    }

    data class ArchiveCorrupted(val detail: String) : PackageError() {
        override val message get() = "Архив пакета повреждён и не распаковывается: $detail"
    }

    data class PackageInvalid(val detail: String) : PackageError() {
        override val message get() = "Пакет региона имеет неожиданный состав: $detail"
    }

    data class StorageError(val detail: String) : PackageError() {
        override val message get() = "Ошибка локального хранилища при установке пакета: $detail"
    }
}

/** Результат установки пакета — единственный тип возврата через границу data → domain. */
sealed class PackageInstallResult {
    data class Success(val installed: InstalledPackage) : PackageInstallResult()
    data class AlreadyInstalled(val installed: InstalledPackage) : PackageInstallResult()
    data class Failure(val error: PackageError) : PackageInstallResult()
}
