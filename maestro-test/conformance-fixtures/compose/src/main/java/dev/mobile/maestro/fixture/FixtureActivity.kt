package dev.mobile.maestro.fixture

import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class FixtureActivity : ComponentActivity() {
    private var currentRoute: String = "TapScreen"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FixtureEmitter.emit("SELFTEST")

        val args = HashMap<String, Any?>()
        intent.extras?.keySet()?.forEach { k -> args[k] = intent.extras?.get(k)?.toString() }
        intent.dataString?.let { args["data"] = it }
        FixtureEmitter.emit("LIFECYCLE", mapOf("state" to "LAUNCHED", "args" to args))

        // Emit DEEPLINK unconditionally so OpenLinkBehavior can detect it regardless of route.
        intent.dataString?.let { data ->
            FixtureEmitter.emit("DEEPLINK", mapOf("data" to data))
        }

        if (Build.VERSION.SDK_INT >= 26) {
            ReceiverRegistrar.register(this)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(MarkReceiver(), IntentFilter("dev.mobile.maestro.fixture.MARK"))
        }

        currentRoute = intent.getStringExtra("route") ?: "TapScreen"

        setContent {
            Router(route = currentRoute)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentRoute == "AppLifecycleScreen") FixtureEmitter.emit("BACK")
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (currentRoute == "OrientationScreen") {
            val value = if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) "LANDSCAPE" else "PORTRAIT"
            FixtureEmitter.emit("ORIENTATION", mapOf("value" to value))
        }
    }
}
