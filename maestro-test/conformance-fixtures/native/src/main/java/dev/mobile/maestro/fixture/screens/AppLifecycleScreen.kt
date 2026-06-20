package dev.mobile.maestro.fixture.screens

import android.app.Activity
import android.content.Context
import android.view.MotionEvent
import android.widget.Button
import android.widget.FrameLayout
import dev.mobile.maestro.fixture.FixtureEmitter

object AppLifecycleScreen {
    private const val PREFS_NAME = "fixture_state"
    private const val KEY_SEEDED = "seeded"

    fun install(activity: Activity) {
        val root = FrameLayout(activity)

        root.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                FixtureEmitter.emit("TOUCH", mapOf("x" to e.rawX.toInt(), "y" to e.rawY.toInt()))
            }
            false
        }

        // Read initial seeded state from SharedPreferences
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val seeded = prefs.getBoolean(KEY_SEEDED, false)
        FixtureEmitter.emit("STATE", mapOf("seeded" to seeded))

        val seedButton = Button(activity).apply {
            text = "Seed State"
            contentDescription = "state_seed_button"
            setOnClickListener {
                prefs.edit().putBoolean(KEY_SEEDED, true).apply()
                FixtureEmitter.emit("STATE", mapOf("seeded" to true))
            }
        }

        root.addView(
            seedButton,
            FrameLayout.LayoutParams(500, 150).apply { topMargin = 400; leftMargin = 100 }
        )

        activity.setContentView(root)

        // Emit DEEPLINK if launched via VIEW intent with data
        activity.intent?.dataString?.let { data ->
            FixtureEmitter.emit("DEEPLINK", mapOf("data" to data))
        }
    }
}
