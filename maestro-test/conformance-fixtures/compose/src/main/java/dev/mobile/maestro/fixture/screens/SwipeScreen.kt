package dev.mobile.maestro.fixture.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import dev.mobile.maestro.fixture.FixtureEmitter
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun SwipeScreen() {
    var translationX by remember { mutableFloatStateOf(0f) }
    var translationY by remember { mutableFloatStateOf(0f) }
    var downX by remember { mutableFloatStateOf(0f) }
    var downY by remember { mutableFloatStateOf(0f) }
    var downTime by remember { mutableLongStateOf(0L) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = "swipe_surface" }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        when (event.type) {
                            PointerEventType.Press -> {
                                downX = change.position.x
                                downY = change.position.y
                                downTime = System.currentTimeMillis()
                                translationX = 0f
                                translationY = 0f
                                FixtureEmitter.emit(
                                    "TOUCH",
                                    mapOf("x" to change.position.x.toInt(), "y" to change.position.y.toInt())
                                )
                                change.consume()
                            }
                            PointerEventType.Move -> {
                                translationX = (change.position.x - downX).coerceIn(-400f, 400f)
                                translationY = (change.position.y - downY).coerceIn(-400f, 400f)
                                change.consume()
                            }
                            PointerEventType.Release -> {
                                val dx = change.position.x - downX
                                val dy = change.position.y - downY
                                val durationMs = System.currentTimeMillis() - downTime
                                if (abs(dx) > 10 || abs(dy) > 10) {
                                    val dir = if (abs(dx) >= abs(dy)) {
                                        if (dx > 0) "RIGHT" else "LEFT"
                                    } else {
                                        if (dy > 0) "DOWN" else "UP"
                                    }
                                    FixtureEmitter.emit(
                                        "SWIPE",
                                        mapOf(
                                            "dir" to dir,
                                            "dx" to dx.toInt(),
                                            "dy" to dy.toInt(),
                                            "durationMs" to durationMs,
                                            "target" to "swipe_surface"
                                        )
                                    )
                                }
                                // animate back
                                translationX = 0f
                                translationY = 0f
                                change.consume()
                            }
                        }
                    }
                }
            }
    ) {
        // Grid that visibly moves with the drag so screen recordings capture motion
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(translationX.roundToInt(), translationY.roundToInt()) }
        ) {
            drawRect(color = Color(0xFF4FC3F7))
            val step = 140f
            var x = 0f
            while (x <= size.width) {
                drawLine(Color(0xFF01579B), Offset(x, 0f), Offset(x, size.height), strokeWidth = 6f)
                x += step
            }
            var y = 0f
            while (y <= size.height) {
                drawLine(Color(0xFF01579B), Offset(0f, y), Offset(size.width, y), strokeWidth = 6f)
                y += step
            }
        }
    }
}
