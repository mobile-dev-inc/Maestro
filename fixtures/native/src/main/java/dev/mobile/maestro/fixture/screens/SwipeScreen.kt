package dev.mobile.maestro.fixture.screens

import android.app.Activity
import android.graphics.Color
import android.view.MotionEvent
import android.widget.FrameLayout
import dev.mobile.maestro.fixture.FixtureEmitter

object SwipeScreen {
    fun install(activity: Activity) {
        val root = FrameLayout(activity)

        // Emit top-level TOUCH on DOWN from the root (for raw coord contract)
        root.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                FixtureEmitter.emit("TOUCH", mapOf("x" to e.rawX.toInt(), "y" to e.rawY.toInt()))
            }
            false
        }

        // Track down position and time for swipe computation on the surface itself
        var downX = 0f
        var downY = 0f
        var downTime = 0L

        val swipeSurface = FrameLayout(activity).apply {
            contentDescription = "swipe_surface"
            setBackgroundColor(Color.parseColor("#4FC3F7"))

            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        downTime = e.eventTime
                        FixtureEmitter.emit("TOUCH", mapOf("x" to e.rawX.toInt(), "y" to e.rawY.toInt()))
                        true  // consume to receive UP/MOVE
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        val durationMs = e.eventTime - downTime
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            val dir = if (Math.abs(dx) >= Math.abs(dy)) {
                                if (dx > 0) "RIGHT" else "LEFT"
                            } else {
                                if (dy > 0) "DOWN" else "UP"
                            }
                            FixtureEmitter.emit(
                                "SWIPE", mapOf(
                                    "dir" to dir,
                                    "dx" to dx.toInt(),
                                    "dy" to dy.toInt(),
                                    "durationMs" to durationMs,
                                    "target" to "swipe_surface"
                                )
                            )
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        root.addView(
            swipeSurface,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        activity.setContentView(root)
    }
}
