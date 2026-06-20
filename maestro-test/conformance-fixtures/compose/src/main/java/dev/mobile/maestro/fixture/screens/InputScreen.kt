package dev.mobile.maestro.fixture.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.mobile.maestro.fixture.FixtureEmitter

@Composable
fun InputScreen() {
    var text by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = text,
            onValueChange = { newText ->
                text = newText
                FixtureEmitter.emit("TEXT_CHANGED", mapOf("text" to newText))
            },
            placeholder = { Text("Type here...") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp)
                .padding(top = 200.dp)
                .semantics { contentDescription = "text_field" }
        )
    }
}
