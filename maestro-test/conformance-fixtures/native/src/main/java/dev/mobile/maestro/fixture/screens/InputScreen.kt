package dev.mobile.maestro.fixture.screens

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.widget.EditText
import android.widget.FrameLayout
import dev.mobile.maestro.fixture.FixtureEmitter

object InputScreen {
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
            hint = "Type here..."
            textSize = 18f
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    FixtureEmitter.emit("TEXT_CHANGED", mapOf("text" to (s?.toString() ?: "")))
                }
            })
        }

        root.addView(
            editText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 200; leftMargin = 40; rightMargin = 40 }
        )

        activity.setContentView(root)
    }
}
