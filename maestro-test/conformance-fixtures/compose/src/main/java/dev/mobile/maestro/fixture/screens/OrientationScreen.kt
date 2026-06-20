package dev.mobile.maestro.fixture.screens

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mobile.maestro.fixture.FixtureEmitter

@Composable
fun OrientationScreen() {
    val configuration = LocalConfiguration.current
    val value = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "LANDSCAPE" else "PORTRAIT"

    // Emit current orientation on first composition
    LaunchedEffect(value) {
        // NOTE: The activity's onConfigurationChanged also emits ORIENTATION.
        // This LaunchedEffect fires on recompose but the harness only checks the
        // onConfigurationChanged-sourced event (from FixtureActivity). We emit here
        // for the initial state on OrientationScreen install.
        FixtureEmitter.emit("ORIENTATION", mapOf("value" to value))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Orientation: $value",
            fontSize = 20.sp,
            color = Color.Black,
            modifier = Modifier
                .offset(x = 100.dp, y = 300.dp)
                .padding(40.dp)
        )
    }
}
