package app.walkquest.viewmodel

import app.walkquest.data.boundaries.BoundaryRepository
import app.walkquest.data.fog.FogRepository
import app.walkquest.data.fog.TestFog
import app.walkquest.data.packages.RegionPackageRepository
import app.walkquest.data.poi.PoiRepository
import app.walkquest.data.settings.SettingsRepository
import app.walkquest.domain.ComputeRegionProgress
import app.walkquest.domain.InstallRegionPackage
import app.walkquest.domain.OpenTileAtPoint
import app.walkquest.domain.SuggestInitialRegion
import app.walkquest.domain.territory.DetectForeignRegion
import app.walkquest.tracking.TrackHistory
import app.walkquest.tracking.TrackingStateBus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Регрессия дефекта разбора 2026-10-05 «после „Обновить" (v1→v2) карта показывает
 * старые POI / нет фото»: обновление пакета ставилось во вкладке «Регионы», но
 * MapViewModel держал снимок пакета в памяти (стиль, сетка, POI) и не перечитывал
 * его при возврате на карту. Теперь [MapViewModel.onMapShown] (ON_RESUME) сверяет
 * версию на диске с загруженной и перечитывает регион при расхождении.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelPackageUpdateTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Before
    fun setUpMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMain() {
        Dispatchers.resetMain()
    }

    /** poi.json с одним маяком тира 1 (есть wikidata → виден на обзорном зуме; координаты внутри TestFog-сетки). */
    private fun poiJson(osmId: String, name: String): String =
        """{"pois":[{"osm_id":"$osmId","name":"$name","category":"tourism=museum",""" +
            """"lon":30.2,"lat":59.1,"wikidata":"Q1","description":null}]}"""

    private fun viewModel(packagesRoot: File): MapViewModel {
        val packages = RegionPackageRepository(packagesRoot)
        val fog = FogRepository(File(temp.root, "fog"))
        val boundaries = BoundaryRepository()
        return MapViewModel(
            openTileAtPoint = OpenTileAtPoint(fog, packages, boundaries),
            regionPackageRepository = packages,
            fogRepository = fog,
            trackingStateBus = TrackingStateBus(),
            computeRegionProgress = ComputeRegionProgress(fog, packages),
            poiRepository = PoiRepository(),
            settingsRepository = SettingsRepository(File(temp.root, "settings")),
            installRegionPackage = InstallRegionPackage(packages),
            suggestInitialRegion = SuggestInitialRegion(packages, DetectForeignRegion(packages, boundaries)),
            trackHistory = TrackHistory(File(temp.root, "tracks")),
            boundaryRepository = boundaries,
        )
    }

    /** Сегмент пути версии пакета с системным разделителем (Windows — обратный слэш). */
    private fun versionDir(version: String): String =
        "${File.separator}$version${File.separator}"

    /** Ждём условие: viewmodel читает диск через Dispatchers.IO (реальный пул). */
    private fun awaitTrue(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            assertTrue("условие не наступило за $timeoutMillis мс", System.currentTimeMillis() < deadline)
            Thread.sleep(20)
        }
    }

    @Test
    fun `map reloads region after package update v1 to v2`() {
        val packagesRoot = File(temp.root, "packages")
        // Пакет v1 на диске: POI «Старый музей», без фото.
        val v1 = TestFog.installPackage(packagesRoot, packageVersion = 1)
        File(v1, "poi.json").writeText(poiJson("100", "Старый музей"))
        val vm = viewModel(packagesRoot)
        awaitTrue { vm.uiState.value.regionInstalled }
        assertTrue(vm.uiState.value.styleUri.contains(versionDir("v1")))
        assertTrue(vm.uiState.value.visiblePois.any { it.osmId == "100" })

        // «Обновить» из «Регионов»: на диске появился v2 (POI другой, с полем photo).
        val v2 = TestFog.installPackage(packagesRoot, packageVersion = 2)
        File(v2, "poi.json").writeText(
            poiJson("200", "Новый музей").replace(
                """"description":null}""",
                """"description":null,"photo":"photos/poi-200.jpg"}""",
            ),
        )

        // Возврат на карту: ON_RESUME → onMapShown → регион перечитан с диска.
        vm.onMapShown()
        awaitTrue { vm.uiState.value.styleUri.contains(versionDir("v2")) }
        awaitTrue { vm.uiState.value.visiblePois.any { it.osmId == "200" } }
        val reloaded = vm.uiState.value
        assertFalse(reloaded.visiblePois.any { it.osmId == "100" })
        // Фото из пакета v2 резолвится в файл на диске (поле photo прочиталось).
        val poi = reloaded.visiblePois.first { it.osmId == "200" }
        assertTrue(poi.photo == "photos/poi-200.jpg")
    }

    @Test
    fun `map shown without package update keeps state and does not reload`() {
        val packagesRoot = File(temp.root, "packages")
        val v1 = TestFog.installPackage(packagesRoot, packageVersion = 1)
        File(v1, "poi.json").writeText(poiJson("100", "Старый музей"))
        val vm = viewModel(packagesRoot)
        awaitTrue { vm.uiState.value.regionInstalled }
        val epochAfterLoad = vm.uiState.value.fogEpoch

        vm.onMapShown() // та же версия — ничего не должно измениться
        Thread.sleep(300) // даём возможной (неправильной) перезагрузке проявиться
        assertTrue(vm.uiState.value.styleUri.contains(versionDir("v1")))
        assertTrue(vm.uiState.value.fogEpoch == epochAfterLoad)
    }
}
