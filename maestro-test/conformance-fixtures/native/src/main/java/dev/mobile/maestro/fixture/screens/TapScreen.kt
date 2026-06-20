package dev.mobile.maestro.fixture.screens

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import dev.mobile.maestro.fixture.FixtureEmitter

object TapScreen {
    fun install(activity: Activity) {
        val root = FrameLayout(activity)

        // Top-level raw-coordinate reporter (independent of which widget handles the hit).
        root.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                FixtureEmitter.emit("TOUCH", mapOf("x" to e.rawX.toInt(), "y" to e.rawY.toInt()))
            }
            false
        }

        val tap = Button(activity).apply {
            text = "tap"
            contentDescription = "tap_target"
            setOnClickListener {
                FixtureEmitter.emit("TAP", mapOf("target" to "tap_target"))
            }
        }
        val longPress = Button(activity).apply {
            text = "longpress"
            contentDescription = "longpress_target"
            var downAt = 0L
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { downAt = System.currentTimeMillis(); false }
                    MotionEvent.ACTION_UP -> {
                        val downMs = System.currentTimeMillis() - downAt
                        if (downMs >= 500) FixtureEmitter.emit(
                            "LONG_PRESS", mapOf("target" to "longpress_target", "downMs" to downMs)
                        )
                        false
                    }
                    else -> false
                }
            }
        }

        root.addView(tap, FrameLayout.LayoutParams(600, 200).apply { topMargin = 400; leftMargin = 100 })
        root.addView(longPress, FrameLayout.LayoutParams(600, 200).apply { topMargin = 800; leftMargin = 100 })
        activity.setContentView(root)
    }
}
