package dev.mobile.maestro.fixture.screens

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.mobile.maestro.fixture.FixtureEmitter

object ScrollScreen {
    fun install(activity: Activity) {
        val root = FrameLayout(activity)

        val scrollView = ScrollView(activity).apply {
            contentDescription = "scroll_container"
        }

        val inner = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Add tall content to make it scrollable
        for (i in 1..30) {
            inner.addView(TextView(activity).apply {
                text = "Item $i"
                textSize = 18f
                setPadding(40, 40, 40, 40)
                setBackgroundColor(if (i % 2 == 0) Color.parseColor("#E3F2FD") else Color.WHITE)
            })
        }

        scrollView.addView(inner)

        scrollView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (scrollY != oldScrollY) {
                FixtureEmitter.emit(
                    "SCROLL", mapOf(
                        "axis" to "Y",
                        "fromOffset" to oldScrollY,
                        "toOffset" to scrollY
                    )
                )
            }
        }

        root.addView(
            scrollView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        activity.setContentView(root)
    }
}
