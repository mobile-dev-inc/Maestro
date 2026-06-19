package dev.mobile.maestro.fixture.screens

import android.app.Activity
import android.graphics.Color
import android.view.MotionEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.mobile.maestro.fixture.FixtureEmitter

object TreeScreen {
    fun install(activity: Activity) {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            contentDescription = "tree_root"
            setPadding(40, 80, 40, 40)
        }

        val labelA = TextView(activity).apply {
            text = "Label A"
            contentDescription = "tree_label_a"
            textSize = 18f
            setPadding(0, 20, 0, 20)
        }

        val buttonB = Button(activity).apply {
            text = "Button B"
            contentDescription = "tree_button_b"
        }

        root.addView(labelA)
        root.addView(buttonB)

        activity.setContentView(root)
    }
}
