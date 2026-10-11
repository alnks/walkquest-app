package app.walkquest.domain

import app.walkquest.data.plans.PlannedWalk
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Выполненность плана (история 3.6, FR-17): план выполнен, если в его локальный
 * день была хотя бы одна завершённая прогулка. Пропуск не наказывается и на
 * серию не влияет — здесь только вывод факта. Чистый Kotlin (AD-10).
 */
class PlanPolicy(
    private val history: WalkHistory,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    fun isFulfilled(plan: PlannedWalk): Boolean =
        history.walksOn(plan.localDate(zone)).isNotEmpty()
}

/** Локальный день плана (расширение — в domain, не в data). */
fun PlannedWalk.localDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    LocalDate.ofInstant(Instant.ofEpochMilli(plannedAtEpochMillis), zone)
