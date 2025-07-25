package dev.mobile.maestro

import android.app.UiAutomation
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlin.reflect.jvm.jvmName

object ToastAccessibilityListener : UiAutomation.OnAccessibilityEventListener {

    private var toastNode: AccessibilityNodeInfo? = null
    private var isListening = false
    private var recentToastTimeMillis: Long = 0

    private const val TOAST_LENGTH_LONG_DURATION = 3500

    override fun onAccessibilityEvent(accessibilityEvent: AccessibilityEvent) {
        if (
            accessibilityEvent.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
            accessibilityEvent.className.toString().contains(Toast::class.jvmName)
        ) {
            recentToastTimeMillis = System.currentTimeMillis()
            // Constructor for AccessibilityNodeInfo is only available on Android API 30+
            val nodeInfo = if (Build.VERSION.SDK_INT < 30) {
                AccessibilityNodeInfo.obtain()
            } else {
                AccessibilityNodeInfo()
            }
            toastNode = nodeInfo.apply {
                text = accessibilityEvent.text.first().toString()
                className = Toast::class.jvmName
                isVisibleToUser = true
                viewIdResourceName = ""
                packageName = ""
                isCheckable = false
                isChecked = accessibilityEvent.isChecked
                isClickable = false
                isEnabled = accessibilityEvent.isEnabled
                Log.d("Maestro", "Toast received with $text")
            }
        }
    }

    fun getToastAccessibilityNode() = toastNode

    fun isTimedOut(): Boolean {
        return System.currentTimeMillis() - recentToastTimeMillis > TOAST_LENGTH_LONG_DURATION
    }

    fun start(uiAutomation: UiAutomation): ToastAccessibilityListener {
        if (isListening) return this
        uiAutomation.setOnAccessibilityEventListener(this)
        isListening = true
        Log.d("Maestro", "Started listening to accessibility events")
        return this
    }

    fun stop() {
        isListening = false
        Log.d("Maestro", "Stopped listening to accessibility events")
    }
}