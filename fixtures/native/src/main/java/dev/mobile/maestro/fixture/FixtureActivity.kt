package dev.mobile.maestro.fixture

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
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

        registerReceiver(MarkReceiver(), android.content.IntentFilter("dev.mobile.maestro.fixture.MARK"),
            // API 33+ requires an export flag
            if (android.os.Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0)

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
