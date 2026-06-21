package com.maestrornfixture

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Port of the native/compose conformance fixture's FixtureEmitter — byte-identical logcat contract
 * so the harness's out-of-band oracle parser works unchanged across all three frameworks.
 * Emits MAESTRO_FIXTURE-tagged JSON: {epoch, seq, event, ...payload}.
 */
object FixtureEmitter {
    private const val TAG = "MAESTRO_FIXTURE"
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
        is Map<*, *> -> JSONObject().apply { for ((mk, mv) in v) put(mk.toString(), toJsonValue(mv)) }
        is List<*> -> JSONArray().apply { for (item in v) put(toJsonValue(item)) }
        is Array<*> -> JSONArray().apply { for (item in v) put(toJsonValue(item)) }
        else -> v
    }
}
