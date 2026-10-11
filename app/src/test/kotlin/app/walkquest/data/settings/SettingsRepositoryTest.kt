package app.walkquest.data.settings

import app.walkquest.domain.GoalType
import app.walkquest.domain.WalkGoal
import app.walkquest.tracking.WalkMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Персистентность домашнего региона (1.10, AD-9): выбор сохраняется в settings.json,
 * переживает «перезапуск» (новый репозиторий на тех же файлах), смена перезаписывает;
 * битый/несовместимый файл → дефолт (не выбран), без краша.
 */
class SettingsRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun repo(): SettingsRepository = SettingsRepository(File(temp.root, "settings"))

    @Test
    fun `home region is not set by default`() {
        assertNull(repo().homeRegionId())
    }

    @Test
    fun `set home region persists across repository recreation`() {
        assertNull(repo().setHomeRegion("spb-city"))

        // «Перезапуск приложения»: новый репозиторий на том же хранилище.
        assertEquals("spb-city", repo().homeRegionId())
    }

    @Test
    fun `changing home region overwrites the stored value`() {
        repo().setHomeRegion("spb-city")
        assertNull(repo().setHomeRegion("gatchina"))
        assertEquals("gatchina", repo().homeRegionId())
    }

    @Test
    fun `corrupt settings file falls back to unset`() {
        val root = File(temp.root, "settings")
        root.mkdirs()
        File(root, "settings.json").writeText("мусор, не json")
        assertNull(repo().homeRegionId())

        // Хранилище живо после битого файла: запись снова работает.
        assertNull(repo().setHomeRegion("spb-city"))
        assertEquals("spb-city", repo().homeRegionId())
    }

    @Test
    fun `incompatible formatVersion falls back to unset`() {
        val root = File(temp.root, "settings")
        root.mkdirs()
        File(root, "settings.json").writeText("""{"formatVersion":99,"homeRegionId":"spb-city"}""")
        assertNull(repo().homeRegionId())
    }
    // --- Режим прогулки и лимиты скорости (история 2.2, FR-6) ---

    @Test
    fun `walk mode defaults to walking and roundtrips`() {
        assertEquals(WalkMode.WALKING, repo().walkMode())
        assertNull(repo().setWalkMode(WalkMode.CYCLING))
        assertEquals(WalkMode.CYCLING, repo().walkMode())
        // Персистится через пересоздание репозитория (тот же диск).
        assertEquals(WalkMode.CYCLING, repo().walkMode())
    }

    @Test
    fun `walk speed limit override roundtrips and resets`() {
        assertNull(repo().walkSpeedLimitKmh(WalkMode.WALKING))
        assertNull(repo().walkSpeedLimitKmh(WalkMode.CYCLING))
        assertNull(repo().setWalkSpeedLimitKmh(WalkMode.WALKING, 10.0))
        assertEquals(10.0, repo().walkSpeedLimitKmh(WalkMode.WALKING) ?: 0.0, 0.0001)
        // Режимы независимы.
        assertNull(repo().walkSpeedLimitKmh(WalkMode.CYCLING))
        assertNull(repo().setWalkSpeedLimitKmh(WalkMode.WALKING, null))
        assertNull(repo().walkSpeedLimitKmh(WalkMode.WALKING))
    }

    @Test
    fun `non positive walk speed limit rejected`() {
        assertTrue(repo().setWalkSpeedLimitKmh(WalkMode.WALKING, 0.0) != null)
        assertTrue(repo().setWalkSpeedLimitKmh(WalkMode.CYCLING, -1.0) != null)
        assertNull(repo().walkSpeedLimitKmh(WalkMode.WALKING))
    }

    @Test
    fun `legacy settings file without mode fields reads with defaults`() {
        // Файл эпохи 1.10 (только homeRegionId): новые поля — дефолты, не битый файл.
        val root = File(temp.root, "settings")
        root.mkdirs()
        File(root, "settings.json").writeText("""{"formatVersion":1,"homeRegionId":"spb-city"}""")
        assertEquals("spb-city", repo().homeRegionId())
        assertEquals(WalkMode.WALKING, repo().walkMode())
        assertNull(repo().walkSpeedLimitKmh(WalkMode.WALKING))
    }
    // --- Автопауза (история 2.4, FR-7) ---

    @Test
    fun `auto pause defaults to enabled and roundtrips`() {
        assertTrue(repo().autoPauseEnabled())
        assertNull(repo().setAutoPauseEnabled(false))
        assertTrue(!repo().autoPauseEnabled())
        assertNull(repo().setAutoPauseEnabled(true))
        assertTrue(repo().autoPauseEnabled())
    }

    @Test
    fun `legacy settings file without auto pause reads as enabled`() {
        val root = File(temp.root, "settings")
        root.mkdirs()
        File(root, "settings.json").writeText("""{"formatVersion":1,"walkMode":"CYCLING"}""")
        assertTrue(repo().autoPauseEnabled())
        assertEquals(WalkMode.CYCLING, repo().walkMode())
    }
    // --- Напоминание о забытой прогулке (история 2.5, FR-7) ---

    @Test
    fun `forgotten walk reminder defaults to enabled and roundtrips`() {
        assertTrue(repo().forgottenWalkReminderEnabled())
        assertNull(repo().setForgottenWalkReminderEnabled(false))
        assertTrue(!repo().forgottenWalkReminderEnabled())
        assertNull(repo().setForgottenWalkReminderEnabled(true))
        assertTrue(repo().forgottenWalkReminderEnabled())
    }

    @Test
    fun `legacy settings file without reminder field reads as enabled`() {
        val root = File(temp.root, "settings")
        root.mkdirs()
        File(root, "settings.json").writeText("""{"formatVersion":1,"autoPauseEnabled":false}""")
        assertTrue(repo().forgottenWalkReminderEnabled())
        assertTrue(!repo().autoPauseEnabled())
    }
    // --- Батарейный страж (история 2.6, FR-10) ---

    @Test
    fun `battery thresholds default to 15 and 10 and roundtrip`() {
        assertEquals(15, repo().batteryWarningPercent())
        assertEquals(10, repo().batteryFinishPercent())
        assertNull(repo().setBatteryThresholds(20, 15))
        assertEquals(20, repo().batteryWarningPercent())
        assertEquals(15, repo().batteryFinishPercent())
    }

    @Test
    fun `battery thresholds validation rejects bad pairs`() {
        // Предупреждение ниже автозавершения — отказ, состояние не меняется.
        assertTrue(repo().setBatteryThresholds(5, 10) != null)
        assertEquals(15, repo().batteryWarningPercent())
        // Вне диапазона 1..100 — отказ.
        assertTrue(repo().setBatteryThresholds(0, 0) != null)
        assertTrue(repo().setBatteryThresholds(101, 50) != null)
        assertEquals(15, repo().batteryWarningPercent())
        assertEquals(10, repo().batteryFinishPercent())
    }

    @Test
    fun `legacy settings file without battery fields reads defaults`() {
        val root = File(temp.root, "settings")
        root.mkdirs()
        File(root, "settings.json").writeText("""{"formatVersion":1,"walkMode":"WALKING"}""")
        assertEquals(15, repo().batteryWarningPercent())
        assertEquals(10, repo().batteryFinishPercent())
    }
    // --- Частота прогресс-уведомлений (история 2.7, FR-9) ---

    @Test
    fun `progress notification interval defaults to 5 and roundtrips`() {
        assertEquals(5, repo().progressNotificationIntervalMinutes())
        assertNull(repo().setProgressNotificationIntervalMinutes(10))
        assertEquals(10, repo().progressNotificationIntervalMinutes())
    }

    @Test
    fun `progress notification interval validation`() {
        assertTrue(repo().setProgressNotificationIntervalMinutes(0) != null)
        assertTrue(repo().setProgressNotificationIntervalMinutes(121) != null)
        assertEquals(5, repo().progressNotificationIntervalMinutes())
    }

    @Test
    fun `legacy settings file without interval field reads default`() {
        val root = File(temp.root, "settings")
        root.mkdirs()
        File(root, "settings.json").writeText("""{"formatVersion":1,"batteryWarningPercent":20}""")
        assertEquals(5, repo().progressNotificationIntervalMinutes())
        assertEquals(20, repo().batteryWarningPercent())
    }
    // --- Цель и онбординг (истории 3.2/3.3, FR-11/FR-12) ---

    @Test
    fun `walk goal unset by default and roundtrips`() {
        assertNull(repo().walkGoal())
        assertTrue(!repo().onboardingDone())
        assertNull(repo().setWalkGoal(WalkGoal(GoalType.WALKS_PER_WEEK, 3)))
        assertEquals(WalkGoal(GoalType.WALKS_PER_WEEK, 3), repo().walkGoal())
        // Изменение в любой момент (FR-11).
        assertNull(repo().setWalkGoal(WalkGoal(GoalType.MINUTES_PER_DAY, 45)))
        assertEquals(WalkGoal(GoalType.MINUTES_PER_DAY, 45), repo().walkGoal())
    }

    @Test
    fun `goal reset to null roundtrips`() {
        assertNull(repo().setWalkGoal(WalkGoal(GoalType.WALKS_PER_WEEK, 3)))
        assertEquals(WalkGoal(GoalType.WALKS_PER_WEEK, 3), repo().walkGoal())
        // Сброс (сценарий «до онбординга»): пара очищается.
        assertNull(repo().setWalkGoal(null))
        assertNull(repo().walkGoal())
    }

    @Test
    fun `onboarding flag roundtrips independently of goal`() {
        assertNull(repo().setWalkGoal(WalkGoal(GoalType.WALKS_PER_WEEK, 2)))
        assertNull(repo().setOnboardingDone(true))
        assertTrue(repo().onboardingDone())
        assertEquals(WalkGoal(GoalType.WALKS_PER_WEEK, 2), repo().walkGoal())
    }

    @Test
    fun `corrupt goal pair reads as unset not crash`() {
        // Битая пара (тип известен, значение вне диапазона): walkGoal = null,
        // приложение не падает (файл правим руками — защита от мусора).
        val root = File(temp.root, "settings")
        root.mkdirs()
        File(root, "settings.json").writeText(
            """{"formatVersion":1,"goalType":"WALKS_PER_WEEK","goalValue":99}""",
        )
        // 99 вне 1..7: init WalkGoal бросает — репозиторий должен отдать null.
        try {
            assertNull(repo().walkGoal())
        } catch (e: IllegalArgumentException) {
            // Пока walkGoal() не оборачивает init в runCatching — фиксируем пробел
            // (sweep 3.7): тест падает, чтобы правка не потерялась.
            throw AssertionError("walkGoal() must not throw on corrupt pair", e)
        }
    }

    @Test
    fun `legacy settings file without goal fields reads unset`() {
        val root = File(temp.root, "settings")
        root.mkdirs()
        File(root, "settings.json").writeText("""{"formatVersion":1,"walkMode":"WALKING"}""")
        assertNull(repo().walkGoal())
        assertTrue(!repo().onboardingDone())
    }
    // --- Умные напоминания по цели (история 3.5, FR-14) ---

    @Test
    fun `goal reminders default enabled with hour 19 and roundtrip`() {
        assertTrue(repo().goalRemindersEnabled())
        assertEquals(19, repo().goalReminderHour())
        assertNull(repo().setGoalRemindersEnabled(false))
        assertTrue(!repo().goalRemindersEnabled())
        assertNull(repo().setGoalReminderHour(21))
        assertEquals(21, repo().goalReminderHour())
    }

    @Test
    fun `goal reminder hour validation`() {
        assertTrue(repo().setGoalReminderHour(-1) != null)
        assertTrue(repo().setGoalReminderHour(24) != null)
        assertEquals(19, repo().goalReminderHour())
    }

    @Test
    fun `legacy settings file without reminder fields reads defaults`() {
        val root = File(temp.root, "settings")
        root.mkdirs()
        File(root, "settings.json").writeText("""{"formatVersion":1,"onboardingDone":true}""")
        assertTrue(repo().goalRemindersEnabled())
        assertEquals(19, repo().goalReminderHour())
        assertTrue(repo().onboardingDone())
    }
    // --- Планировщик прогулок (история 3.6, FR-17) ---

    @Test
    fun `plan reminder lead defaults to 15 and roundtrips`() {
        assertEquals(15, repo().planReminderLeadMinutes())
        assertNull(repo().setPlanReminderLeadMinutes(30))
        assertEquals(30, repo().planReminderLeadMinutes())
    }

    @Test
    fun `plan reminder lead validation`() {
        assertTrue(repo().setPlanReminderLeadMinutes(4) != null)
        assertTrue(repo().setPlanReminderLeadMinutes(121) != null)
        assertEquals(15, repo().planReminderLeadMinutes())
    }

    @Test
    fun `legacy settings file without plan lead reads default`() {
        val root = File(temp.root, "settings")
        root.mkdirs()
        File(root, "settings.json").writeText("""{"formatVersion":1,"goalReminderHour":20}""")
        assertEquals(15, repo().planReminderLeadMinutes())
        assertEquals(20, repo().goalReminderHour())
    }
}
