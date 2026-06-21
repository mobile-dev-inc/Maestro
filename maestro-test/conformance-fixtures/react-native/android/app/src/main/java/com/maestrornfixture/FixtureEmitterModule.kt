package com.maestrornfixture

import android.content.Context
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap

/**
 * Bridges the JS screens to the logcat oracle. Legacy NativeModule — works under New Architecture
 * via the bridgeless interop layer, no codegen needed.
 *
 * `emit` forwards per-screen events (TAP, SWIPE, TEXT_CHANGED, …) to [FixtureEmitter].
 * `seedState` persists the AppLifecycle "seeded" flag (SharedPreferences, like the native fixture)
 * so clearAppState has observable state to wipe.
 */
class FixtureEmitterModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "FixtureEmitter"

    @ReactMethod
    fun emit(type: String, payload: ReadableMap?) {
        @Suppress("UNCHECKED_CAST")
        FixtureEmitter.emit(type, payload?.toHashMap() as? Map<String, Any?> ?: emptyMap())
    }

    @ReactMethod
    fun seedState() {
        reactContext.getSharedPreferences("fixture_state", Context.MODE_PRIVATE)
            .edit().putBoolean("seeded", true).apply()
        FixtureEmitter.emit("STATE", mapOf("seeded" to true))
    }
}
