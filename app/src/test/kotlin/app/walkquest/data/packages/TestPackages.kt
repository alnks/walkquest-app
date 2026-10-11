package app.walkquest.data.packages

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Тестовая сборка пакетов региона: zip той же структуры, что выдаёт
 * tools/region-pack (история 1.3), с честными или намеренно битыми checksums.
 */
object TestPackages {

    const val PLACEHOLDER = "pmtiles://tiles.pmtiles"

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Файлы валидного пакета (без meta.json — он собирается отдельно, т.к. содержит checksums). */
    fun packageFiles(stylePlaceholder: String = PLACEHOLDER): LinkedHashMap<String, ByteArray> =
        linkedMapOf(
            "style.json" to """{"version":8,"sources":{"region":{"type":"vector","url":"$stylePlaceholder"}},"layers":[]}"""
                .toByteArray(),
            "tiles.pmtiles" to ByteArray(64) { it.toByte() },
            "walkability.grid" to ByteArray(16) { 1 },
            "boundaries.geojson" to """{"type":"FeatureCollection","features":[]}""".toByteArray(),
            "poi.json" to "[]".toByteArray(),
        )

    fun metaJson(
        files: Map<String, ByteArray>,
        formatVersion: Int = 1,
        regionId: String = "spb-city",
        packageVersion: Int = 1,
        corruptChecksumFor: String? = null,
    ): ByteArray {
        val checksums = files.entries.joinToString(",") { (name, bytes) ->
            val sha = if (name == corruptChecksumFor) "0".repeat(64) else sha256Hex(bytes)
            "\"$name\":\"$sha\""
        }
        return ("""{"formatVersion":$formatVersion,"regionId":"$regionId","packageVersion":$packageVersion,""" +
            """"tileSizeMeters":100.0,"maxzoom":14,"pmtilesUrlPlaceholder":"$PLACEHOLDER",""" +
            """"generatedBy":"test","checksums":{$checksums}}""").toByteArray()
    }

    /** Собирает zip пакета в [outFile]; возвращает его же. */
    fun buildZip(
        outFile: File,
        formatVersion: Int = 1,
        regionId: String = "spb-city",
        packageVersion: Int = 1,
        corruptChecksumFor: String? = null,
        stylePlaceholder: String = PLACEHOLDER,
    ): File {
        val files = packageFiles(stylePlaceholder)
        val all = LinkedHashMap(files)
        all["meta.json"] = metaJson(files, formatVersion, regionId, packageVersion, corruptChecksumFor)
        ZipOutputStream(outFile.outputStream().buffered()).use { zip ->
            for ((name, bytes) in all) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return outFile
    }

    /** Текст manifest.json (URL-схема 1.4) для готового zip-файла. */
    fun manifestJson(
        zipFile: File,
        baseUrl: String,
        regionId: String = "spb-city",
        packageVersion: Int = 1,
        formatVersion: Int = 1,
        sha256Override: String? = null,
    ): ByteArray {
        val fileName = "region-$regionId-v$packageVersion.zip"
        val sha = sha256Override ?: sha256Hex(zipFile.readBytes())
        return ("""{"baseUrl":"$baseUrl","packages":[{"regionId":"$regionId","packageVersion":$packageVersion,""" +
            """"formatVersion":$formatVersion,"file":"$fileName","sizeBytes":${zipFile.length()},"sha256":"$sha"}]}""")
            .toByteArray()
    }
}
