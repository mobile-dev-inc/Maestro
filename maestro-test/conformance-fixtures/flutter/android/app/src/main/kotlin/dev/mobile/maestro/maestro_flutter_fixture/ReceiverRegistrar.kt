package dev.mobile.maestro.maestro_flutter_fixture

import android.app.Activity
import android.content.Context
import android.content.IntentFilter
import android.os.Build

/** API-26+ receiver registration isolated in its own class (ART verification safety on API < 26). */
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
