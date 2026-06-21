package dev.mobile.maestro.fixture.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Reproduces mobile-dev-inc/Maestro#2704 — "Android Compose `mergeDescendants`".
 *
 * `Modifier.semantics(mergeDescendants = true)` tells Compose to fold the children's semantics
 * (here the two Texts) into this Column's accessibility node — the idiomatic way a real Compose
 * team groups content for screen readers (TalkBack announces "Line 1, Line 2"). `MergeDescendants-
 * Behavior` asserts a single node in the driver's hierarchy carries both child texts; today that
 * fails because the merged node surfaces empty text, which is exactly the bug #2704 reports.
 *
 * Idiomatic, no cheats: this is the documented Compose accessibility-merging API
 * (https://developer.android.com/develop/ui/compose/accessibility/merging-clearing).
 */
@Composable
fun MergeScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(40.dp)
            .semantics(mergeDescendants = true) {}
    ) {
        Text("Line 1")
        Text("Line 2")
    }
}
