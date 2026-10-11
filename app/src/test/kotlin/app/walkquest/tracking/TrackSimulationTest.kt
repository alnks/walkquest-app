package app.walkquest.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * План темпа симуляции трека (история 1.15): парсинг фикстур (заголовок журнала,
 * поля acc/prov, усечённый хвост), темп по дельтам t с масштабом и потолком паузы.
 */
class TrackSimulationTest {

    private fun fixture(vararg lines: String): String =
        (listOf("""{"formatVersion":1,"startedAtEpochMillis":1}""") + lines).joinToString("\n")

    @Test
    fun `journal header is skipped, points parsed with acc and provider`() {
        val result = TrackSimulation.parse(
            fixture(
                """{"lat":59.93,"lon":30.33,"t":1000}""",
                """{"lat":59.94,"lon":30.34,"t":2000,"acc":12.5,"prov":"network"}""",
            ),
        ) as TrackSimulation.SimResult.Ok
        assertEquals(2, result.steps.size)
        assertEquals(null, result.steps[0].accuracyMeters)
        assertEquals(TrackSimulation.DEFAULT_PROVIDER, result.steps[0].provider)
        assertEquals(12.5f, result.steps[1].accuracyMeters)
        assertEquals("network", result.steps[1].provider)
        assertFalse(result.truncatedTail)
    }

    @Test
    fun `first step has no delay, later steps paced by t deltas`() {
        val result = TrackSimulation.parse(
            fixture(
                """{"lat":59.93,"lon":30.33,"t":10000}""",
                """{"lat":59.94,"lon":30.34,"t":14000}""",
                """{"lat":59.95,"lon":30.35,"t":20000}""",
            ),
        ) as TrackSimulation.SimResult.Ok
        assertEquals(0L, result.steps[0].delayBeforeMillis)
        assertEquals(4_000L, result.steps[1].delayBeforeMillis)
        assertEquals(6_000L, result.steps[2].delayBeforeMillis)
    }

    @Test
    fun `speed factor compresses pauses`() {
        val result = TrackSimulation.parse(
            fixture(
                """{"lat":59.93,"lon":30.33,"t":0}""",
                """{"lat":59.94,"lon":30.34,"t":8000}""",
            ),
            speedFactor = 4.0,
        ) as TrackSimulation.SimResult.Ok
        assertEquals(2_000L, result.steps[1].delayBeforeMillis)
    }

    @Test
    fun `long real pauses are capped by max step delay`() {
        val result = TrackSimulation.parse(
            fixture(
                """{"lat":59.93,"lon":30.33,"t":0}""",
                """{"lat":59.94,"lon":30.34,"t":3600000}""",
            ),
        ) as TrackSimulation.SimResult.Ok
        assertEquals(TrackSimulation.DEFAULT_MAX_STEP_DELAY_MS, result.steps[1].delayBeforeMillis)
    }

    @Test
    fun `out of order timestamps do not produce negative delays`() {
        val result = TrackSimulation.parse(
            fixture(
                """{"lat":59.93,"lon":30.33,"t":5000}""",
                """{"lat":59.94,"lon":30.34,"t":1000}""",
            ),
        ) as TrackSimulation.SimResult.Ok
        assertEquals(0L, result.steps[1].delayBeforeMillis)
    }

    @Test
    fun `truncated tail keeps valid prefix and is reported`() {
        val result = TrackSimulation.parse(
            fixture(
                """{"lat":59.93,"lon":30.33,"t":1000}""",
                """{"lat":59.94,"lon":30.3""",
            ),
        ) as TrackSimulation.SimResult.Ok
        assertEquals(1, result.steps.size)
        assertTrue(result.truncatedTail)
    }

    @Test
    fun `file without points is invalid`() {
        val result = TrackSimulation.parse("""{"formatVersion":1}""")
        assertTrue(result is TrackSimulation.SimResult.Invalid)
    }

    @Test
    fun `non positive speed factor is invalid`() {
        val result = TrackSimulation.parse(
            fixture("""{"lat":59.93,"lon":30.33,"t":1000}"""),
            speedFactor = 0.0,
        )
        assertTrue(result is TrackSimulation.SimResult.Invalid)
    }
}
