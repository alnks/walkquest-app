package app.walkquest.data.packages

/**
 * Конфигурация источника пакетов (история 1.5, URL-схема 1.4).
 *
 * Пакеты опубликованы в GitHub Releases (тег `packages-v1`) по
 * `tools/region-pack/publish.md`; [DEFAULT_BASE_URL] указывает на релиз —
 * это единственная правка в коде при смене места публикации.
 */
object PackageSource {

    const val DEFAULT_BASE_URL = "https://github.com/alnks/walkquest-packages/releases/download/packages-v1"

    /** Единственная поддерживаемая версия формата пакета (spine Consistency Conventions). */
    const val SUPPORTED_FORMAT_VERSION = 1

    fun manifestUrl(baseUrl: String): String = "${baseUrl.trimEnd('/')}/manifest.json"

    fun packageUrl(baseUrl: String, file: String): String = "${baseUrl.trimEnd('/')}/$file"

    /**
     * Debug-override источника пакетов (QA-контур, `_qa/wq.py push-base-url`).
     *
     * В DEBUG-сборке, если существует файл `files/qa/packages-base-url.txt`
     * (подкладывается через `run-as`), источник пакетов — URL из него:
     * так полный путь «манифест → скачивание → проверка → установка»
     * проверяется локальным сервером `wq.py serve` без публикации на GitHub.
     * В release-сборке файл игнорируется всегда. Невалидное содержимое
     * (не http/https) тоже игнорируется — возврат к [DEFAULT_BASE_URL].
     *
     * [debugEnabled] — флаг debuggable-сборки из Android-слоя
     * (`ApplicationInfo.FLAG_DEBUGGABLE`); чистый data-слой BuildConfig не читает.
     * В debug-режиме `files/qa/packages-base-url.txt` переопределяет источник
     * пакетов на локальный сервер `_qa/wq.py serve` (QA-контур без публикации).
     */
    fun effectiveBaseUrl(
        filesDir: java.io.File,
        debugEnabled: Boolean = false,
    ): String {
        if (!debugEnabled) return DEFAULT_BASE_URL
        val file = java.io.File(filesDir, "qa/packages-base-url.txt")
        val url = runCatching { file.readText().trim() }.getOrNull().orEmpty()
        return if (url.startsWith("http://") || url.startsWith("https://")) url else DEFAULT_BASE_URL
    }
}
