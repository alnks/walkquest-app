package app.walkquest.tracking

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Симуляция трека в debug-сборке (история 1.15, QA-инструментарий).
 *
 * Чистый JVM (AD-10): парсинг файла-фикстуры и план темпа — без android.*;
 * TrackingService лишь превращает шаги плана в synthetic Location и гонит
 * их через тот же [TrackingService.onLocation], что живые фиксы LocationManager.
 * Так сценарии «грубый первый фикс с вышек», «телепорт», «GPS замолчал — живёт
 * NETWORK», «accuracy хуже порога» воспроизводятся детерминированно — то, что
 * `emu geo fix` не умеет (accuracy не регулируется, всегда GPS-провайдер).
 *
 * Формат файла — журнал .track (AD-5, JSONL) плюс опциональное поле `prov`
 * (провайдер точки: "gps"/"network"; отсутствует — [DEFAULT_PROVIDER]):
 *   строка 1 — заголовок {"formatVersion":1,...} (как в журнале; строки без
 *   lat/lon пропускаются, битый хвост обрывает чтение — семантика TrackJournal);
 *   строки 2..N — {"lat":…,"lon":…,"t":…,"acc":…?,"prov":…?}.
 * Поле `prov` журналом никогда не пишется — это расширение фикстур; читатели
 * журнала его терпимо игнорируют (ignoreUnknownKeys).
 *
 * Темп: паузы между точками — дельты `t` / [speedFactor], усечённые сверху
 * [maxStepDelayMillis] (длинные реальные паузы не заставляют ждать). Точки
 * подаются с «текущим» временем фикса (Location.time ставится в момент эмиссии) —
 * проверки свежести маркера (30 с) проходят как при живой записи.
 */
object TrackSimulation {

    const val DEFAULT_PROVIDER = "gps"
    const val DEFAULT_MAX_STEP_DELAY_MS = 10_000L

    /** Одна точка плана: пауза ПЕРЕД подачей и сам фикс. */
    data class SimStep(
        val delayBeforeMillis: Long,
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float?,
        val provider: String,
    )

    sealed class SimResult {
        /** План проигрывания; [points] точек, усечённый хвост — как у журнала. */
        data class Ok(val steps: List<SimStep>, val truncatedTail: Boolean) : SimResult()

        /** Файл не читается или точек нет — симуляция не стартует. */
        data class Invalid(val detail: String) : SimResult()
    }

    @Serializable
    private data class SimPointLine(
        val lat: Double,
        val lon: Double,
        val t: Long,
        val acc: Float? = null,
        val prov: String? = null,
    )

    private val SimJson = Json { ignoreUnknownKeys = true }

    /**
     * Разбирает содержимое файла-фикстуры в план темпа.
     * [speedFactor] > 1 ускоряет проигрывание (паузы делятся); ≤ 0 — ошибка.
     */
    fun parse(
        text: String,
        speedFactor: Double = 1.0,
        maxStepDelayMillis: Long = DEFAULT_MAX_STEP_DELAY_MS,
    ): SimResult {
        if (speedFactor <= 0.0) return SimResult.Invalid("speedFactor должен быть > 0")
        val points = ArrayList<SimPointLine>()
        var truncated = false
        for (line in text.split('\n')) {
            if (line.isBlank()) continue
            val parsed = try {
                SimJson.decodeFromString<SimPointLine>(line)
            } catch (e: Exception) {
                // Заголовок журнала (нет lat/lon) не парсится точкой — пропускаем
                // молча; битая строка внутри серии точек — усечённый хвост, стоп
                // (семантика TrackJournal: валидный префикс сохраняется).
                if (points.isNotEmpty()) {
                    truncated = true
                    break
                }
                continue
            }
            points.add(parsed)
        }
        if (points.isEmpty()) {
            return SimResult.Invalid("в файле нет ни одной точки {lat, lon, t}")
        }
        val steps = ArrayList<SimStep>(points.size)
        var previousT: Long? = null
        for (p in points) {
            val prev = previousT
            val delay = if (prev == null) {
                0L
            } else {
                ((p.t - prev).coerceAtLeast(0L) / speedFactor)
                    .toLong()
                    .coerceAtMost(maxStepDelayMillis)
            }
            steps.add(
                SimStep(
                    delayBeforeMillis = delay,
                    latitude = p.lat,
                    longitude = p.lon,
                    accuracyMeters = p.acc,
                    provider = p.prov?.takeIf { it.isNotBlank() } ?: DEFAULT_PROVIDER,
                ),
            )
            previousT = p.t
        }
        return SimResult.Ok(steps, truncated)
    }
}
