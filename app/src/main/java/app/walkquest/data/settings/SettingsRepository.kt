package app.walkquest.data.settings

import app.walkquest.domain.GoalType
import app.walkquest.domain.WalkGoal
import app.walkquest.tracking.WalkMode
import java.io.File
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Настройки приложения (история 1.10): домашний регион (AD-9).
 *
 * Хранилище — один json-файл `settings/settings.json` (формат версионируется
 * formatVersion, Consistency Conventions). Запись атомарная: temp → rename
 * (как FogRepository 1.6 / PackageInstaller 1.5). Битый/несовместимый файл →
 * дефолтные настройки (домашний не выбран), приложение не падает.
 *
 * Правило «какой регион домашний, если не выбран» живёт в domain
 * (HomeRegionPolicy) — здесь только персистентность.
 *
 * Чистый JVM-код (AD-10): корень инжектируется из AppContainer
 * (`File(context.filesDir, "settings")`), Android не импортируется.
 */
class SettingsRepository(private val settingsRoot: File) {

    private val file: File get() = File(settingsRoot, FILE_NAME)

    /** Сохранённый домашний регион или null (не выбран / файл битый / пакет удалён — решает domain). */
    @Synchronized
    fun homeRegionId(): String? = read().homeRegionId

    /** Запоминает домашний регион. @return ошибку хранилища или null при успехе. */
    @Synchronized
    fun setHomeRegion(regionId: String): SettingsError? = write(read().copy(homeRegionId = regionId))

    // --- Режим прогулки и лимиты скорости (история 2.2, FR-6) ---

    /** Последний выбранный режим прогулки; не выбран/битый файл — WALKING (по умолчанию PRD). */
    @Synchronized
    fun walkMode(): WalkMode = read().walkMode?.let { mode ->
        WalkMode.entries.firstOrNull { it.name == mode }
    } ?: WalkMode.WALKING

    @Synchronized
    fun setWalkMode(mode: WalkMode): SettingsError? = write(read().copy(walkMode = mode.name))

    /**
     * Пользовательский override лимита скорости режима, км/ч; null — дефолт режима
     * (решает SpeedLimitPolicy). Провалидированный ввод: только положительные
     * значения сохраняются, иначе ошибка (UI показывает её, состояние не меняется).
     */
    @Synchronized
    fun walkSpeedLimitKmh(mode: WalkMode): Double? = when (mode) {
        WalkMode.WALKING -> read().walkingLimitKmh
        WalkMode.CYCLING -> read().cyclingLimitKmh
    }

    @Synchronized
    fun setWalkSpeedLimitKmh(mode: WalkMode, kmh: Double?): SettingsError? {
        if (kmh != null && kmh <= 0.0) return SettingsError.StorageError("лимит должен быть положительным")
        val current = read()
        return write(
            when (mode) {
                WalkMode.WALKING -> current.copy(walkingLimitKmh = kmh)
                WalkMode.CYCLING -> current.copy(cyclingLimitKmh = kmh)
            },
        )
    }

    // --- Автопауза (история 2.4, FR-7) ---

    /** Автопауза после 15 минут неподвижности; default true (отключается в настройках). */
    @Synchronized
    fun autoPauseEnabled(): Boolean = read().autoPauseEnabled

    @Synchronized
    fun setAutoPauseEnabled(enabled: Boolean): SettingsError? =
        write(read().copy(autoPauseEnabled = enabled))

    // --- Напоминание о забытой прогулке (история 2.5, FR-7) ---

    /** Напоминание после паузы + второго окна неподвижности; default true. */
    @Synchronized
    fun forgottenWalkReminderEnabled(): Boolean = read().forgottenWalkReminderEnabled

    @Synchronized
    fun setForgottenWalkReminderEnabled(enabled: Boolean): SettingsError? =
        write(read().copy(forgottenWalkReminderEnabled = enabled))

    // --- Батарейный страж (история 2.6, FR-10) ---

    /** Порог предупреждения, %; default 15. */
    @Synchronized
    fun batteryWarningPercent(): Int = read().batteryWarningPercent

    /** Порог автозавершения, %; default 10. */
    @Synchronized
    fun batteryFinishPercent(): Int = read().batteryFinishPercent

    /**
     * Пороги батарейного стража, %: оба в 1..100, предупреждение >= автозавершения.
     * Невалидная пара не сохраняется — ошибка, состояние не меняется.
     */
    @Synchronized
    fun setBatteryThresholds(warningPercent: Int, finishPercent: Int): SettingsError? {
        if (warningPercent !in 1..100 || finishPercent !in 1..100) {
            return SettingsError.StorageError("пороги должны быть в 1..100%")
        }
        if (warningPercent < finishPercent) {
            return SettingsError.StorageError("порог предупреждения не может быть ниже автозавершения")
        }
        return write(
            read().copy(
                batteryWarningPercent = warningPercent,
                batteryFinishPercent = finishPercent,
            ),
        )
    }

