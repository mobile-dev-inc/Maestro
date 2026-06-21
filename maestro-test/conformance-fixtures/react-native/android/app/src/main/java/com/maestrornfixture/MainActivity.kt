package com.maestrornfixture

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

/**
 * ReactActivity subclass mirroring the native/compose fixtures' FixtureActivity: it emits the
 * timing-critical lifecycle/deeplink/back/orientation events natively (so they don't depend on the
 * JS bridge being ready) and passes the `route` intent extra to JS as an initial prop, which the
 * JS Router uses to render the matching screen.
 */
class MainActivity : ReactActivity() {

    override fun getMainComponentName(): String = "MaestroRnFixture"

    private val currentRoute: String
        get() = intent.getStringExtra("route") ?: "TapScreen"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        emitLaunch(intent)

        if (Build.VERSION.SDK_INT >= 26) {
            ReceiverRegistrar.register(this)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(MarkReceiver(), IntentFilter("dev.mobile.maestro.fixture.MARK"))
        }
    }

    /**
     * The activity is `singleTask`, so a re-launch of an already-running fixture is delivered via
     * onNewIntent (not onCreate). Emit the launch events here too — idiomatic RN deep-link handling
     * — so launchApp/openLink observe LAUNCHED/DEEPLINK regardless of whether the process was alive.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        emitLaunch(intent)
    }

    private fun emitLaunch(launchIntent: Intent) {
        FixtureEmitter.emit("SELFTEST")

        val args = HashMap<String, Any?>()
        launchIntent.extras?.keySet()?.forEach { k -> args[k] = launchIntent.extras?.get(k)?.toString() }
        launchIntent.dataString?.let { args["data"] = it }
        FixtureEmitter.emit("LIFECYCLE", mapOf("state" to "LAUNCHED", "args" to args))

        launchIntent.dataString?.let { data -> FixtureEmitter.emit("DEEPLINK", mapOf("data" to data)) }

        // AppLifecycle state is persisted natively (SharedPreferences) so clearAppState can wipe it.
        if ((launchIntent.getStringExtra("route") ?: "TapScreen") == "AppLifecycleScreen") {
            val seeded = getSharedPreferences("fixture_state", Context.MODE_PRIVATE)
                .getBoolean("seeded", false)
            FixtureEmitter.emit("STATE", mapOf("seeded" to seeded))
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (currentRoute == "AppLifecycleScreen") FixtureEmitter.emit("BACK")
        super.onBackPressed()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (currentRoute == "OrientationScreen") {
            val value = if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
                "LANDSCAPE" else "PORTRAIT"
            FixtureEmitter.emit("ORIENTATION", mapOf("value" to value))
        }
    }

    override fun createReactActivityDelegate(): ReactActivityDelegate =
        object : DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled) {
            override fun getLaunchOptions(): Bundle =
                Bundle().apply {
                    putString("route", currentRoute)
                    intent.dataString?.let { putString("data", it) }
                }
        }
}
