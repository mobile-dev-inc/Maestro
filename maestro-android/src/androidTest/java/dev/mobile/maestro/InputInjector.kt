package dev.mobile.maestro

import android.app.UiAutomation
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiDeviceExt.clickExt
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Injects taps and key presses without waiting for window animations.
 *
 * UiAutomator injects through `UiAutomation.injectInputEvent(event, sync)`, which makes the window
 * manager wait for every window animation to complete: up to 5s before a DOWN and 5s after an UP.
 * Android can leave an animation running forever (a `starting_reveal` left behind when an app is
 * slow to take over its splash screen), and then every tap and key press costs 10s, so `inputText`
 * exceeds the driver's deadline.
 *
 * The three-argument overload `injectInputEvent(event, sync, waitForAnimations)` lets the caller
 * skip that wait, as a finger or `adb shell input` never waits either. It is a `@TestApi` available
 * from API 31; on older versions, or if the lookup is refused, UiAutomator is used unchanged.
 */
internal class InputInjector(
    private val uiDevice: UiDevice,
    private val uiAutomation: UiAutomation,
) {

    // The three-argument overload, hidden from the SDK so it is reached by reflection.
    // Null when the OS does not have it (before API 31), and input then goes through UiAutomator.
    private val injectInputEventMethod: Method? = runCatching {
        UiAutomation::class.java.getMethod(
            "injectInputEvent",
            InputEvent::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
    }.onFailure {
        Log.i(TAG, "injectInputEvent(event, sync, waitForAnimations) unavailable, using UiAutomator: $it")
    }.getOrNull()

    fun pressKeyCode(keyCode: Int, metaState: Int = 0): Boolean {
        val method = injectInputEventMethod ?: return uiDevice.pressKeyCode(keyCode, metaState)

        // Same events UiAutomator's InteractionController.sendKey builds.
        val eventTime = SystemClock.uptimeMillis()
        return send(method, keyEvent(eventTime, KeyEvent.ACTION_DOWN, keyCode, metaState)) &&
            send(method, keyEvent(eventTime, KeyEvent.ACTION_UP, keyCode, metaState))
    }

    fun click(x: Int, y: Int): Boolean {
        val method = injectInputEventMethod ?: return uiDevice.clickExt(x, y)

        // Same sequence UiAutomator's InteractionController.clickNoSync performs.
        val downTime = SystemClock.uptimeMillis()
        if (!sendTouch(method, downTime, downTime, MotionEvent.ACTION_DOWN, x, y)) return false
        SystemClock.sleep(CLICK_LENGTH_MS)
        return sendTouch(method, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)
    }

    private fun sendTouch(method: Method, downTime: Long, eventTime: Long, action: Int, x: Int, y: Int): Boolean {
        val event = touchEvent(downTime, eventTime, action, x, y)
        try {
            return send(method, event)
        } finally {
            event.recycle()
        }
    }

    private fun send(method: Method, event: InputEvent): Boolean {
        try {
            return method.invoke(uiAutomation, event, /* sync = */ true, /* waitForAnimations = */ false) as Boolean
        } catch (e: InvocationTargetException) {
            // Surface the real failure (for example a SecurityException) the way a direct call would.
            throw e.cause ?: e
        }
    }

    private fun keyEvent(eventTime: Long, action: Int, keyCode: Int, metaState: Int): KeyEvent {
        return KeyEvent(
            eventTime, eventTime, action, keyCode, 0, metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
        )
    }

    private fun touchEvent(downTime: Long, eventTime: Long, action: Int, x: Int, y: Int): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            pressure = 1f
            size = 1f
            this.x = x.toFloat()
            this.y = y.toFloat()
        }
        return MotionEvent.obtain(
            downTime, eventTime, action, 1, arrayOf(properties), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
    }

    private companion object {
        private const val TAG = "Maestro"
        private const val CLICK_LENGTH_MS = 100L
    }
}
