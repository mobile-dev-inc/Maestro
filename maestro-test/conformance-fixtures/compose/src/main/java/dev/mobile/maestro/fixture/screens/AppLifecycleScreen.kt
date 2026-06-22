package dev.mobile.maestro.fixture.screens

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.mobile.maestro.fixture.FixtureEmitter

@Composable
fun AppLifecycleScreen() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("fixture_state", Context.MODE_PRIVATE)

    // Read initial seeded state and emit STATE on composition
    LaunchedEffect(Unit) {
        val seeded = prefs.getBoolean("seeded", false)
        FixtureEmitter.emit("STATE", mapOf("seeded" to seeded))

        // Emit DEEPLINK if launched via VIEW intent with data
        (context as? androidx.activity.ComponentActivity)?.intent?.dataString?.let { data ->
            FixtureEmitter.emit("DEEPLINK", mapOf("data" to data))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Button(
            onClick = {
                prefs.edit().putBoolean("seeded", true).apply()
                FixtureEmitter.emit("STATE", mapOf("seeded" to true))
            },
            modifier = Modifier
                .offset(x = 100.dp, y = 400.dp)
                .size(width = 160.dp, height = 50.dp)
                .semantics { contentDescription = "state_seed_button" }
        ) {
            Text("Seed State")
        }
    }
}
