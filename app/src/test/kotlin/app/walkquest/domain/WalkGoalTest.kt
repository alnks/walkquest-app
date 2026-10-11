package app.walkquest.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-тесты цели прогулки (история 3.2, FR-11): диапазоны значений, валидация,
 * описания (склонения/км с запятой).
 */
class WalkGoalTest {

    @Test
    fun `valid goals accepted`() {
        WalkGoal(GoalType.WALKS_PER_WEEK, 1)
        WalkGoal(GoalType.WALKS_PER_WEEK, 7)
        WalkGoal(GoalType.MINUTES_PER_DAY, 10)
        WalkGoal(GoalType.MINUTES_PER_DAY, 600)
        WalkGoal(GoalType.KM_PER_DAY, 1) // 0,5 км
        WalkGoal(GoalType.KM_PER_DAY, 100) // 50 км
    }

    @Test(expected = IllegalArgumentException::class)
    fun `walks per week above range rejected`() {
        WalkGoal(GoalType.WALKS_PER_WEEK, 8)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `minutes below range rejected`() {
        WalkGoal(GoalType.MINUTES_PER_DAY, 9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `km above range rejected`() {
        WalkGoal(GoalType.KM_PER_DAY, 101)
    }

    @Test
    fun `validate returns reason or null`() {
        assertNull(WalkGoal.validate(GoalType.WALKS_PER_WEEK, 3))
        assertTrue(WalkGoal.validate(GoalType.WALKS_PER_WEEK, 0) != null)
        assertTrue(WalkGoal.validate(GoalType.MINUTES_PER_DAY, 601) != null)
        assertTrue(WalkGoal.validate(GoalType.KM_PER_DAY, 0) != null)
    }

    @Test
    fun `describe walks per week pluralizes`() {
        assertEquals("1 прогулка в неделю", WalkGoal(GoalType.WALKS_PER_WEEK, 1).describe())
        assertEquals("2 прогулки в неделю", WalkGoal(GoalType.WALKS_PER_WEEK, 2).describe())
        assertEquals("5 прогулок в неделю", WalkGoal(GoalType.WALKS_PER_WEEK, 5).describe())
        assertEquals("7 прогулок в неделю", WalkGoal(GoalType.WALKS_PER_WEEK, 7).describe())
    }

    @Test
    fun `describe minutes pluralizes`() {
        assertEquals("10 минут в день", WalkGoal(GoalType.MINUTES_PER_DAY, 10).describe())
        assertEquals("21 минута в день", WalkGoal(GoalType.MINUTES_PER_DAY, 21).describe())
        assertEquals("32 минуты в день", WalkGoal(GoalType.MINUTES_PER_DAY, 32).describe())
    }

    @Test
    fun `describe km uses comma decimals`() {
        assertEquals("1 км в день", WalkGoal(GoalType.KM_PER_DAY, 2).describe())
        assertEquals("1,5 км в день", WalkGoal(GoalType.KM_PER_DAY, 3).describe())
        assertEquals("0,5 км в день", WalkGoal(GoalType.KM_PER_DAY, 1).describe())
        assertEquals("25 км в день", WalkGoal(GoalType.KM_PER_DAY, 50).describe())
    }
}
