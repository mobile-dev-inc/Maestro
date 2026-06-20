package dev.mobile.maestro.fixture.screens

import android.graphics.Rect
import android.os.Build
import android.view.KeyEvent
import android.view.WindowInsets
import android.widget.EditText
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.mobile.maestro.fixture.FixtureEmitter

@Composable
fun KeyboardScreen() {
    val view = LocalView.current

    // IME visibility detection via ViewTreeObserver (works API 24+)
    DisposableEffect(view) {
        var lastImeState = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val listener = android.view.View.OnApplyWindowInsetsListener { v, insets ->
                val imeVisible = insets.isVisible(WindowInsets.Type.ime())
                if (imeVisible != lastImeState) {
                    lastImeState = imeVisible
                    FixtureEmitter.emit("IME", mapOf("state" to if (imeVisible) "SHOWN" else "HIDDEN"))
                }
                v.onApplyWindowInsets(insets)
            }
            view.setOnApplyWindowInsetsListener(listener)
            view.requestApplyInsets()
            onDispose { view.setOnApplyWindowInsetsListener(null) }
        } else {
            val layoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
                val rect = Rect()
                view.getWindowVisibleDisplayFrame(rect)
                val screenHeight = view.rootView.height
                val keypadHeight = screenHeight - rect.bottom
                val imeVisible = keypadHeight > screenHeight * 0.15
                if (imeVisible != lastImeState) {
                    lastImeState = imeVisible
                    FixtureEmitter.emit("IME", mapOf("state" to if (imeVisible) "SHOWN" else "HIDDEN"))
                }
            }
            view.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener) }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Use AndroidView with a real EditText so that hardware key events from
        // UiAutomator (pressKey) are delivered via setOnKeyListener — same as native fixture.
        AndroidView(
            factory = { ctx ->
                EditText(ctx).apply {
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
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp)
                .padding(top = 200.dp)
        )
    }
}
