package app.walkquest.logging

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.roundToInt

/**
 * Compose-интеграции уровневого логирования (только debug-сборка, см. WqLog).
 *
 * - [ScreenLogEffect] — лог открытия/закрытия экрана (WQ/SCREEN): ставится на
 *   корень каждого экрана; открытие = вход в композицию, закрытие = onDispose.
 * - [Modifier.wqLogGestures] — каждый жест на элементе (WQ/GESTURE): DOWN с
 *   координатами и UP/CANCEL. События не потребляются — clickable/drag
 *   элементов продолжают работать; модификатор только подслушивает.
 * - [Modifier.wqLogLayout] — расположение элемента (WQ/UI): onGloballyPositioned
 *   логирует ТОЛЬКО при фактическом изменении границ (первая раскладка,
 *   сдвиг, ресайз, скрытие), а не каждый layout-проход — иначе спам.
 */

/** Эффект открытия/закрытия экрана: в начало корневой композабл-функции экрана. */
@Composable
fun ScreenLogEffect(screenName: String) {
    DisposableEffect(screenName) {
        WqLog.d(WqLayer.SCREEN) { "$screenName: open" }
        onDispose { WqLog.d(WqLayer.SCREEN) { "$screenName: close" } }
    }
}

/**
 * Логирует жесты на элементе (DOWN/UP/CANCEL с координатами). События
 * не потребляются — обработчики элемента (clickable, свайпы) работают как раньше.
 * Вешается рядом с интерактивными элементами: вкладки навигации, кнопки, карточки.
 */
fun Modifier.wqLogGestures(label: String): Modifier = composed {
    if (!WqLog.isEnabled()) return@composed this
    this then Modifier.pointerInput(label) {
        awaitPointerEventScope {
            while (true) {
                // Initial pass: видим событие раньше потребителей; не трогаем
                // его — потребление остаётся за clickable/MapView ниже.
                val down = awaitPointerEvent(PointerEventPass.Initial)
                if (down.type != PointerEventType.Press) continue
                val pos = down.changes.first().position
                WqLog.d(WqLayer.GESTURE) {
                    "$label: DOWN @ (${pos.x.roundToInt()}, ${pos.y.roundToInt()})"
                }
                // Ждём отпускания/отмены всех пальцев.
                var last = down
                while (last.changes.any { it.pressed }) {
                    last = awaitPointerEvent(PointerEventPass.Initial)
                }
                WqLog.d(WqLayer.GESTURE) {
                    "$label: ${if (last.type == PointerEventType.Release) "UP" else "CANCEL"}"
                }
            }
        }
    }
}

/**
 * Логирует расположение элемента (WQ/UI) при изменении границ в окне:
 * первой раскладке, сдвиге, ресайзе, исчезновении (isAttached=false при dispose).
 */
fun Modifier.wqLogLayout(label: String): Modifier = composed {
    if (!WqLog.isEnabled()) return@composed this
    // remember: подпись последних границ должна пережить рекомпозиции,
    // иначе каждый recomposition лога бы спамил повторно.
    var lastBounds by remember { mutableStateOf("") }
    this then Modifier.onGloballyPositioned { coords ->
        if (!coords.isAttached) return@onGloballyPositioned
        val bounds = coords.boundsInWindow()
        val signature = "rect=(${bounds.left.roundToInt()},${bounds.top.roundToInt()})-" +
            "${bounds.right.roundToInt()}x${bounds.bottom.roundToInt()}"
        if (signature != lastBounds) {
            lastBounds = signature
            WqLog.d(WqLayer.UI) { "$label: $signature" }
        }
    }
}
