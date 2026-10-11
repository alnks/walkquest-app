package app.walkquest.data.plans

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM-тесты хранилища планов (история 3.6, FR-17): round-trip, удаление,
 * однократность напоминания, битый файл.
 */
class PlannedWalksStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(): PlannedWalksStore = PlannedWalksStore(File(temp.root, "plans/plans.json"))

    @Test
    fun `add then plans returns sorted by time`() {
        val store = store()
        assertNull(store.add(PlannedWalk(id = 2, plannedAtEpochMillis = 200L)))
        assertNull(store.add(PlannedWalk(id = 1, plannedAtEpochMillis = 100L)))
        assertEquals(listOf(1L, 2L), store.plans().map { it.id })
    }

    @Test
    fun `add is idempotent by id`() {
        val store = store()
        store.add(PlannedWalk(id = 1, plannedAtEpochMillis = 100L))
        assertNull(store.add(PlannedWalk(id = 1, plannedAtEpochMillis = 100L)))
        assertEquals(1, store.plans().size)
    }

    @Test
    fun `remove drops plan and tolerates missing id`() {
        val store = store()
        store.add(PlannedWalk(id = 1, plannedAtEpochMillis = 100L))
        store.add(PlannedWalk(id = 2, plannedAtEpochMillis = 200L))
        assertNull(store.remove(1))
        assertEquals(listOf(2L), store.plans().map { it.id })
        assertNull(store.remove(999)) // отсутствующий — no-op без ошибки
        assertEquals(1, store.plans().size)
    }

    @Test
    fun `markReminded flips flag once`() {
        val store = store()
        store.add(PlannedWalk(id = 1, plannedAtEpochMillis = 100L))
        assertNull(store.markReminded(1))
        assertTrue(store.plans().first().reminded)
    }

    @Test
    fun `corrupt file reads empty not crash`() {
        val file = File(temp.root, "plans/plans.json")
        file.parentFile?.mkdirs()
        file.writeText("мусор")
        assertTrue(store().plans().isEmpty())
    }

    @Test
    fun `empty store reads empty`() {
        assertTrue(store().plans().isEmpty())
    }
}
