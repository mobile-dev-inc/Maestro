package dev.mobile.maestro.fixture

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import dev.mobile.maestro.fixture.screens.AnimationScreen
import dev.mobile.maestro.fixture.screens.AppLifecycleScreen
import dev.mobile.maestro.fixture.screens.InputScreen
import dev.mobile.maestro.fixture.screens.KeyboardScreen
import dev.mobile.maestro.fixture.screens.OrientationScreen
import dev.mobile.maestro.fixture.screens.ScrollScreen
import dev.mobile.maestro.fixture.screens.SwipeScreen
import dev.mobile.maestro.fixture.screens.TapScreen
import dev.mobile.maestro.fixture.screens.TreeScreen

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Router(route: String) {
    // Enable testTag → resource-id mapping once at the root so UiAutomator/Maestro can find
    // elements by resource-id (in addition to content-desc and text). This is the idiomatic
    // way a real Compose team exposes test IDs to the accessibility tree.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true }
    ) {
        when (route) {
            "TapScreen" -> TapScreen()
            "SwipeScreen" -> SwipeScreen()
            "ScrollScreen" -> ScrollScreen()
            "InputScreen" -> InputScreen()
            "KeyboardScreen" -> KeyboardScreen()
            "TreeScreen" -> TreeScreen()
            "OrientationScreen" -> OrientationScreen()
            "AnimationScreen" -> AnimationScreen()
            "AppLifecycleScreen" -> AppLifecycleScreen()
            else -> TapScreen()
        }
    }
}
