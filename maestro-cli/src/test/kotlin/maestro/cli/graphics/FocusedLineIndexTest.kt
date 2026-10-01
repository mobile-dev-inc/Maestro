package maestro.cli.graphics

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FocusedLineIndexTest {

    @Test
    fun `focuses the first pending line in ansi output`() {
        val text = listOf("Flow", "✅ tapOn", "🔲 assertVisible", "🔲 inputText").joinToString("\n")

        assertThat(focusedLineIndex(text)).isEqualTo(2)
    }

    @Test
    fun `focuses the last completed line when nothing is pending`() {
        val text = listOf("Flow", "✅ tapOn", "✅ assertVisible").joinToString("\n")

        assertThat(focusedLineIndex(text)).isEqualTo(2)
    }

    @Test
    fun `follows the tail of plain text output which has no emoji markers`() {
        val text = (1..50).joinToString("\n") { "tapOn $it... COMPLETED" } + "\n"

        assertThat(focusedLineIndex(text)).isEqualTo(49)
    }

    @Test
    fun `is zero for empty text`() {
        assertThat(focusedLineIndex("")).isEqualTo(0)
    }
}
