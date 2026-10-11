package app.walkquest.domain.achievements

import app.walkquest.domain.DistrictProgress
import app.walkquest.tracking.WalkRecord
import java.io.File
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM-тесты движка достижений (история 4.2, FR-15): граничные случаи условий,
 * идемпотентность EarnedStore, выдача задним числом по истории.
 */
class AchievementEngineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(): EarnedStore = EarnedStore(File(temp.root, "achievements/earned.json"))

    private fun record(
        distance: Double = 3_000.0,
        activeMillis: Long = 1_800_000L,
        regionIds: List<String> = listOf("spb-city"),
        modes: List<String> = emptyList(),
        newTiles: Int = 5,
        startMillis: Long = 0L,
        id: Long = 1L,
    ) = WalkRecord(
        sessionId = id,
        startedAtEpochMillis = startMillis,
        finishedAtEpochMillis = startMillis + activeMillis,
        distanceMeters = distance,
        activeMillis = activeMillis,
        acceptedCount = 20,
        regionIds = regionIds,
        modes = modes,
        newTilesOpened = newTiles,
    )

    private fun state(
        records: List<WalkRecord> = emptyList(),
        homeFraction: Double? = null,
        districts: List<DistrictProgress> = emptyList(),
        otherFractions: Map<String, Double> = emptyMap(),
        poiRead: Int = 0,
        totalPoi: Int = 0,
        parks: Int = 0,
        streak: Int = 0,
        questsAccepted: Int = 0,
        questsCompleted: Int = 0,
    ) = AchievementState(
        records = records,
        homeRegionId = "spb-city",
        homeCityFraction = homeFraction,
        homeDistricts = districts,
        otherRegionFractions = otherFractions,
        poiReadCount = poiRead,
        totalPoiCount = totalPoi,
        parksReadCount = parks,
        streakDays = streak,
        questsAcceptedCount = questsAccepted,
        questsCompletedCount = questsCompleted,
        zone = ZoneOffset.UTC,
    )

    private fun district(fraction: Double, fully: Boolean) = DistrictProgress(
        name = "р-н", openedWalkable = 0, walkableTotal = 0, fraction = fraction,
    ).let { if (fully) DistrictProgress("р-н", 1, 1, 1.0) else it }

    @Test
    fun `catalog has 40 achievements in five categories`() {
        assertEquals(40, AchievementCatalog.ALL.size)
        assertEquals(
            mapOf(
                "EXPLORATION" to 16, "REGULARITY" to 7, "DISTANCE" to 8,
                "QUESTS" to 5, "TRAVEL" to 4,
            ),
            AchievementCatalog.ALL.groupingBy { it.category.name }.eachCount(),
        )
        assertEquals(setOf("A6", "A10", "A14", "B4", "C3", "E3"),
            AchievementCatalog.ALL.filter { it.rare }.map { it.id }.toSet())
    }

    @Test
    fun `first walk earns A1 and percent thresholds earn A2 to A6`() {
        val engine = AchievementEngine(earnedStore = store())
        val first = engine.checkAndRecord(state(records = listOf(record()), homeFraction = 0.05))
        assertTrue(first.any { it.id == "A1" })
        assertFalse(first.any { it.id == "A2" })
        // Город дошёл до 90%: всё выдано одной перепроверкой (задним числом).
        val all = engine.checkAndRecord(state(records = listOf(record()), homeFraction = 0.9))
        assertEquals(setOf("A2", "A3", "A4", "A5", "A6"), all.map { it.id }.toSet())
    }

    @Test
    fun `streak distance time and quest achievements fire on boundaries`() {
        val engine = AchievementEngine(earnedStore = store())
        val earned = engine.checkAndRecord(
            state(
                records = listOf(
                    record(distance = 100_000.0, activeMillis = 91L * 60_000L, modes = listOf("CYCLING")),
                ),
                streak = 30, questsAccepted = 1, questsCompleted = 15,
            ),
        ).map { it.id }.toSet()
        assertTrue("B4 rare серия" in earned || "B4" in earned)
        assertTrue("C3" in earned && "C1" in earned && "C2" in earned)
        assertTrue("C5" in earned && "C6" in earned)
        assertTrue("D1" in earned && "D2" in earned && "D3" in earned)
    }

    @Test
    fun `repeat check is idempotent`() {
        val engine = AchievementEngine(earnedStore = store())
        val s = state(records = listOf(record()), homeFraction = 0.9, streak = 30)
        val first = engine.checkAndRecord(s)
        assertTrue(first.isNotEmpty())
        val second = engine.checkAndRecord(s)
        assertTrue(second.isEmpty())
        assertEquals(first.size, store().earnedIds().size)
    }

    @Test
    fun `new defs are checked against full history retroactively`() {
        // Достижение «из обновления»: каталог проверяется по состоянию всей истории.
        val engine = AchievementEngine(earnedStore = store())
        val legacy = record(distance = 120_000.0, id = 1)
        val earned = engine.checkAndRecord(state(records = listOf(legacy)))
        assertTrue(earned.any { it.id == "C3" })
    }

    @Test
    fun `early bird night owl lunch and twice a day use start local time`() {
        val engine = AchievementEngine(earnedStore = store())
        // 2026-10-07 = среда (будний). 06:00 UTC старт.
        val wednesday6am = 1_768_3_00_00_00L // placeholder, вычисляем ниже явно
        val base = java.time.LocalDate.of(2026, 10, 7).atTime(6, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val records = listOf(
            record(startMillis = base, id = 1), // 06:00 до 8:00, будний
            record(startMillis = base + 17L * 3_600_000L, id = 2), // 23:00 — после 22:00
            record(startMillis = base + 6L * 3_600_000L, id = 3), // 12:00 обед
            record(startMillis = base + 7L * 3_600_000L, id = 4), // тот же день 13:00 — вторая прогулка
        )
        val earned = engine.checkAndRecord(state(records = records)).map { it.id }.toSet()
        assertTrue(earned.containsAll(listOf("B5", "B6", "B7", "C7")))
    }

    @Test
    fun `loved trail needs exactly zero new tiles and at least 1 km`() {
        val engine = AchievementEngine(earnedStore = store())
        assertFalse(engine.newlyEarned(state(records = listOf(record(distance = 1_500.0, newTiles = 0)))).isEmpty())
        // 0,5 км — мало.
        assertFalse("C8" in engine.newlyEarned(
            state(records = listOf(record(distance = 500.0, newTiles = 0)))).map { it.id })
        // Неизвестно (запись до 4.1): C8 не выдаётся задним числом.
        assertFalse("C8" in engine.newlyEarned(
            state(records = listOf(record(distance = 5_000.0, newTiles = -1)))).map { it.id })
    }

    @Test
    fun `districts and travel achievements`() {
        val engine = AchievementEngine(earnedStore = store())
        val earned = engine.checkAndRecord(
            state(
                records = listOf(record(regionIds = listOf("spb-city", "gatchina", "kolpino"))),
                districts = listOf(district(1.0, true), district(1.0, true), district(1.0, true)),
                otherFractions = mapOf("gatchina" to 0.15),
            ),
        ).map { it.id }.toSet()
        assertTrue(earned.containsAll(listOf("A10", "E1", "E2", "E3")))
    }

    @Test
    fun `poi and park achievements`() {
        val engine = AchievementEngine(earnedStore = store())
        val earned = engine.checkAndRecord(
            state(poiRead = 4583, totalPoi = 4583, parks = 3),
        ).map { it.id }.toSet()
        assertTrue(earned.containsAll(listOf("A11", "A12", "A13", "A14", "A15", "A16")))
    }

    @Test
    fun `earned store roundtrip and byId`() {
        val s = store()
        assertTrue(s.record("A1", 100L))
        assertFalse(s.record("A1", 200L)) // идемпотентно
        assertTrue(s.contains("A1"))
        assertEquals(listOf("A1"), s.earned().map { it.id })
        assertTrue(AchievementCatalog.byId("D4") != null && AchievementCatalog.byId("D5") != null)
    }
}
