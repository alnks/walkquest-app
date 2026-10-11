package app.walkquest.domain.quests

import java.io.File
import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Задания (история 4.5, FR-16): пул 22 из content-catalog (финал 2026-10-01).
 * Раздача — случайная или выбор из 6 из ОДНОГО пула (веса — после запуска,
 * каталог «Открытые вопросы»). Одно активное за раз; взятие поверх активного —
 * через подтверждающий диалог UI («Взять новое / Оставить текущее»), прежнее
 * снимается без штрафа (счётчик отказов растёт). Выполнение — на честном слове.
 * Чистый Kotlin (AD-10): store файловый, JVM-тестируемо.
 */

data class QuestDef(val id: String, val icon: String, val name: String, val description: String)

object QuestCatalog {
    val ALL: List<QuestDef> = listOf(
        QuestDef("T1", "🎨", "Пять вещей цвета дня", "Найдите 5 вещей одного цвета — выберите цвет сами. Здорово потом составить фотоколлаж на память."),
        QuestDef("T2", "🏛️", "Лицо города", "Найдите 3 детали архитектуры, которых раньше не замечали: наличник, дату на фасаде, старую вывеску."),
        QuestDef("T3", "🐦", "Птичий день", "Понаблюдайте за птицами, которые встретятся по пути. Голуби тоже считаются."),
        QuestDef("T4", "📚", "История рядом", "Выберите любое здание по пути и узнайте о нём пару фактов: поиск в интернете, табличка на фасаде."),
        QuestDef("T5", "🐈", "Кот района", "Встретьте кота: дворового, витринного, гуляющего на поводке — любого. Можно поздороваться."),
        QuestDef("T6", "🔝", "Второй этаж", "Смотрите выше первых этажей: найдите 3 интересные детали наверху — балконы, карнизы, башенки."),
        QuestDef("T7", "🗿", "Кому памятник", "Найдите памятник или мемориальную доску по пути и узнайте, кем был этот человек."),
        QuestDef("T8", "🖼️", "Уличное искусство", "Найдите мурал, граффити или просто необычно оформленную стену."),
        QuestDef("T9", "👃", "Запахи города", "Поймайте 3 разных запаха за прогулку: выпечка, дождь, скошенная трава, кофе из окна."),
        QuestDef("T10", "🔁", "Кольцевая", "Вернитесь домой другой дорогой, не повторяя путь туда."),
        QuestDef("T11", "🌉", "Мост", "Перейдите любой мост по пути — над водой, путями или дорогой."),
        QuestDef("T12", "🪧", "Именная улица", "Пройдите по улице, названной в честь человека, и узнайте, кем он был."),
        QuestDef("T13", "🤫", "Тихий двор", "Найдите двор, где заметно тише, чем на улице. Постойте там минуту."),
        QuestDef("T14", "🪙", "Монетка", "На трёх перекрёстках подряд выбирайте направление наугад. Куда заведёт?"),
        QuestDef("T15", "🔥", "К огню", "Дойдите до ближайшего путеводного огня на карте и прочтите его историю."),
        QuestDef("T16", "🏃", "Интервалы", "3 раза смените шаг на быстрый — по 1–2 минуте. Между ними обычный темп."),
        QuestDef("T17", "🐌", "Замедление", "Пройдите 5 минут заметно медленнее обычного. Смотрите по сторонам."),
        QuestDef("T18", "⚡", "Быстрые десять", "10 минут бодрого шага — быстрее обычного, но без бега."),
        QuestDef("T19", "💬", "Тёплое слово", "Сделайте комплимент прохожему или продавцу. Искренне."),
        QuestDef("T20", "🎧", "Без наушников", "Пройдите 10 минут без музыки. Послушайте город."),
        QuestDef("T21", "📵", "Экран вниз", "Пройдите 15 минут, не доставая телефон. Туман сам расступится."),
        QuestDef("T22", "🪑", "Скамейка с видом", "Найдите скамейку в приятном месте и посидите 5 минут. Просто так."),
    )

    fun byId(id: String): QuestDef? = ALL.firstOrNull { it.id == id }
}

