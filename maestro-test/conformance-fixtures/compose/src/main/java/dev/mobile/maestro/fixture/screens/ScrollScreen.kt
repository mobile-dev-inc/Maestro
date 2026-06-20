package dev.mobile.maestro.fixture.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mobile.maestro.fixture.FixtureEmitter

@Composable
fun ScrollScreen() {
    val listState = rememberLazyListState()

    LaunchedEffect(listState) {
        var prevOffset = 0
        snapshotFlow {
            val firstVisible = listState.firstVisibleItemIndex
            val itemOffset = listState.firstVisibleItemScrollOffset
            firstVisible * 1000 + itemOffset // proxy for scroll position
        }.collect { currentOffset ->
            if (currentOffset != prevOffset) {
                FixtureEmitter.emit(
                    "SCROLL",
                    mapOf(
                        "axis" to "Y",
                        "fromOffset" to prevOffset,
                        "toOffset" to currentOffset
                    )
                )
                prevOffset = currentOffset
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = "scroll_container" }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            items(30) { i ->
                Text(
                    text = "Item ${i + 1}",
                    fontSize = 18.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if ((i + 1) % 2 == 0) Color(0xFFE3F2FD) else Color.White)
                        .padding(horizontal = 40.dp, vertical = 40.dp)
                )
            }
        }
    }
}
