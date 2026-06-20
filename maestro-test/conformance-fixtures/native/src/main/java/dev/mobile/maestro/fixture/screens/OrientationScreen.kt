package dev.mobile.maestro.fixture.screens

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import dev.mobile.maestro.fixture.FixtureEmitter

object OrientationScreen {
    fun install(activity: Activity) {
        val root = FrameLayout(activity)

        root.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                FixtureEmitter.emit("TOUCH", mapOf("x" to e.rawX.toInt(), "y" to e.rawY.toInt()))
            }
            false
        }

        val label = TextView(activity).apply {
            textSize = 20f
            setTextColor(Color.BLACK)
            setPadding(40, 40, 40, 40)
        }

        root.addView(
            label,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 300; leftMargin = 100 }
        )

        // Emit current orientation on install
        val currentOrientation = orientationValue(activity.resources.configuration.orientation)
        label.text = "Orientation: $currentOrientation"
        FixtureEmitter.emit("ORIENTATION", mapOf("value" to currentOrientation))

        activity.setContentView(root)
    }

    fun orientationValue(orientation: Int): String {
        return when (orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "LANDSCAPE"
            else -> "PORTRAIT"
        }
    }
}
