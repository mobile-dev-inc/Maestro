package dev.mobile.maestro.maestro_flutter_fixture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MarkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        FixtureEmitter.emit("MARK")
    }
}
