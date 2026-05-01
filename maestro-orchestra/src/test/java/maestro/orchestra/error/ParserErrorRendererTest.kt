package maestro.orchestra.error

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

internal class ParserErrorRendererTest {

    @Test
    fun `renderSummary returns a single line with title and location`() {
        val summary = ParserErrorRenderer.renderSummary(
            ParserErrorContext(
                title = "Invalid command",
                filePath = "/flows/example.yaml",
                line = 5,
                column = 9,
                flowSource = "ignored",
                message = "ignored",
            )
        )

        assertThat(summary).isEqualTo("Invalid command at /flows/example.yaml:5:9")
        assertThat(summary).doesNotContain("\n")
    }

    @Test
    fun `renderSummary falls back to 'line N M' when no file path`() {
        val summary = ParserErrorRenderer.renderSummary(
            ParserErrorContext(
                title = "Parsing Failed",
                filePath = null,
                line = 1,
                column = 1,
                flowSource = "ignored",
                message = "ignored",
            )
        )

        assertThat(summary).isEqualTo("Parsing Failed at line 1:1")
    }

    @Test
    fun `renderDetail produces snippet, caret, message and docs without title`() {
        val flow = listOf(
            "appId: com.example",
            "---",
            "- launchApp:",
            "    appId: com.example",
            "    fooz: bar",
            "- tapOn: \"Hello\"",
        ).joinToString("\n")

        val detail = ParserErrorRenderer.renderDetail(
            ParserErrorContext(
                title = "Invalid command",
                filePath = "/flows/example.yaml",
                line = 5,
                column = 5,
                flowSource = flow,
                message = "Unknown property 'fooz' on launchApp.",
                docs = "https://docs.maestro.dev/api-reference/commands",
            )
        )

        val expected = listOf(
            "       3 | - launchApp:",
            "       4 |     appId: com.example",
            "  >    5 |     fooz: bar",
            "               ^",
            "       6 | - tapOn: \"Hello\"",
            "",
            "  Unknown property 'fooz' on launchApp.",
            "  See: https://docs.maestro.dev/api-reference/commands",
        ).joinToString("\n")

        assertThat(detail).isEqualTo(expected)
        // The title is in the summary, not the detail.
        assertThat(detail).doesNotContain("Invalid command")
    }

    @Test
    fun `renderDetail omits docs line when not provided`() {
        val detail = ParserErrorRenderer.renderDetail(
            ParserErrorContext(
                title = "Parsing Failed",
                filePath = "/flows/example.yaml",
                line = 2,
                column = 3,
                flowSource = "appId: com.example\n- badCommand\n",
                message = "Unknown command: badCommand",
                docs = null,
            )
        )

        assertThat(detail).doesNotContain("See:")
        assertThat(detail).contains("Unknown command: badCommand")
        assertThat(detail).contains(">    2 | - badCommand")
    }

    @Test
    fun `renderDetail clamps snippet window when error is on the first line`() {
        val flow = listOf(
            "badRoot: true",
            "appId: com.example",
            "---",
            "- launchApp",
        ).joinToString("\n")

        val detail = ParserErrorRenderer.renderDetail(
            ParserErrorContext(
                title = "Parsing Failed",
                filePath = "/flow.yaml",
                line = 1,
                column = 1,
                flowSource = flow,
                message = "Unexpected key",
            )
        )

        assertThat(detail).contains(">    1 | badRoot: true")
        assertThat(detail).contains("       3 | ---")
    }

    @Test
    fun `renderDetail indents each line of a multi-line message`() {
        val detail = ParserErrorRenderer.renderDetail(
            ParserErrorContext(
                title = "Config Field Required",
                filePath = "/flow.yaml",
                line = 1,
                column = 1,
                flowSource = "appId: com.example\n",
                message = "Either 'url' or 'appId' must be specified.\nFor mobile apps, use:\nappId: com.example",
            )
        )

        assertThat(detail).contains("  Either 'url' or 'appId' must be specified.")
        assertThat(detail).contains("  For mobile apps, use:")
        assertThat(detail).contains("  appId: com.example")
    }

    @Test
    fun `formatForTerminal joins summary and detail with a blank line`() {
        val error = SyntaxError(
            message = "Invalid command at /flow.yaml:5:5",
            detail = "       5 |     fooz: bar\n               ^\n\n  Unknown property 'fooz'.",
        )

        assertThat(error.formatForTerminal()).isEqualTo(
            "Invalid command at /flow.yaml:5:5\n" +
                "\n" +
                "       5 |     fooz: bar\n" +
                "               ^\n" +
                "\n" +
                "  Unknown property 'fooz'."
        )
    }

    @Test
    fun `formatForTerminal returns just the summary when detail is null`() {
        val error = SyntaxError(message = "Failed to parse file: /flow.yaml", detail = null)
        assertThat(error.formatForTerminal()).isEqualTo("Failed to parse file: /flow.yaml")
    }
}
