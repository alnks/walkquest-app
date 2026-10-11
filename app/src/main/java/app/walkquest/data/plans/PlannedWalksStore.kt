package app.walkquest.data.plans

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Запланированные прогулки (история 3.6, FR-17): хранилище plans/plans.json —
 * список планов с атомарной записью (tmp+rename, как настройки). План: время
 * начала и факт напоминания. Выполненность НЕ хранится: она выводится
 * по истории прогулок («в день плана была прогулка») — пропуск не наказывает
 * и серию не трогает (FR-17). Чистый JVM (AD-10): файл инжектируется.
 */
class PlannedWalksStore(private val file: File) {

    /** Все планы, от ближайших к дальним; битый файл — пусто (не краш). */
    @Synchronized
    fun plans(): List<PlannedWalk> {
        if (!file.isFile) return emptyList()
        return try {
            PlansJson.decodeFromString<List<StoredPlan>>(file.readText())
                .map { it.toPlan() }
                .sortedBy { it.plannedAtEpochMillis }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Добавляет план; @return ошибку хранилища или null при успехе. */
    @Synchronized
    fun add(plan: PlannedWalk): PlanStoreError? {
        val current = plans()
        if (current.any { it.id == plan.id }) return null // идемпотентность
        return write(current + plan)
    }

    /** Удаляет план (отмена); отсутствующий id — no-op. */
    @Synchronized
    fun remove(planId: Long): PlanStoreError? =
        write(plans().filterNot { it.id == planId })

    /** Помечает напоминание отправленным (однократность). */
    @Synchronized
    fun markReminded(planId: Long): PlanStoreError? =
        write(plans().map { if (it.id == planId) it.copy(reminded = true) else it })

    private fun write(plans: List<PlannedWalk>): PlanStoreError? {
        val temp = File(file.parentFile, ".plans-${System.nanoTime()}.tmp")
        return try {
            file.parentFile?.mkdirs()
            temp.writeText(PlansJson.encodeToString(plans.map { it.toStored() }))
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) throw java.io.IOException("rename $temp → $file не удался")
            }
            null
        } catch (e: Exception) {
            PlanStoreError.StorageError(e.message ?: e.toString())
        } finally {
            temp.delete()
        }
    }

    companion object {
        private val PlansJson = Json { ignoreUnknownKeys = true }
    }
}

/** Запланированная прогулка; [id] — время создания (epoch millis). */
data class PlannedWalk(
    val id: Long,
    val plannedAtEpochMillis: Long,
    val reminded: Boolean = false,
)

/** Ошибки хранилища планов (sealed-результаты, не исключения через границы). */
sealed class PlanStoreError {
    abstract val message: String

    data class StorageError(val detail: String) : PlanStoreError() {
        override val message get() = "Ошибка хранилища планов: $detail"
    }
}

@Serializable
private data class StoredPlan(
    val id: Long,
    val plannedAtEpochMillis: Long,
    val reminded: Boolean = false,
)

private fun PlannedWalk.toStored() = StoredPlan(id, plannedAtEpochMillis, reminded)
private fun StoredPlan.toPlan() = PlannedWalk(id, plannedAtEpochMillis, reminded)
