package app.walkquest.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM-тесты резолвера лимитов скорости по режиму (история 2.2, FR-6):
 * дефолты — гипотезы PRD OQ-1, override из настроек имеет приоритет.
 */
class SpeedLimitPolicyTest {

    @Test
    fun `defaults come from SpeedLimits hypotheses`() {
        assertEquals(12.0, SpeedLimitPolicy.defaultLimitKmh(WalkMode.WALKING), 0.0001)
        assertEquals(30.0, SpeedLimitPolicy.defaultLimitKmh(WalkMode.CYCLING), 0.0001)
        assertEquals(SpeedLimits.WALKING_MPS, SpeedLimitPolicy.effectiveLimitMps(WalkMode.WALKING, null), 0.0001)
        assertEquals(SpeedLimits.CYCLING_MPS, SpeedLimitPolicy.effectiveLimitMps(WalkMode.CYCLING, null), 0.0001)
    }

    @Test
    fun `user override wins over default`() {
        assertEquals(10.0, SpeedLimitPolicy.effectiveLimitKmh(WalkMode.WALKING, 10.0), 0.0001)
        assertEquals(25.0, SpeedLimitPolicy.effectiveLimitKmh(WalkMode.CYCLING, 25.0), 0.0001)
    }

    @Test
    fun `non positive override falls back to default`() {
        assertEquals(12.0, SpeedLimitPolicy.effectiveLimitKmh(WalkMode.WALKING, 0.0), 0.0001)
        assertEquals(30.0, SpeedLimitPolicy.effectiveLimitKmh(WalkMode.CYCLING, -5.0), 0.0001)
    }
}
