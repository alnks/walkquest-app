package app.walkquest.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM-тесты батарейного стража (история 2.6, FR-10): пороги предупреждения
 * и автозавершения, однократность, рост заряда не повторяет сигналы,
 * кастомные пороги, валидация конструктора.
 */
class BatteryGuardTest {

    @Test
    fun `warning fires once at threshold then finish once at its threshold`() {
        val guard = BatteryGuard()
        assertEquals(BatterySignal.None, guard.onBatteryLevel(50))
        assertEquals(BatterySignal.None, guard.onBatteryLevel(16))
        // Ровно на пороге — сигнал.
        assertEquals(BatterySignal.WarningDue, guard.onBatteryLevel(15))
        // Дальнейший разряд не повторяет предупреждение.
        assertEquals(BatterySignal.None, guard.onBatteryLevel(14))
        assertEquals(BatterySignal.None, guard.onBatteryLevel(11))
        // Ровно на пороге автозавершения — штатный стоп.
        assertEquals(BatterySignal.FinishDue, guard.onBatteryLevel(10))
        // После автозавершения — тишина (запись остановлена, гвард не нужен).
        assertEquals(BatterySignal.None, guard.onBatteryLevel(5))
        assertEquals(BatterySignal.None, guard.onBatteryLevel(1))
    }

    @Test
    fun `charging up does not repeat warning and does not reset finish`() {
        val guard = BatteryGuard()
        assertEquals(BatterySignal.WarningDue, guard.onBatteryLevel(15))
        // Подключили зарядку: 15 → 60 → снова 15 — предупреждение не вернулось.
        assertEquals(BatterySignal.None, guard.onBatteryLevel(60))
        assertEquals(BatterySignal.None, guard.onBatteryLevel(15))
        // Зарядка кончилась, разряд продолжился → автозавершение всё равно сработает.
        assertEquals(BatterySignal.FinishDue, guard.onBatteryLevel(10))
    }

    @Test
    fun `low battery from the very start finishes immediately`() {
        // Старт записи при 8% (< порога автозавершения 10%): гвард отдаёт
        // FinishDue сразу — предупреждение при уже-критичном заряде бессмысленно,
        // прогулка завершается штатно на первой же проверке.
        val guard = BatteryGuard()
        assertEquals(BatterySignal.FinishDue, guard.onBatteryLevel(8))
        assertEquals(BatterySignal.None, guard.onBatteryLevel(8))
    }

    @Test
    fun `custom thresholds respected`() {
        val guard = BatteryGuard(warningPercent = 25, finishPercent = 20)
        assertEquals(BatterySignal.WarningDue, guard.onBatteryLevel(25))
        assertEquals(BatterySignal.None, guard.onBatteryLevel(22))
        assertEquals(BatterySignal.FinishDue, guard.onBatteryLevel(20))
    }

    @Test
    fun `thresholds equal finish takes priority on same level`() {
        // warning == finish: уровень на общем пороге — сразу FinishDue
        // (автозавершение приоритетнее предупреждения, проверка идёт первой).
        val guard = BatteryGuard(warningPercent = 10, finishPercent = 10)
        assertEquals(BatterySignal.FinishDue, guard.onBatteryLevel(10))
        assertEquals(BatterySignal.None, guard.onBatteryLevel(10))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `warning below finish rejected`() {
        BatteryGuard(warningPercent = 5, finishPercent = 10)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non positive thresholds rejected`() {
        BatteryGuard(warningPercent = 0, finishPercent = 0)
    }
}
