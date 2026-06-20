package dev.mobile.maestro.fixture.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.mobile.maestro.fixture.FixtureEmitter

@Composable
fun TapScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        if (event.type == PointerEventType.Press) {
                            FixtureEmitter.emit(
                                "TOUCH",
                                mapOf(
                                    "x" to change.position.x.toInt(),
                                    "y" to change.position.y.toInt()
                                )
                            )
                        }
                    }
                }
            }
    ) {
        Button(
            onClick = {
                FixtureEmitter.emit("TAP", mapOf("target" to "tap_target"))
            },
            modifier = Modifier
                .offset(x = 100.dp, y = 400.dp)
                .size(width = 200.dp, height = 60.dp)
                .semantics { contentDescription = "tap_target" }
        ) {
            Text("tap")
        }

        // Long press: use pointerInput with raw DOWN/UP tracking — same contract as native.
        // detectTapGestures(onPress) conflicts with the button's own gesture handler; using
        // a plain awaitPointerEventScope ensures we see the raw events UiAutomator sends.
        Box(
            modifier = Modifier
                .offset(x = 100.dp, y = 520.dp)
                .size(width = 200.dp, height = 60.dp)
                .semantics { contentDescription = "longpress_target" }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitPointerEvent()
                            val downChange = down.changes.firstOrNull() ?: continue
                            if (down.type != PointerEventType.Press) continue
                            val downAt = System.currentTimeMillis()
                            // Wait for UP
                            while (true) {
                                val next = awaitPointerEvent()
                                if (next.type == PointerEventType.Release) {
                                    val downMs = System.currentTimeMillis() - downAt
                                    if (downMs >= 500) {
                                        FixtureEmitter.emit(
                                            "LONG_PRESS",
                                            mapOf(
                                                "target" to "longpress_target",
                                                "downMs" to downMs
                                            )
                                        )
                                    }
                                    break
                                }
                            }
                        }
                    }
                }
        ) {
            // Visual button rendered as a Material button inside
            Button(
                onClick = {},
                modifier = Modifier.fillMaxSize()
            ) {
                Text("longpress")
            }
        }
    }
}
