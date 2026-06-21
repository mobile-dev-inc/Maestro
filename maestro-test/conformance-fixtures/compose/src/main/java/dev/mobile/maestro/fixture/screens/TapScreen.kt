package dev.mobile.maestro.fixture.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.mobile.maestro.fixture.FixtureEmitter

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TapScreen() {
    Box(modifier = Modifier.fillMaxSize()) {
        // Tap target: idiomatic Button with onClick
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

        // Long-press target: idiomatic combinedClickable on a Box (no inner clickable).
        // A Button child would steal the DOWN event before combinedClickable can detect
        // the hold, so we use a plain styled Box instead.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .offset(x = 100.dp, y = 520.dp)
                .size(width = 200.dp, height = 60.dp)
                .semantics { contentDescription = "longpress_target" }
                .background(MaterialTheme.colorScheme.primaryContainer)
                .combinedClickable(
                    onClick = { /* no-op for plain tap */ },
                    onLongClick = {
                        FixtureEmitter.emit(
                            "LONG_PRESS",
                            mapOf(
                                "target" to "longpress_target",
                                "downMs" to 3000
                            )
                        )
                    }
                )
        ) {
            Text(
                text = "longpress",
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}
