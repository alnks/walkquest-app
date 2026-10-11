package app.walkquest.viewmodel

import app.walkquest.data.boundaries.BoundaryRepository
import app.walkquest.data.fog.FogRepository
import app.walkquest.data.packages.PackageSource
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Данные debug-оверлея карты (история 1.14): провайдер маркерного фикса и
 * вердикт отбраковки доносятся шиной до состояния экрана; источник пакетов
 * виден в состоянии с самого старта. Сам оверлей — UI (рендерится только
 * в debuggable-сборке), тестируется перенос данных, не пиксели.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelDebugOverlayTest {

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

    private fun viewModel(bus: TrackingStateBus): MapViewModel {
        val packages = RegionPackageRepository(File(temp.root, "packages"))
        val fog = FogRepository(File(temp.root, "fog"))
        val boundaries = BoundaryRepository()
        return MapViewModel(
            openTileAtPoint = OpenTileAtPoint(fog, packages, boundaries),
            regionPackageRepository = packages,
            fogRepository = fog,
            trackingStateBus = bus,
            computeRegionProgress = ComputeRegionProgress(fog, packages),
            poiRepository = PoiRepository(),
            settingsRepository = SettingsRepository(File(temp.root, "settings")),
            installRegionPackage = InstallRegionPackage(packages),
            suggestInitialRegion = SuggestInitialRegion(packages, DetectForeignRegion(packages, boundaries)),
            trackHistory = TrackHistory(File(temp.root, "tracks")),
            boundaryRepository = boundaries,
        )
    }

    @Test
    fun `packages base url visible in state from start`() {
        val vm = viewModel(TrackingStateBus())
        assertEquals(PackageSource.DEFAULT_BASE_URL, vm.uiState.value.packagesBaseUrl)
        // Без установленного пакета версии и провайдера нет — оверлей покажет прочерки.
        assertNull(vm.uiState.value.packageVersion)
        assertNull(vm.uiState.value.userFixProvider)
    }

    @Test
    fun `fix provider travels from bus to state`() {
        val bus = TrackingStateBus()
        val vm = viewModel(bus)
        bus.recordingStarted()
        bus.locationUpdated(59.93, 30.33, 12f, 1_000L, provider = "gps")
        assertEquals("gps", vm.uiState.value.userFixProvider)
        // Фикс без провайдера не затирает прежнего (совместимость старых вызовов).
        bus.locationUpdated(59.94, 30.34, null, 2_000L)
        assertEquals("gps", vm.uiState.value.userFixProvider)
        bus.locationUpdated(59.95, 30.35, 800f, 3_000L, provider = "network")
        assertEquals("network", vm.uiState.value.userFixProvider)
    }

    @Test
    fun `last rejected verdict travels from bus to state`() {
        val bus = TrackingStateBus()
        val vm = viewModel(bus)
        bus.recordingStarted()
        bus.pointRejected("TooFast")
        assertEquals("TooFast", vm.uiState.value.lastRejectedVerdict)
        assertEquals(1, vm.uiState.value.rejectedPoints)
        bus.pointRejected() // без имени — прежний вердикт сохраняется
        assertEquals("TooFast", vm.uiState.value.lastRejectedVerdict)
        assertEquals(2, vm.uiState.value.rejectedPoints)
    }
}
