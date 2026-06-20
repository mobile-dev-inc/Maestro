package dev.mobile.maestro.fixture.screens

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
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

        // A grid pattern that visibly drags with the gesture. This gives screen recordings real
        // on-screen motion to capture — without it, a swipe on a static surface produces a
        // zero-frame (unplayable) clip. The SWIPE event below is unaffected.
        val content = object : View(activity) {
            private val line = Paint().apply {
                color = Color.parseColor("#01579B"); strokeWidth = 6f; isAntiAlias = false
            }
            private val bg = Paint().apply { color = Color.parseColor("#4FC3F7") }
            override fun onDraw(canvas: Canvas) {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
                val step = 140
                var x = 0
                while (x <= width) { canvas.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), line); x += step }
                var y = 0
                while (y <= height) { canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), line); y += step }
            }
        }

        var downX = 0f
        var downY = 0f
        var downTime = 0L

        val swipeSurface = FrameLayout(activity).apply {
            contentDescription = "swipe_surface"
            setBackgroundColor(Color.parseColor("#0288D1"))

            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        downTime = e.eventTime
                        content.translationX = 0f
                        content.translationY = 0f
                        FixtureEmitter.emit("TOUCH", mapOf("x" to e.rawX.toInt(), "y" to e.rawY.toInt()))
                        true  // consume to receive UP/MOVE
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // Drag the grid with the finger (clamped) → visible motion for the recording.
                        content.translationX = (e.rawX - downX).coerceIn(-400f, 400f)
                        content.translationY = (e.rawY - downY).coerceIn(-400f, 400f)
                        true
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
                        // Settle the grid back with a short animation (more frames to capture).
                        content.animate().translationX(0f).translationY(0f).setDuration(250).start()
                        true
                    }
                    else -> false
                }
            }
        }

        swipeSurface.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

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