    // --- Частота прогресс-уведомлений (история 2.7, FR-9) ---

    /** Интервал прогресс-уведомлений, мин; default 5. */
    @Synchronized
    fun progressNotificationIntervalMinutes(): Int = read().progressNotificationIntervalMinutes

    /** Интервал в 1..120 мин; иначе — отказ, состояние не меняется. */
    @Synchronized
    fun setProgressNotificationIntervalMinutes(minutes: Int): SettingsError? {
        if (minutes !in 1..120) {
            return SettingsError.StorageError("интервал уведомлений должен быть в 1..120 мин")
        }
        return write(read().copy(progressNotificationIntervalMinutes = minutes))
    }

    // --- Цель и онбординг (истории 3.2/3.3, FR-11/FR-12) ---

    /** Текущая цель или null (не выбрана — до онбординга; битая пара — null, не краш). */
    @Synchronized
    fun walkGoal(): WalkGoal? {
        val settings = read()
        val type = settings.goalType?.let { name ->
            GoalType.entries.firstOrNull { it.name == name }
        } ?: return null
        val value = settings.goalValue ?: return null
        return runCatching { WalkGoal(type, value) }.getOrNull()
    }

    /**
     * Сохранение цели с валидацией (FR-11: значения из диапазонов GoalType);
     * null — сброс цели (используется только до онбординга).
     */
    @Synchronized
    fun setWalkGoal(goal: WalkGoal?): SettingsError? {
        if (goal != null) {
            val invalid = WalkGoal.validate(goal.type, goal.value)
            if (invalid != null) return SettingsError.StorageError(invalid)
        }
        return write(
            read().copy(
                goalType = goal?.type?.name,
                goalValue = goal?.value,
            ),
        )
    }

    /** Онбординг пройден (3.3, FR-12). */
    @Synchronized
    fun onboardingDone(): Boolean = read().onboardingDone

    @Synchronized
    fun setOnboardingDone(done: Boolean): SettingsError? =
        write(read().copy(onboardingDone = done))

    // --- Умные напоминания по цели (история 3.5, FR-14) ---

    /** Напоминания по цели; default true (отдельно от уведомлений трекинга). */
    @Synchronized
    fun goalRemindersEnabled(): Boolean = read().goalRemindersEnabled

    @Synchronized
    fun setGoalRemindersEnabled(enabled: Boolean): SettingsError? =
        write(read().copy(goalRemindersEnabled = enabled))

    /** Час вечерней проверки, 0..23; default 19. */
    @Synchronized
    fun goalReminderHour(): Int = read().goalReminderHour

    @Synchronized
    fun setGoalReminderHour(hour: Int): SettingsError? {
        if (hour !in 0..23) return SettingsError.StorageError("час должен быть в 0..23")
        return write(read().copy(goalReminderHour = hour))
    }

    // --- Планировщик прогулок (история 3.6, FR-17) ---

    /** Lead напоминания плана, мин; default 15. */
    @Synchronized
    fun planReminderLeadMinutes(): Int = read().planReminderLeadMinutes

    /** Ник лидерборда или null. */
    @Synchronized
    fun leaderboardNickname(): String? = read().leaderboardNickname

    @Synchronized
    fun setLeaderboardNickname(nickname: String?): SettingsError? {
        if (nickname != null) {
            val invalid = app.walkquest.domain.leaderboard.NicknamePolicy.validate(nickname)
            if (invalid != null) return SettingsError.StorageError(invalid)
        }
        return write(read().copy(leaderboardNickname = nickname))
    }

    /** Участие в рейтингах; default true. */
    @Synchronized
    fun leaderboardVisible(): Boolean = read().leaderboardVisible

    @Synchronized
    fun setLeaderboardVisible(visible: Boolean): SettingsError? =
        write(read().copy(leaderboardVisible = visible))

    // --- Режим темы (история 7.16, решение владельца 2026-10-17) ---

    /** Режим темы; default SYSTEM — следуем настройкам телефона. */
    @Synchronized
    fun themeMode(): ThemeMode = read().themeMode?.let { name ->
        ThemeMode.entries.firstOrNull { it.name == name }
    } ?: ThemeMode.SYSTEM

