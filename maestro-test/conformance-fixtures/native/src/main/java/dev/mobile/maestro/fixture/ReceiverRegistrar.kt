package dev.mobile.maestro.fixture

import android.app.Activity
import android.content.Context
import android.content.IntentFilter
import android.os.Build

/**
 * Isolated helper for API-26+ receiver registration.
 *
 * ART on API 24 verifies ALL bytecode in a class at class-load time, including dead branches.
 * The 3-argument registerReceiver(BroadcastReceiver, IntentFilter, int) does not exist on API 24,
 * so any class that references it — even inside an unreachable `if (SDK >= 26)` block — causes
 * a NoSuchMethodError at class load.  Putting the call in THIS separate class means the class
 * loader never loads ReceiverRegistrar on API < 26, sidestepping the verification failure.
 */
object ReceiverRegistrar {
    fun register(activity: Activity) {
        // Called only on API 26+; the 3-arg form is safe here.
        val flags = if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0
        activity.registerReceiver(
            MarkReceiver(),
            IntentFilter("dev.mobile.maestro.fixture.MARK"),
            flags,
        )
    }
}
