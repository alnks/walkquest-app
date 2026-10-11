package app.walkquest.data.poicard

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Прочтённые карточки POI (история 4.1, достижения A11–A16): множество osm_id
 * открытых карточек. Файл poicards.json (атомарная запись). Источник записи —
 * MapViewModel.onPoiTapped (UI действие «открыть карточку» = прочтение).
 * Чистый JVM (AD-10).
 */
class PoiCardStore(private val file: File) {

    /** Прочтённые osm_id. */
    @Synchronized
    fun readIds(): Set<String> {
        if (!file.isFile) return emptySet()
        return try {
            PoiCardJson.decodeFromString<StoredCards>(file.readText()).ids.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun readCount(): Int = readIds().size

    fun contains(osmId: String): Boolean = osmId in readIds()

    /** Фиксирует прочтение (идемпотентно). @return true — первое прочтение. */
    @Synchronized
    fun markRead(osmId: String): Boolean {
        val current = readIds()
        if (osmId in current) return false
        return try {
            val temp = File(file.parentFile, ".poicards-${System.nanoTime()}.tmp")
            file.parentFile?.mkdirs()
            temp.writeText(PoiCardJson.encodeToString(StoredCards(ids = (current + osmId).sorted())))
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
    private data class StoredCards(val ids: List<String> = emptyList())

    companion object {
        private val PoiCardJson = Json { ignoreUnknownKeys = true }
    }
}
