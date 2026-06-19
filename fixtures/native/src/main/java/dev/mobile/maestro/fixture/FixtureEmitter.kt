package dev.mobile.maestro.fixture

import android.util.Log
import org.json.JSONArray
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
        for ((k, v) in payload) o.put(k, toJsonValue(v))
        Log.d(TAG, o.toString())
    }

    fun currentEpoch() = epoch

    private fun toJsonValue(v: Any?): Any? = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> {
            val obj = JSONObject()
            for ((mk, mv) in v) obj.put(mk.toString(), toJsonValue(mv))
            obj
        }
        is List<*> -> {
            val arr = JSONArray()
            for (item in v) arr.put(toJsonValue(item))
            arr
        }
        is Array<*> -> {
            val arr = JSONArray()
            for (item in v) arr.put(toJsonValue(item))
            arr
        }
        else -> v
    }
}
