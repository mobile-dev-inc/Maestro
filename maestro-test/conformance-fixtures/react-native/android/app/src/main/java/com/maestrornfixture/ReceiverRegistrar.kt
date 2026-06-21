package com.maestrornfixture

import android.app.Activity
import android.content.Context
import android.content.IntentFilter
import android.os.Build

/**
 * Isolated helper for API-26+ receiver registration (the 3-arg registerReceiver). Kept in its own
 * class so ART never loads it on API < 26 — same rationale as the native fixture.
 */
object ReceiverRegistrar {
    fun register(activity: Activity) {
        val flags = if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0
        activity.registerReceiver(
            MarkReceiver(),
            IntentFilter("dev.mobile.maestro.fixture.MARK"),
            flags,
        )
    }
}
