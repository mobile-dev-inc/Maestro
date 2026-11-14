package maestro.cli.runner.resultview

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TeeResultViewTest {

    private class RecordingView(private val log: MutableList<String>, private val name: String) : ResultView {
        override fun setState(state: UiState) {
            log.add("$name: ${(state as UiState.Error).message}")
        }
    }

    @Test
    fun `passes every state to each view in order`() {
        // Given
        val log = mutableListOf<String>()
        val view = TeeResultView(RecordingView(log, "first"), RecordingView(log, "second"))

        // When
        view.setState(UiState.Error("one"))
        view.setState(UiState.Error("two"))

        // Then
        assertThat(log).containsExactly("first: one", "second: one", "first: two", "second: two").inOrder()
    }
}
