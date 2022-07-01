package dev.mobile.sample

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import kotlin.concurrent.thread

class DetailsViewModel : ViewModel() {

    var state by mutableStateOf(State())
        private set

    fun loadData() {
        state = state.copy(
            loading = true
        )
        thread {
            Thread.sleep(1000)
            state = state.copy(
                loading = false,
                content = "Here is the detailed content",
            )
        }
    }

    data class State(
        val loading: Boolean = false,
        val content: String? = null
    )

}