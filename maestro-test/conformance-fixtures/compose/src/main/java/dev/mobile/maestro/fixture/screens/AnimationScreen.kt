package dev.mobile.maestro.fixture.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.mobile.maestro.fixture.FixtureEmitter

@Composable
fun AnimationScreen() {
    val alpha = remember { Animatable(0f) }

    // Start a ~1.5s animation on install, mirroring native AnimationScreen
    LaunchedEffect(Unit) {
        FixtureEmitter.emit("ANIM", mapOf("state" to "RUNNING"))
        alpha.animateTo(1f, animationSpec = tween(durationMillis = 1500))
        FixtureEmitter.emit("ANIM", mapOf("state" to "SETTLED"))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Button(
            onClick = { /* animation behavior — no click action needed */ },
            modifier = Modifier
                .offset(x = 100.dp, y = 400.dp)
                .size(width = 130.dp, height = 50.dp)
                .alpha(alpha.value)
                .semantics { contentDescription = "animate_button" }
        ) {
            Text("Animate")
        }
    }
}
