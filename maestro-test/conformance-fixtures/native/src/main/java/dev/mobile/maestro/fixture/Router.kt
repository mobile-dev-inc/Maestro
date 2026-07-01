package dev.mobile.maestro.fixture

import android.app.Activity
import dev.mobile.maestro.fixture.screens.AnimationScreen
import dev.mobile.maestro.fixture.screens.AppLifecycleScreen
import dev.mobile.maestro.fixture.screens.InputScreen
import dev.mobile.maestro.fixture.screens.KeyboardScreen
import dev.mobile.maestro.fixture.screens.OrientationScreen
import dev.mobile.maestro.fixture.screens.ScrollScreen
import dev.mobile.maestro.fixture.screens.SwipeScreen
import dev.mobile.maestro.fixture.screens.TapScreen
import dev.mobile.maestro.fixture.screens.TreeScreen

object Router {
    fun show(activity: Activity, route: String) {
        when (route) {
            "TapScreen" -> TapScreen.install(activity)
            "SwipeScreen" -> SwipeScreen.install(activity)
            "ScrollScreen" -> ScrollScreen.install(activity)
            "InputScreen" -> InputScreen.install(activity)
            "KeyboardScreen" -> KeyboardScreen.install(activity)
            "TreeScreen" -> TreeScreen.install(activity)
            "OrientationScreen" -> OrientationScreen.install(activity)
            "AnimationScreen" -> AnimationScreen.install(activity)
            "AppLifecycleScreen" -> AppLifecycleScreen.install(activity)
            else -> TapScreen.install(activity)
        }
    }
}
