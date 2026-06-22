package dev.mobile.maestro.fixture.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TreeScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp, vertical = 80.dp)
            .semantics { contentDescription = "tree_root" }
    ) {
        Text(
            text = "Label A",
            fontSize = 18.sp,
            modifier = Modifier
                .padding(vertical = 20.dp)
                .semantics { contentDescription = "tree_label_a" }
        )
        Button(
            onClick = { /* no event - tree oracle only */ },
            modifier = Modifier
                .semantics { contentDescription = "tree_button_b" }
        ) {
            Text("Button B")
        }
    }
}
