package app.walkquest.data.packages

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Скачивание файла по HTTP в локальный файл. Интерфейс, чтобы тесты и будущие
 * реализации (прогресс, резюмирование) подменялись без правок репозитория.
 */
interface PackageDownloader {
    /** Скачивает [url] в [dest] (перезаписывает). Возвращает число записанных байт. */
    @Throws(IOException::class)
    fun download(url: String, dest: File): Long
}

/**
 * Реализация на HttpURLConnection: spine не пинит OkHttp, новых сетевых
 * зависимостей не вводим. Чистый JVM-код — работает и в unit-тестах.
 */
class HttpPackageDownloader(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : PackageDownloader {

    @Throws(IOException::class)
    override fun download(url: String, dest: File): Long {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = true
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP $code")
            }
            connection.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        total += read
                    }
                    return total
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
