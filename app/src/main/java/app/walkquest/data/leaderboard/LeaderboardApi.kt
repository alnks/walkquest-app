package app.walkquest.data.leaderboard

import app.walkquest.domain.leaderboard.LeaderboardSnapshot
import app.walkquest.domain.leaderboard.RegionAggregate
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Клиент Supabase (эпик 5, FR-19, AD-7): plain REST к PostgREST (OkHttp уже в
 * проекте — новых зависимостей нет). На сервер уходят ТОЛЬКО ник и агрегаты
 * (AD-3): таблицы profiles/aggregates; RLS (Done when 6) на стороне проекта.
 * Все вызовы — sealed-результаты, сеть синхронная (вызывающий — на IO).
 * Чистый JVM (AD-10): OkHttp работает и в unit-тестах (MiniHttpServer).
 */

class LeaderboardApi(
    private val baseUrl: String,
    private val anonKey: String,
) {

    /** Строка топа: ник + метрики (только агрегаты чужих — AD-3). */
    data class TopEntry(
        val nickname: String,
        val distanceMeters: Double,
        val fraction: Double,
    )

    sealed class Result {
        data class Ok(val top: List<TopEntry>) : Result()
        data class Failure(val detail: String) : Result()
    }

    /** Публикация профиля (ник). Ответ пуст — успех = 2xx. */
    fun upsertProfile(nickname: String): Boolean {
        val body = """{"nickname":"${nickname.replace("\"", "")}"}"""
        return post("$baseUrl/profiles", body, upsert = false) in 200..299
    }

    /** Публикация агрегатов (AD-3: только эти поля). */
    fun publishAggregates(snapshot: LeaderboardSnapshot): Boolean {
        val rows = snapshot.regions.joinToString(",") { agg ->
            """{"region_id":"${agg.regionId}","distance_meters":${agg.distanceMeters},"fraction":${agg.fraction}}"""
        }
        return post("$baseUrl/aggregates", "[$rows]", upsert = true) in 200..299
    }

    /** Топ региона по метрике (distance | fraction). */
    fun cityTop(regionId: String, metric: String): Result {
        val column = if (metric == "fraction") "fraction" else "distance_meters"
        return try {
            val conn = URL("$baseUrl/top?region_id=eq.$regionId&order=$column.desc&limit=50").openConnection()
                as HttpURLConnection
            conn.setRequestProperty("apikey", anonKey)
            conn.setRequestProperty("Authorization", "Bearer $anonKey")
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            val code = conn.responseCode
            if (code !in 200..299) return Result.Failure("HTTP $code")
            val text = conn.inputStream.use { it.readBytes().decodeToString() }
            val parsed = TopJson.decodeFromString<List<TopRow>>(text)
            Result.Ok(parsed.map { TopEntry(it.nickname, it.distance_meters, it.fraction) })
        } catch (e: Exception) {
            Result.Failure(e.message ?: e.toString())
        }
    }

    /** Создать код приглашения (возвращает код или null). */
    fun createInvite(): String? {
        val code = java.util.UUID.randomUUID().toString().take(8)
        return if (post("$baseUrl/invites", """{"code":"$code"}""", upsert = false) in 200..299) code else null
    }

    /** Принять приглашение (RPC создаёт двустороннюю дружбу; D5). */
    fun acceptInvite(code: String): Boolean {
        val body = """{"code":"${code.trim()}"}"""
        return post("$baseUrl/rpc/accept_invite", body, upsert = false) in 200..299
    }

    /** Добавить друга по нику (friends через profiles id — упрощённо v1). */
    fun addFriendByNickname(nickname: String): Boolean {
        // v1: дружба только через invite-коды; добавление по нику — через поиск профиля
        // (экран вызывает acceptInvite/invite-флоу). Метод оставлен для полноты API.
        return false
    }

    private fun post(url: String, json: String, upsert: Boolean): Int {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("apikey", anonKey)
            conn.setRequestProperty("Authorization", "Bearer $anonKey")
            conn.setRequestProperty("Content-Type", "application/json")
            if (upsert) conn.setRequestProperty("Prefer", "resolution=merge-duplicates")
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
        } catch (e: Exception) {
            -1
        }
    }

    @Serializable
    private data class TopRow(
        val nickname: String,
        val distance_meters: Double,
        val fraction: Double,
    )

    companion object {
        private val TopJson = Json { ignoreUnknownKeys = true }
    }
}

/** Офлайн-очередь снимков (FR-19: отложенная отправка при появлении сети). */
class LeaderboardOutbox(private val file: File) {

    @Synchronized
    fun pending(): List<LeaderboardSnapshot> {
        if (!file.isFile) return emptyList()
        return try {
            OutboxJson.decodeFromString<StoredOutbox>(file.readText()).pending
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun enqueue(snapshot: LeaderboardSnapshot) {
        save(pending() + snapshot)
    }

    @Synchronized
    fun clear() {
        save(emptyList())
    }

    private fun save(list: List<LeaderboardSnapshot>) {
        try {
            val temp = File(file.parentFile, ".outbox-${System.nanoTime()}.tmp")
            file.parentFile?.mkdirs()
            temp.writeText(OutboxJson.encodeToString(StoredOutbox(list)))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        } catch (e: Exception) {
            // Очередь не критична: повторная отправка по ручному обновлению.
        }
    }

    @Serializable
    private data class StoredOutbox(val pending: List<LeaderboardSnapshot> = emptyList())

    companion object {
        private val OutboxJson = Json { ignoreUnknownKeys = true }
    }
}

/** Утилита: региональные агрегаты из истории (AD-3 — только суммы). */
fun regionAggregates(
    history: app.walkquest.domain.WalkHistory,
    fractions: Map<String, Double>,
): List<RegionAggregate> {
    val distanceByRegion = HashMap<String, Double>()
    for (record in history.records()) {
        for (regionId in record.regionIds) {
            distanceByRegion.merge(regionId, record.distanceMeters) { a, b -> a + b }
        }
    }
    return distanceByRegion.map { (regionId, distance) ->
        RegionAggregate(regionId, distance, fractions[regionId] ?: 0.0)
    }
}
