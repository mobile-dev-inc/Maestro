package dev.mobile.maestro.fixture

import android.app.Activity
import dev.mobile.maestro.fixture.screens.TapScreen

object Router {
    fun show(activity: Activity, route: String) {
        when (route) {
            "TapScreen" -> TapScreen.install(activity)
            else -> TapScreen.install(activity) // other screens added in later tasks
        }
    }
}
