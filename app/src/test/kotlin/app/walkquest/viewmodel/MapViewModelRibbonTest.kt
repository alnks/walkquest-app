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
import app.walkquest.tracking.JournalAppendResult
import app.walkquest.tracking.TrackHistory
import app.walkquest.tracking.TrackJournal
import app.walkquest.tracking.TrackPoint
import app.walkquest.tracking.TrackingStateBus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Лента раскрытия «свечи» в состоянии карты (полевой тест 4, 2026-10-06):
 * восстановление из журналов треков с replay фильтра и приписыванием региону
 * (только точки активного региона), инкремент по шине записи, дедупликация.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelRibbonTest {

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

    private fun viewModel(packagesRoot: File, bus: TrackingStateBus): MapViewModel {
        val packages = RegionPackageRepository(packagesRoot)
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

    private fun awaitTrue(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            assertTrue("условие не наступило за $timeoutMillis мс", System.currentTimeMillis() < deadline)
            Thread.sleep(20)
        }
    }

    /** Пешая цепочка внутри TestFog-региона: шаг ~1,4 м по широте, accuracy 10 м. */
    private fun walk(startLat: Double, startLon: Double, count: Int, startT: Long): List<TrackPoint> =
        (0 until count).map { i ->
            TrackPoint(startLat + i * (1.4 / 111_320.0), startLon, startT + i * 1_000L, 10f)
        }

    private fun writeJournal(name: String, points: List<TrackPoint>) {
        // Заголовок согласован с точками (replay отсекает фиксы старше старта записи).
        val journal = TrackJournal(File(temp.root, "tracks/$name"), startedAtEpochMillis = points.first().epochMillis)
        points.forEach { assertEquals(JournalAppendResult.Ok, journal.append(it)) }
    }

    @Test
    fun `ribbon restores accepted points of the active region from track journals`() {
        val packagesRoot = File(temp.root, "packages")
        TestFog.installPackage(packagesRoot)
        // Прогулка внутри региона (20 точек × 1,4 м) + точки далеко за его пределами.
        writeJournal("track-1000.track", walk(59.500, 30.300, 20, 1_000_000L))
        writeJournal("track-2000.track", walk(55.000, 30.300, 5, 2_000_000L))

        val vm = viewModel(packagesRoot, TrackingStateBus())
        awaitTrue { vm.uiState.value.regionInstalled }

        // Восстановлены только точки своего региона, дедуплицированные шагом 9 м:
        // 20 × 1,4 м = ~27 м цепочки → точки осевой на 0, 9,8 и 19,6 м.
        val chains = vm.uiState.value.ribbonChains
        assertEquals(1, chains.size) // чужой журнал своей цепочки не даёт
        val ribbon = chains.flatten()
        assertEquals(3, ribbon.size)
        assertTrue(ribbon.all { it.latitude in 59.49..59.51 })
    }

    @Test
    fun `each journal restores as a separate chain — no connecting line between sessions`() {
        // Полевой баг 2026-10-08: единая полилиния соединяла разрозненные записи
        // прямыми лентами через весь регион. Две прогулки в своём регионе —
        // две цепочки, соединения нет.
        val packagesRoot = File(temp.root, "packages")
        TestFog.installPackage(packagesRoot)
        writeJournal("track-1000.track", walk(59.500, 30.300, 20, 1_000_000L))
        writeJournal("track-2000.track", walk(59.501, 30.300, 20, 2_000_000L))

        val vm = viewModel(packagesRoot, TrackingStateBus())
        awaitTrue { vm.uiState.value.regionInstalled }

        val chains = vm.uiState.value.ribbonChains
        assertEquals(2, chains.size)
        assertTrue(chains[0].all { it.latitude in 59.4999..59.5005 })
        assertTrue(chains[1].all { it.latitude in 59.5009..59.5015 })
    }

    @Test
    fun `live accepted points extend the ribbon with dedup and region filter`() {
        val packagesRoot = File(temp.root, "packages")
        TestFog.installPackage(packagesRoot)
        val bus = TrackingStateBus()
        val vm = viewModel(packagesRoot, bus)
        awaitTrue { vm.uiState.value.regionInstalled }
        assertTrue(vm.uiState.value.ribbonChains.isEmpty())

        bus.recordingStarted()
        // Первая принятая точка — круг ленты.
        bus.pointAccepted(fogChanged = true, latitude = 59.500, longitude = 30.300)
        awaitTrue { vm.uiState.value.ribbonChains.flatten().size == 1 }
        // 5 м от неё — дедупликация, круг не добавляется.
        bus.pointAccepted(fogChanged = false, latitude = 59.500 + 5.0 / 111_320.0, longitude = 30.300)
        // 30 м — новый круг.
        bus.pointAccepted(fogChanged = false, latitude = 59.500 + 30.0 / 111_320.0, longitude = 30.300)
        awaitTrue { vm.uiState.value.ribbonChains.flatten().size == 2 }
        // Точка вне региона (за пределами границ пакета) в ленту не попадает.
        bus.pointAccepted(fogChanged = false, latitude = 55.000, longitude = 30.300)
        Thread.sleep(300) // даём возможной (неправильной) добавке проявиться
        assertEquals(2, vm.uiState.value.ribbonChains.flatten().size)
    }

    @Test
    fun `ribbon survives viewmodel recreation via journals (restart persistence)`() {
        val packagesRoot = File(temp.root, "packages")
        TestFog.installPackage(packagesRoot)
        val bus = TrackingStateBus()
        val first = viewModel(packagesRoot, bus)
        awaitTrue { first.uiState.value.regionInstalled }

        // Запись: две точки 30 м — две точки осевой; журнал на диске — как у сервиса (1.7).
        bus.recordingStarted()
        bus.pointAccepted(fogChanged = true, latitude = 59.500, longitude = 30.300)
        bus.pointAccepted(fogChanged = false, latitude = 59.500 + 30.0 / 111_320.0, longitude = 30.300)
        awaitTrue { first.uiState.value.ribbonChains.flatten().size == 2 }
        writeJournal("track-1000.track", walk(59.500, 30.300, 25, 1_000_000L))
        bus.recordingStopped()

        // «Перезапуск»: новая viewmodel читает ленту из журнала, а не из памяти.
        val second = viewModel(packagesRoot, TrackingStateBus())
        awaitTrue { second.uiState.value.regionInstalled }
        val restored = second.uiState.value.ribbonChains.flatten()
        // 25 × 1,4 м = ~34 м → точки осевой на 0, 9,8, 19,6, 29,4 м (дедуп шагом 9 м).
        assertEquals(4, restored.size)
        assertEquals(59.500, restored.first().latitude, 1e-9)
    }
}
