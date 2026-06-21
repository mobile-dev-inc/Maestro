package dev.mobile.maestro.maestro_flutter_fixture

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * FlutterActivity mirroring the native/compose/RN fixtures' lifecycle contract.
 *
 * Per-screen events (TAP, SWIPE, …) come from Dart over the "maestro.fixture/bridge" MethodChannel
 * and are forwarded to the MAESTRO_FIXTURE logcat oracle via [FixtureEmitter]. Timing-critical
 * lifecycle/deeplink/back/orientation events are emitted natively here so they don't depend on the
 * Dart isolate being ready. Dart pulls the launched `route` via the channel's getRoute.
 */
class MainActivity : FlutterActivity() {

    private val currentRoute: String
        get() = intent.getStringExtra("route") ?: "TapScreen"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "emit" -> {
                    val type = call.argument<String>("event") ?: ""
                    val payload = call.argument<Map<String, Any?>>("payload") ?: emptyMap()
                    FixtureEmitter.emit(type, payload)
                    result.success(null)
                }
                "seedState" -> {
                    getSharedPreferences("fixture_state", Context.MODE_PRIVATE)
                        .edit().putBoolean("seeded", true).apply()
                    FixtureEmitter.emit("STATE", mapOf("seeded" to true))
                    result.success(null)
                }
                "getRoute" -> result.success(currentRoute)
                else -> result.notImplemented()
            }
        }
    }

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

    companion object {
        private const val CHANNEL = "maestro.fixture/bridge"
    }
}