    @Synchronized
    fun setThemeMode(mode: ThemeMode): SettingsError? = write(read().copy(themeMode = mode.name))

    /** Кнопка задания; default true (скрывается в настройках, FR-16). */
    @Synchronized
    fun showQuestButton(): Boolean = read().showQuestButton

    @Synchronized
    fun setShowQuestButton(show: Boolean): SettingsError? =
        write(read().copy(showQuestButton = show))

    /** Lead в 5..120 мин; иначе — отказ, состояние не меняется. */
    @Synchronized
    fun setPlanReminderLeadMinutes(minutes: Int): SettingsError? {
        if (minutes !in 5..120) return SettingsError.StorageError("напоминание плана должно быть в 5..120 мин")
        return write(read().copy(planReminderLeadMinutes = minutes))
    }

    private fun read(): AppSettings {
        if (!file.isFile) return AppSettings()
        return try {
            val parsed = SettingsJson.decodeFromString<AppSettings>(file.readText())
            if (parsed.formatVersion != FORMAT_VERSION) AppSettings() else parsed
        } catch (e: Exception) {
            AppSettings()
        }
    }

    private fun write(settings: AppSettings): SettingsError? {
        val temp = File(settingsRoot, ".settings-${System.nanoTime()}.tmp")
        return try {
            settingsRoot.mkdirs()
            temp.writeText(SettingsJson.encodeToString(settings))
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) throw IOException("rename $temp → $file не удался")
            }
            null
        } catch (e: Exception) {
            SettingsError.StorageError(e.message ?: e.toString())
        } finally {
            temp.delete()
        }
    }

    /** Содержимое settings.json (растёт между formatVersion — неизвестные поля игнорируем). */
    @Serializable
    data class AppSettings(
        val formatVersion: Int = FORMAT_VERSION,
        val homeRegionId: String? = null,
        /** Последний выбранный режим прогулки (2.2), имя enum'а WalkMode. */
        val walkMode: String? = null,
        /** Пользовательские лимиты скорости, км/ч (2.2); null — дефолт режима. */
        val walkingLimitKmh: Double? = null,
        val cyclingLimitKmh: Double? = null,
        /** Автопауза после неподвижности (2.4, FR-7); default true — по спеке PRD. */
        val autoPauseEnabled: Boolean = true,
        /** Напоминание о забытой прогулке (2.5, FR-7); default true. */
        val forgottenWalkReminderEnabled: Boolean = true,
        /** Порог предупреждения низкого заряда, % (2.6, FR-10); default 15. */
        val batteryWarningPercent: Int = 15,
        /** Порог автозавершения прогулки, % (2.6, FR-10); default 10. */
        val batteryFinishPercent: Int = 10,
        /** Интервал прогресс-уведомлений, мин (2.7, FR-9); default 5. */
        val progressNotificationIntervalMinutes: Int = 5,
        /** Тип цели (3.2, FR-11), имя GoalType; null — цель не выбрана (до онбординга). */
        val goalType: String? = null,
        /** Значение цели (3.2): прогулок/минут в день; для км в день — удвоенное. */
        val goalValue: Int? = null,
        /** Онбординг пройден (3.3, FR-12); цель при этом обязана быть (FR-11). */
        val onboardingDone: Boolean = false,
        /** Напоминания по цели (3.5, FR-14); независимы от уведомлений трекинга. */
        val goalRemindersEnabled: Boolean = true,
        /** Час вечерней проверки напоминания (3.5), 0..23; default 19. */
        val goalReminderHour: Int = 19,
        /** За сколько минут до плана напоминать (3.6, FR-17); default 15. */
        val planReminderLeadMinutes: Int = 15,
        /** Кнопка задания на карте (4.5, FR-16); default true. */
        val showQuestButton: Boolean = true,
        /** Ник лидерборда (5.x, FR-19); null — не задан (не спрашивали). */
        val leaderboardNickname: String? = null,
        /** Участие в рейтингах (FR-19: опционально); default true. */
        val leaderboardVisible: Boolean = true,
        /** Режим темы (7.16), имя ThemeMode; null/неизвестное — SYSTEM. */
        val themeMode: String? = null,
    )

    companion object {
        const val FORMAT_VERSION = 1
        const val FILE_NAME = "settings.json"
    }
}

/** Типизированные ошибки data/settings (sealed-результаты, не исключения через границы). */
sealed class SettingsError {
    abstract val message: String

    data class StorageError(val detail: String) : SettingsError() {
        override val message get() = "Ошибка хранилища настроек: $detail"
    }
}

private val SettingsJson: Json = Json { ignoreUnknownKeys = true }
