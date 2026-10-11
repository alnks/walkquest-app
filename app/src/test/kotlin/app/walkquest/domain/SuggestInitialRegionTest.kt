package app.walkquest.domain

import app.walkquest.data.boundaries.BoundaryRepository
import app.walkquest.data.fog.TestFog
import app.walkquest.data.packages.PackageDownloader
import app.walkquest.data.packages.RegionPackageRepository
import app.walkquest.domain.territory.DetectForeignRegion
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Предложение первого (домашнего) региона при запуске без установленных пакетов
 * (дефект D1 эмуляторного прогона 2026-10-05): локация известна → регион по bbox
 * манифеста (DetectForeignRegion); локации нет → первая запись манифеста; пакеты
 * уже установлены или манифест недоступен/пуст → предложения нет.
 */
class SuggestInitialRegionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val satelliteBbox = listOf(29.5, 59.3, 29.9, 59.6)

    private fun packagesRoot(): File = File(temp.root, "packages")

    /** Манифест с двумя записями: spb-city (без bbox) первая, satellite (с bbox) вторая. */
    private val manifestJson =
        """{"baseUrl":"https://example.test","packages":[""" +
            """{"regionId":"${TestFog.REGION_ID}","packageVersion":1,"formatVersion":1,""" +
            """"file":"a.zip","sizeBytes":1,"sha256":"x","name":"Санкт-Петербург"},""" +
            """{"regionId":"satellite","packageVersion":1,"formatVersion":1,""" +
            """"file":"b.zip","sizeBytes":1,"sha256":"y",""" +
            """"bbox":[${satelliteBbox.joinToString(",")}],"name":"Город-спутник"}]}"""

    private fun useCase(manifest: String?): SuggestInitialRegion {
        val downloader = object : PackageDownloader {
            override fun download(url: String, dest: File): Long {
                if (manifest == null || !url.endsWith("manifest.json")) throw IOException("нет сети")
                dest.writeText(manifest)
                return dest.length()
            }
        }
        val repository = RegionPackageRepository(packagesRoot(), downloader = downloader)
        return SuggestInitialRegion(repository, DetectForeignRegion(repository, BoundaryRepository()))
    }

    @Test
    fun `suggests first manifest package when location unknown`() = runBlocking {
        val suggestion = useCase(manifestJson).invoke(latitude = null, longitude = null)
        assertEquals(TestFog.REGION_ID, suggestion?.regionId)
        assertEquals("Санкт-Петербург", suggestion?.name)
    }

    @Test
    fun `suggests region by location bbox over manifest order`() = runBlocking {
        val suggestion = useCase(manifestJson).invoke(latitude = 59.45, longitude = 29.7)
        assertEquals("satellite", suggestion?.regionId)
        assertEquals("Город-спутник", suggestion?.name)
    }

    @Test
    fun `location outside all manifest bboxes falls back to first package`() = runBlocking {
        val suggestion = useCase(manifestJson).invoke(latitude = 0.0, longitude = 0.0)
        assertEquals(TestFog.REGION_ID, suggestion?.regionId)
    }

    @Test
    fun `no suggestion when packages already installed`() = runBlocking {
        TestFog.installPackage(packagesRoot())
        assertNull(useCase(manifestJson).invoke(latitude = null, longitude = null))
        assertNull(useCase(manifestJson).invoke(latitude = 59.45, longitude = 29.7))
    }

    @Test
    fun `no suggestion when manifest unavailable`() = runBlocking {
        assertNull(useCase(manifest = null).invoke(latitude = null, longitude = null))
    }

    @Test
    fun `no suggestion when manifest has no packages`() = runBlocking {
        val empty = """{"baseUrl":"https://example.test","packages":[]}"""
        // Пустой манифест — ошибка формата на уровне data → предложения нет.
        assertNull(useCase(empty).invoke(latitude = null, longitude = null))
    }
}