/** Раздача из пула: случайная карточка или выбор из 6 (без весов v1). */
class QuestPool(
    private val catalog: List<QuestDef> = QuestCatalog.ALL,
    private val random: Random = Random.Default,
) {
    /** Случайное задание из полного пула. */
    fun random(): QuestDef = catalog[random.nextInt(catalog.size)]

    /** 6 предложенных для выбора (без повторов; пул ≥ 6). */
    fun pickSix(): List<QuestDef> = catalog.shuffled(random).take(6)

    /** Для тестов: раздача из фиксированного Random(seed). */
    companion object {
        fun seeded(seed: Int) = QuestPool(random = Random(seed))
    }
}

/** Состояние заданий: активное, выполненные, счётчики. */
data class QuestState(
    val activeId: String? = null,
    val doneIds: Set<String> = emptySet(),
    val completedCount: Int = 0,
    val declinedCount: Int = 0,
    /** Всего принято заданий (D1 «Азарт»: первое принятое). */
    val acceptedCount: Int = 0,
)

/** Смена активного задания: подтверждённая замена (без штрафа) или отказ. */
sealed class QuestTakeResult {
    /** Задание взято; replaced = true — прежнее снято подтверждённой заменой. */
    data class Taken(val questId: String, val replaced: Boolean) : QuestTakeResult()
    data class AlreadyActive(val questId: String) : QuestTakeResult()
    data class Invalid(val reason: String) : QuestTakeResult()
}

/** Логика состояния заданий (чистый Kotlin): переходы + счётчики. */
class QuestStateMachine {
    fun take(state: QuestState, questId: String, confirmedReplace: Boolean): QuestTakeResult {
        if (QuestCatalog.byId(questId) == null) return QuestTakeResult.Invalid("нет такого задания")
        if (state.activeId == questId) return QuestTakeResult.AlreadyActive(questId)
        val replacing = state.activeId != null
        if (replacing && !confirmedReplace) return QuestTakeResult.Invalid("требуется подтверждение замены")
        return QuestTakeResult.Taken(questId, replaced = replacing)
    }

    /** Применение взятия: снятое без выполнения уходит в отказы (без штрафа за сам факт). */
    fun applyTake(state: QuestState, questId: String): QuestState {
        val replacingActive = state.activeId != null && state.activeId != questId
        return state.copy(
            activeId = questId,
            declinedCount = if (replacingActive) state.declinedCount + 1 else state.declinedCount,
            acceptedCount = state.acceptedCount + 1,
        )
    }

    fun complete(state: QuestState): QuestState {
        val active = state.activeId ?: return state
        return state.copy(
            activeId = null,
            doneIds = state.doneIds + active,
            completedCount = state.completedCount + 1,
        )
    }

    /** «Снять задание» — отказ без штрафа. */
    fun decline(state: QuestState): QuestState =
        if (state.activeId == null) state else state.copy(activeId = null, declinedCount = state.declinedCount + 1)
}

/** Персистентность состояния заданий (quests.json, атомарная запись). */
class QuestStateStore(private val file: File) {

    @Synchronized
    fun load(): QuestState {
        if (!file.isFile) return QuestState()
        return try {
            val stored = QuestJson.decodeFromString<StoredQuestState>(file.readText())
            QuestState(
                activeId = stored.activeId,
                doneIds = stored.doneIds.toSet(),
                completedCount = stored.completedCount,
                declinedCount = stored.declinedCount,
                acceptedCount = stored.acceptedCount,
            )
        } catch (e: Exception) {
            QuestState()
        }
    }

    @Synchronized
    fun save(state: QuestState) {
        try {
            val temp = File(file.parentFile, ".quests-${System.nanoTime()}.tmp")
            file.parentFile?.mkdirs()
            temp.writeText(
                QuestJson.encodeToString(
                    StoredQuestState(
                        activeId = state.activeId,
                        doneIds = state.doneIds.sorted(),
                        completedCount = state.completedCount,
                        declinedCount = state.declinedCount,
                        acceptedCount = state.acceptedCount,
                    ),
                ),
            )
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) throw java.io.IOException("rename не удался")
            }
        } catch (e: Exception) {
            // Состояние заданий не критично: пропускаем запись, UI отразит в памяти.
        }
    }

    @Serializable
    private data class StoredQuestState(
        val activeId: String? = null,
        val doneIds: List<String> = emptyList(),
        val completedCount: Int = 0,
        val declinedCount: Int = 0,
        val acceptedCount: Int = 0,
    )

    companion object {
        private val QuestJson = Json { ignoreUnknownKeys = true }
    }
}
