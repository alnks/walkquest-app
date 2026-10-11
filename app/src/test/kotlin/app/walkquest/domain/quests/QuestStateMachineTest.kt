package app.walkquest.domain.quests

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM-тесты заданий (история 4.5, FR-16): пул 22, раздача, одно активное,
 * замена без штрафа, выполнение/отказ, персистентность.
 */
class QuestStateMachineTest {

    private val machine = QuestStateMachine()

    @Test
    fun `catalog has 22 unique quests`() {
        assertEquals(22, QuestCatalog.ALL.size)
        assertEquals(22, QuestCatalog.ALL.map { it.id }.toSet().size)
        assertEquals("T1", QuestCatalog.ALL.first().id)
        assertEquals("T22", QuestCatalog.ALL.last().id)
    }

    @Test
    fun `pickSix returns six unique from full pool`() {
        val six = QuestPool.seeded(42).pickSix()
        assertEquals(6, six.size)
        assertEquals(6, six.map { it.id }.toSet().size)
        assertTrue(six.all { QuestCatalog.byId(it.id) != null })
    }

    @Test
    fun `random always returns existing quest`() {
        val pool = QuestPool.seeded(7)
        repeat(50) { assertTrue(QuestCatalog.byId(pool.random().id) != null) }
    }

    @Test
    fun `take without active succeeds without confirmation`() {
        val result = machine.take(QuestState(), "T5", confirmedReplace = false)
        assertTrue(result is QuestTakeResult.Taken && !result.replaced)
    }

    @Test
    fun `take over active requires confirmation and declines previous without penalty`() {
        val state = machine.applyTake(QuestState(), "T5")
        // Без подтверждения — отказ.
        assertTrue(machine.take(state, "T7", confirmedReplace = false) is QuestTakeResult.Invalid)
        // С подтверждением — замена, счётчик отказов растёт (прежнее снято без выполнения).
        val result = machine.take(state, "T7", confirmedReplace = true)
        assertTrue(result is QuestTakeResult.Taken && result.replaced)
        val next = machine.applyTake(state, "T7")
        assertEquals("T7", next.activeId)
        assertEquals(1, next.declinedCount)
    }

    @Test
    fun `same active quest is not retaken`() {
        val state = machine.applyTake(QuestState(), "T5")
        assertTrue(machine.take(state, "T5", confirmedReplace = false) is QuestTakeResult.AlreadyActive)
    }

    @Test
    fun `complete moves active to done and bumps counter`() {
        val state = machine.applyTake(QuestState(), "T5")
        val done = machine.complete(state)
        assertNull(done.activeId)
        assertEquals(setOf("T5"), done.doneIds)
        assertEquals(1, done.completedCount)
        assertEquals(0, done.declinedCount)
    }

    @Test
    fun `decline clears active without penalty to completions`() {
        val state = machine.applyTake(QuestState(), "T5")
        val declined = machine.decline(state)
        assertNull(declined.activeId)
        assertEquals(1, declined.declinedCount)
        assertEquals(0, declined.completedCount)
    }

    @Test
    fun `multiple completions accumulate`() {
        var state = QuestState()
        repeat(5) { i ->
            state = machine.applyTake(state, "T${i + 1}")
            state = machine.complete(state)
        }
        assertEquals(5, state.completedCount)
        assertEquals(5, state.doneIds.size)
    }
}

class QuestStateStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(): QuestStateStore = QuestStateStore(File(temp.root, "quests/quests.json"))

    @Test
    fun `roundtrip preserves state`() {
        val store = store()
        store.save(QuestState(activeId = "T3", doneIds = setOf("T1", "T2"), completedCount = 2, declinedCount = 1))
        val loaded = store.load()
        assertEquals("T3", loaded.activeId)
        assertEquals(setOf("T1", "T2"), loaded.doneIds)
        assertEquals(2, loaded.completedCount)
        assertEquals(1, loaded.declinedCount)
    }

    @Test
    fun `corrupt file loads default`() {
        val file = File(temp.root, "quests/quests.json")
        file.parentFile?.mkdirs()
        file.writeText("мусор")
        val loaded = store().load()
        assertNull(loaded.activeId)
        assertEquals(0, loaded.completedCount)
    }

    @Test
    fun `empty store loads default`() {
        val loaded = store().load()
        assertFalse(loaded.doneIds.isNotEmpty())
        assertNull(loaded.activeId)
    }
}
