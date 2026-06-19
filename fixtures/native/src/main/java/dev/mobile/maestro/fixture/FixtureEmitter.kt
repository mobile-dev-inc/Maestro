package dev.mobile.maestro.fixture

import android.util.Log
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

object FixtureEmitter {
    private const val TAG = "MAESTRO_FIXTURE"
    // Fresh per process start; resets implicitly on cold start / pm clear (seq resets too).
    private val epoch: String = java.lang.Long.toHexString(System.nanoTime())
    private val seq = AtomicInteger(0)

    fun emit(type: String, payload: Map<String, Any?> = emptyMap()) {
        val o = JSONObject()
        o.put("epoch", epoch)
        o.put("seq", seq.incrementAndGet())
        o.put("event", type)
        for ((k, v) in payload) o.put(k, v)
        Log.d(TAG, o.toString())
    }

    fun currentEpoch() = epoch
}
