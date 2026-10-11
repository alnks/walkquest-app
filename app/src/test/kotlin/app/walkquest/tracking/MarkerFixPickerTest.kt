package app.walkquest.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Выбор фикса для маркера «я» во время записи (полевой тест 3, 2026-10-05):
 * сервис слушает GPS и NETWORK; маркеру — свежайший фикс независимо от провайдера.
 * Запоздавший сетевой реплей свежий GPS-фикс не перетирает.
 */
class MarkerFixPickerTest {

    @Test
    fun `first fix is always accepted`() {
        val picker = MarkerFixPicker()
        assertTrue(picker.acceptIfNewer(accuracyMeters = 600f, fixEpochMillis = 1_000L))
    }

    @Test
    fun `newer fix wins regardless of worse accuracy`() {
        val picker = MarkerFixPicker()
        assertTrue(picker.acceptIfNewer(accuracyMeters = 8f, fixEpochMillis = 1_000L))
        // Свежий сетевой фикс в помещении лучше, чем устаревший GPS.
        assertTrue(picker.acceptIfNewer(accuracyMeters = 80f, fixEpochMillis = 2_000L))
        assertTrue(picker.acceptIfNewer(accuracyMeters = null, fixEpochMillis = 3_000L))
    }

    @Test
    fun `stale replayed fix does not displace fresher one`() {
        val picker = MarkerFixPicker()
        assertTrue(picker.acceptIfNewer(accuracyMeters = 500f, fixEpochMillis = 5_000L))
        // Сетевой провайдер прислал старый фикс с опозданием — маркер не сдвигается.
        assertFalse(picker.acceptIfNewer(accuracyMeters = 5f, fixEpochMillis = 4_999L))
        assertFalse(picker.acceptIfNewer(accuracyMeters = null, fixEpochMillis = 1_000L))
        // А следующий свежий — снова принимается.
        assertTrue(picker.acceptIfNewer(accuracyMeters = 30f, fixEpochMillis = 6_000L))
    }

    @Test
    fun `same timestamp better accuracy wins`() {
        val picker = MarkerFixPicker()
        assertTrue(picker.acceptIfNewer(accuracyMeters = 80f, fixEpochMillis = 1_000L))
        // Тот же момент, но точнее — вытесняет (дубликаты двух провайдеров).
        assertTrue(picker.acceptIfNewer(accuracyMeters = 12f, fixEpochMillis = 1_000L))
        // Хуже при равном времени — нет.
        assertFalse(picker.acceptIfNewer(accuracyMeters = 40f, fixEpochMillis = 1_000L))
        // Эталон без accuracy равным по времени фиксом не перетирается.
        assertTrue(picker.acceptIfNewer(accuracyMeters = null, fixEpochMillis = 2_000L))
        assertFalse(picker.acceptIfNewer(accuracyMeters = 5f, fixEpochMillis = 2_000L))
    }

    @Test
    fun `reset starts selection from scratch`() {
        val picker = MarkerFixPicker()
        assertTrue(picker.acceptIfNewer(accuracyMeters = 10f, fixEpochMillis = 9_000L))
        picker.reset()
        // После сброса старый по времени фикс снова валиден (новая запись).
        assertTrue(picker.acceptIfNewer(accuracyMeters = 10f, fixEpochMillis = 1_000L))
    }
}
