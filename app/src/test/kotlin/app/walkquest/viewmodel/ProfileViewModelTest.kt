package app.walkquest.viewmodel

import app.walkquest.data.packages.RegionPackageRepository
import app.walkquest.data.settings.SettingsRepository
import app.walkquest.data.transfer.ExportReadError
import app.walkquest.data.transfer.ImportReport
import app.walkquest.data.transfer.SectionStatus
import app.walkquest.domain.InstallRegionPackage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Вкладка «Профиль» (история 6.3): round-trip переноса через потоки viewmodel,
 * честный отчёт импорта, подсказка о перезапуске.
 */
class ProfileViewModelTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    @Before
    fun setUpMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMain() {
        Dispatchers.resetMain()
    }

    private class Env(root: File, withData: Boolean) {
        val settingsRoot = File(root, "settings").apply { mkdirs() }
        val fogRoot = File(root, "fog").apply { mkdirs() }
        val tracksRoot = File(root, "tracks").apply { mkdirs() }
        val packagesRoot = File(root, "packages").apply { mkdirs() }
        val walksFile = File(root, "walks/walks.jsonl").apply { parentFile.mkdirs() }
        val earnedFile = File(root, "achievements/earned.json").apply { parentFile.mkdirs() }
        val questsFile = File(root, "quests/quests.json").apply { parentFile.mkdirs() }
        val plansFile = File(root, "plans/plans.json").apply { parentFile.mkdirs() }
        val poiCardsFile = File(root, "poicards.json")
        val sessionFile = File(root, "session/session.json").apply { parentFile.mkdirs() }

        init {
            File(settingsRoot, "settings.json").writeText("""{"homeRegionId":"spb"}""")
            if (withData) {
                walksFile.writeText(
                    "{\"sessionId\":1,\"startedAtEpochMillis\":10,\"finishedAtEpochMillis\":20," +
                        "\"distanceMeters\":100.0,\"activeMillis\":9000,\"acceptedCount\":5}\n"
                )
                earnedFile.writeText("""{"earned":[{"id":"A1","atEpochMillis":5}]}""")
                File(fogRoot, "spb.fog").writeText("{\"formatVersion\":1}\n", Charsets.ISO_8859_1)
            }
        }

        fun sources() = app.walkquest.data.transfer.ExportSources(
            settingsRoot = settingsRoot, fogRoot = fogRoot, walksFile = walksFile,
            earnedFile = earnedFile, questsFile = questsFile, plansFile = plansFile,
            poiCardsFile = poiCardsFile, tracksRoot = tracksRoot,
            appVersion = "1.0-test", installedRegionIds = listOf("spb"),
        )

        fun targets() = app.walkquest.data.transfer.ImportTargets(
            settingsRoot = settingsRoot, fogRoot = fogRoot, walksFile = walksFile,
            earnedFile = earnedFile, questsFile = questsFile, plansFile = plansFile,
            poiCardsFile = poiCardsFile, tracksRoot = tracksRoot,
            sessionFile = sessionFile,
        )
    }

    private fun viewModel(
        env: Env,
        importAppliedCalls: IntArray,
    ): ProfileViewModel {
        val repository = RegionPackageRepository(
            packagesRoot = env.packagesRoot,
            baseUrl = "https://packages.invalid",
        )
        return ProfileViewModel(
            settingsRepository = SettingsRepository(env.settingsRoot),
            regionPackageRepository = repository,
            installRegionPackage = InstallRegionPackage(repository),
            exportSources = { env.sources() },
            importTargets = { env.targets() },
            appVersion = "1.0-test",
            privacyUrl = "https://walkquest.app/privacy",
            onImportApplied = { importAppliedCalls[0]++ },
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @Test
    fun `export preview confirm restores data and reports missing regions`() = runBlocking {
        val src = Env(tmp.newFolder("src"), withData = true)
        val dst = Env(tmp.newFolder("dst"), withData = false)
        dst.sessionFile.writeText("{\"active\":true}")
        val appliedCalls = intArrayOf(0)
        val srcViewModel = viewModel(src, intArrayOf(0))
        val dstViewModel = viewModel(dst, appliedCalls)

        val out = ByteArrayOutputStream()
        srcViewModel.export(out)
        assertTrue(srcViewModel.uiState.value.message!!.contains("Копия сохранена"))

        dstViewModel.readImportPreview(ByteArrayInputStream(out.toByteArray()))
        val preview = dstViewModel.uiState.value.importState as ImportState.Preview
        assertTrue(preview.exportedAtEpochMillis > 0)

        dstViewModel.confirmImport()

        assertEquals(1, appliedCalls[0])
        assertEquals(ImportState.Idle, dstViewModel.uiState.value.importState)
        // Файлы восстановлены побайтово.
        assertEquals(src.walksFile.readText(), dst.walksFile.readText())
        assertEquals(src.earnedFile.readText(), dst.earnedFile.readText())
        // Сессия удалена, регион копии без пакета — в недостающих.
        assertTrue(!dst.sessionFile.exists())
        assertEquals(listOf("spb"), dstViewModel.uiState.value.missingRegions)
        assertNotNull(dstViewModel.uiState.value.message)
        assertTrue(dstViewModel.uiState.value.message!!.contains("Перезапустите"))
    }

    @Test
    fun `corrupt archive shows error and no dialog`() = runBlocking {
        val dst = Env(tmp.newFolder("dst"), withData = false)
        val appliedCalls = intArrayOf(0)
        val viewModel = viewModel(dst, appliedCalls)

        viewModel.readImportPreview(ByteArrayInputStream("plain garbage".toByteArray()))

        assertEquals(ImportState.Idle, viewModel.uiState.value.importState)
        assertNotNull(viewModel.uiState.value.lastError)
        assertEquals(0, appliedCalls[0])
    }

    @Test
    fun `formatImportReport is honest about skipped sections`() {
        val report = ImportReport(
            sections = mapOf(
                "walks/walks.jsonl" to SectionStatus.Ok,
                "fog/spb.fog" to SectionStatus.Skipped("fog header is not a JSON object"),
            ),
            sessionDeleted = true,
            warnings = emptyList(),
        )
        val text = formatImportReport(report)
        assertTrue(text.contains("частично"))
        assertTrue(text.contains("walks/walks.jsonl — ✓"))
        assertTrue(text.contains("fog/spb.fog — пропущена"))
    }

    @Test
    fun `import error text distinguishes corrupt and future formats`() {
        assertTrue(
            importErrorText(ExportReadError.CorruptArchive("x")).contains("похоже на копию")
        )
        assertTrue(
            importErrorText(ExportReadError.UnsupportedFormat(999)).contains("новой версией")
        )
    }

    @Test
    fun `reload exposes nickname visibility and goal`() {
        val dst = Env(tmp.newFolder("dst"), withData = false)
        val settings = SettingsRepository(dst.settingsRoot)
        settings.setWalkGoal(app.walkquest.domain.WalkGoal(app.walkquest.domain.GoalType.WALKS_PER_WEEK, 4))
        settings.setLeaderboardVisible(false)
        val viewModel = viewModel(dst, intArrayOf(0))

        viewModel.reload()

        // Текст цели — готовая строка из domain (AD-4), не форматируем в UI.
        assertEquals("4 прогулки в неделю", viewModel.uiState.value.goalText)
        assertEquals(false, viewModel.uiState.value.leaderboardVisible)
        assertNull(viewModel.uiState.value.nickname)
    }
}
