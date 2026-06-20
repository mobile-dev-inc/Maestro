package dev.mobile.maestro.fixture.screens

import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import dev.mobile.maestro.fixture.FixtureEmitter

object KeyboardScreen {
    fun install(activity: Activity) {
        val root = FrameLayout(activity)

        root.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                FixtureEmitter.emit("TOUCH", mapOf("x" to e.rawX.toInt(), "y" to e.rawY.toInt()))
            }
            false
        }

        val editText = EditText(activity).apply {
            contentDescription = "text_field"
            hint = "Focus me to show keyboard..."
            textSize = 18f

            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val name = when (keyCode) {
                        KeyEvent.KEYCODE_ENTER -> "ENTER"
                        KeyEvent.KEYCODE_DEL -> "DEL"
                        KeyEvent.KEYCODE_BACK -> "BACK"
                        KeyEvent.KEYCODE_TAB -> "TAB"
                        KeyEvent.KEYCODE_SPACE -> "SPACE"
                        else -> keyCode.toString()
                    }
                    FixtureEmitter.emit("KEY", mapOf("code" to name))
                }
                false
            }
        }

        root.addView(
            editText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 200; leftMargin = 40; rightMargin = 40 }
        )

        activity.setContentView(root)

        // IME visibility detection
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+: attach to decorView so insets are always dispatched
            val decorView = activity.window.decorView
            decorView.setOnApplyWindowInsetsListener { view, insets ->
                val imeVisible = insets.isVisible(WindowInsets.Type.ime())
                FixtureEmitter.emit("IME", mapOf("state" to if (imeVisible) "SHOWN" else "HIDDEN"))
                view.onApplyWindowInsets(insets)
            }
            root.post { decorView.requestApplyInsets() }
        } else {
            // Fallback for API 24-29: compare visible rect height to screen height
            var lastImeState = false
            root.viewTreeObserver.addOnGlobalLayoutListener {
                val rect = Rect()
                root.getWindowVisibleDisplayFrame(rect)
                val screenHeight = root.rootView.height
                val keypadHeight = screenHeight - rect.bottom
                val imeVisible = keypadHeight > screenHeight * 0.15
                if (imeVisible != lastImeState) {
                    lastImeState = imeVisible
                    FixtureEmitter.emit("IME", mapOf("state" to if (imeVisible) "SHOWN" else "HIDDEN"))
                }
            }
        }
    }
}
