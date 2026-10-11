package app.walkquest.data.packages

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Debug-override источника пакетов (QA-контур `_qa/wq.py push-base-url`):
 * файл `qa/packages-base-url.txt` читается только в debug-режиме,
 * невалидное содержимое игнорируется.
 */
class PackageSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `no override file — default base url`() {
        assertEquals(
            PackageSource.DEFAULT_BASE_URL,
            PackageSource.effectiveBaseUrl(tmp.root, debugEnabled = true),
        )
    }

    @Test
    fun `override file wins in debug mode`() {
        val qa = tmp.newFolder("qa")
        java.io.File(qa, "packages-base-url.txt").writeText("http://10.0.2.2:8777")
        assertEquals(
            "http://10.0.2.2:8777",
            PackageSource.effectiveBaseUrl(tmp.root, debugEnabled = true),
        )
    }

    @Test
    fun `override ignored in release mode`() {
        val qa = tmp.newFolder("qa")
        java.io.File(qa, "packages-base-url.txt").writeText("http://10.0.2.2:8777")
        assertEquals(
            PackageSource.DEFAULT_BASE_URL,
            PackageSource.effectiveBaseUrl(tmp.root, debugEnabled = false),
        )
    }

    @Test
    fun `garbage in override file falls back to default`() {
        val qa = tmp.newFolder("qa")
        java.io.File(qa, "packages-base-url.txt").writeText("10.0.2.2:8777\n")
        assertEquals(
            PackageSource.DEFAULT_BASE_URL,
            PackageSource.effectiveBaseUrl(tmp.root, debugEnabled = true),
        )
    }

    @Test
    fun `override url is trimmed of whitespace`() {
        val qa = tmp.newFolder("qa")
        java.io.File(qa, "packages-base-url.txt").writeText("  http://localhost:8777 \n")
        assertEquals(
            "http://localhost:8777",
            PackageSource.effectiveBaseUrl(tmp.root, debugEnabled = true),
        )
    }
}
