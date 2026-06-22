package dev.mobile.maestro.fixture.screens

import android.graphics.Rect
import android.os.Build
import android.view.KeyEvent
import android.view.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.mobile.maestro.fixture.FixtureEmitter

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun KeyboardScreen() {
    val view = LocalView.current
    var text by remember { mutableStateOf("") }

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
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Focus me to show keyboard...") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    FixtureEmitter.emit("KEY", mapOf("code" to "ENTER"))
                }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp)
                .padding(top = 200.dp)
                .semantics { contentDescription = "text_field" }
                .onKeyEvent { keyEvent ->
                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                        val code = when (keyEvent.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_ENTER -> "ENTER"
                            KeyEvent.KEYCODE_DEL -> "DEL"
                            KeyEvent.KEYCODE_BACK -> "BACK"
                            KeyEvent.KEYCODE_TAB -> "TAB"
                            KeyEvent.KEYCODE_SPACE -> "SPACE"
                            else -> keyEvent.nativeKeyEvent.keyCode.toString()
                        }
                        FixtureEmitter.emit("KEY", mapOf("code" to code))
                    }
                    false
                }
        )
    }
}
