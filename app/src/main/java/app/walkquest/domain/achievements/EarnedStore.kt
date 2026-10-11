package app.walkquest.domain.achievements

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Хранилище полученных достижений (история 4.2, FR-15): earned.json — список
 * {id, at}; атомарная запись. Идемпотентность выдачи — на движке ( EarnedStore
 * отвечает только за персистентность). Чистый JVM (AD-10).
 */
class EarnedStore(private val file: File) {

    @Synchronized
    fun earnedIds(): Set<String> {
        if (!file.isFile) return emptySet()
        return try {
            EarnedJson.decodeFromString<StoredEarned>(file.readText()).earned.map { it.id }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    /** Засчитанные id с датой (для «получено …» на экране). */
    @Synchronized
    fun earned(): List<EarnedEntry> {
        if (!file.isFile) return emptyList()
        return try {
            EarnedJson.decodeFromString<StoredEarned>(file.readText()).earned
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun contains(id: String): Boolean = id in earnedIds()

    @Synchronized
    fun record(id: String, atEpochMillis: Long = System.currentTimeMillis()): Boolean {
        if (contains(id)) return false
        return try {
            val current = earned()
            val temp = File(file.parentFile, ".earned-${System.nanoTime()}.tmp")
            file.parentFile?.mkdirs()
            temp.writeText(EarnedJson.encodeToString(StoredEarned(earned = current + EarnedEntry(id, atEpochMillis))))
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) throw java.io.IOException("rename не удался")
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    @Serializable
    data class EarnedEntry(val id: String, val atEpochMillis: Long)

    @Serializable
    private data class StoredEarned(val earned: List<EarnedEntry> = emptyList())

    companion object {
        private val EarnedJson = Json { ignoreUnknownKeys = true }
    }
}
