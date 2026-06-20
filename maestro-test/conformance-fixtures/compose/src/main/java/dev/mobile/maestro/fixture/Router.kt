package dev.mobile.maestro.fixture

import androidx.compose.runtime.Composable
import dev.mobile.maestro.fixture.screens.AnimationScreen
import dev.mobile.maestro.fixture.screens.AppLifecycleScreen
import dev.mobile.maestro.fixture.screens.InputScreen
import dev.mobile.maestro.fixture.screens.KeyboardScreen
import dev.mobile.maestro.fixture.screens.OrientationScreen
import dev.mobile.maestro.fixture.screens.ScrollScreen
import dev.mobile.maestro.fixture.screens.SwipeScreen
import dev.mobile.maestro.fixture.screens.TapScreen
import dev.mobile.maestro.fixture.screens.TreeScreen

@Composable
fun Router(route: String) {
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
