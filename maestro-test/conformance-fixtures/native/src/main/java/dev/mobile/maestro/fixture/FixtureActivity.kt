package dev.mobile.maestro.fixture

import android.app.Activity
import android.content.Context
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.widget.FrameLayout

class FixtureActivity : Activity() {
    private var currentRoute: String = "TapScreen"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this).apply { id = android.R.id.content })

        FixtureEmitter.emit("SELFTEST")

        val args = HashMap<String, Any?>()
        intent.extras?.keySet()?.forEach { k -> args[k] = intent.extras?.get(k)?.toString() }
        // Deep-link data (openLink) is echoed too.
        intent.dataString?.let { args["data"] = it }
        FixtureEmitter.emit("LIFECYCLE", mapOf("state" to "LAUNCHED", "args" to args))

        // Emit DEEPLINK unconditionally so OpenLinkBehavior can detect it regardless of route.
        intent.dataString?.let { data ->
            FixtureEmitter.emit("DEEPLINK", mapOf("data" to data))
        }

        // registerReceiver(receiver, filter, flags) requires API 26; use the 2-arg form on older devices.
        // The API-26 branch is isolated in a separate class (ReceiverRegistrar) to prevent ART
        // class verification from failing on API 24. ART verifies all bytecode at class-load time —
        // including dead branches — so the 3-arg call must live in a class that is never loaded on API < 26.
        if (Build.VERSION.SDK_INT >= 26) {
            ReceiverRegistrar.register(this)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(MarkReceiver(), IntentFilter("dev.mobile.maestro.fixture.MARK"))
        }

        currentRoute = intent.getStringExtra("route") ?: "TapScreen"
        Router.show(this, currentRoute)
    }

    override fun onBackPressed() {
        if (currentRoute == "AppLifecycleScreen") FixtureEmitter.emit("BACK")
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
