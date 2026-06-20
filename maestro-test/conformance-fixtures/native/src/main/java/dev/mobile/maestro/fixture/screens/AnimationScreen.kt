package dev.mobile.maestro.fixture.screens

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.view.MotionEvent
import android.widget.Button
import android.widget.FrameLayout
import dev.mobile.maestro.fixture.FixtureEmitter

object AnimationScreen {
    fun install(activity: Activity) {
        val root = FrameLayout(activity)

        root.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                FixtureEmitter.emit("TOUCH", mapOf("x" to e.rawX.toInt(), "y" to e.rawY.toInt()))
            }
            false
        }

        val animateButton = Button(activity).apply {
            text = "Animate"
            contentDescription = "animate_button"
        }

        root.addView(
            animateButton,
            FrameLayout.LayoutParams(400, 150).apply { topMargin = 400; leftMargin = 100 }
        )

        activity.setContentView(root)

        // Start a ~1.5s ValueAnimator on install
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500L
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    FixtureEmitter.emit("ANIM", mapOf("state" to "RUNNING"))
                }

                override fun onAnimationEnd(animation: Animator) {
                    FixtureEmitter.emit("ANIM", mapOf("state" to "SETTLED"))
                }
            })
            addUpdateListener { anim ->
                animateButton.alpha = anim.animatedValue as Float
            }
        }
        animator.start()
    }
}
