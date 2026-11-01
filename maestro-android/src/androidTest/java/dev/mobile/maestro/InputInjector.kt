package dev.mobile.maestro

import android.app.UiAutomation
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.test.uiautomator.UiDevice
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Presses keys without waiting for window animations. UiAutomator's injection waits up to 5s
 * before and after each key for animations to end, and an animation Android leaves running (a
 * `starting_reveal` after a slow splash handoff) makes `inputText` exceed the driver's deadline.
 * Uses the hidden `injectInputEvent(event, sync, waitForAnimations)` from API 31, else UiAutomator.
 */
internal class InputInjector(
    private val uiDevice: UiDevice,
    private val uiAutomation: UiAutomation,
) {

    // Null before API 31.
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

    private companion object {
        private const val TAG = "Maestro"
    }
}
