package maestro.orchestra.error

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

internal class ParserErrorRendererTest {

    @Test
    fun `renders title, location, snippet, message and docs`() {
        val flow = listOf(
            "appId: com.example",
            "---",
            "- launchApp:",
            "    appId: com.example",
            "    fooz: bar",
            "- tapOn: \"Hello\"",
        ).joinToString("\n")

        val rendered = ParserErrorRenderer.renderPlain(
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
            "Invalid command",
            "  at /flows/example.yaml:5:5",
            "",
            "       3 | - launchApp:",
            "       4 |     appId: com.example",
            "  >    5 |     fooz: bar",
            "               ^",
            "       6 | - tapOn: \"Hello\"",
            "",
            "  Unknown property 'fooz' on launchApp.",
            "  See: https://docs.maestro.dev/api-reference/commands",
        ).joinToString("\n")

        assertThat(rendered).isEqualTo(expected)
    }

    @Test
    fun `omits docs line when not provided`() {
        val flow = "appId: com.example\n- badCommand\n"

        val rendered = ParserErrorRenderer.renderPlain(
            ParserErrorContext(
                title = "Parsing Failed",
                filePath = "/flows/example.yaml",
                line = 2,
                column = 3,
                flowSource = flow,
                message = "Unknown command: badCommand",
                docs = null,
            )
        )

        assertThat(rendered).doesNotContain("See:")
        assertThat(rendered).contains("Unknown command: badCommand")
        assertThat(rendered).contains(">    2 | - badCommand")
    }

    @Test
    fun `falls back to 'line N M' header when no file path`() {
        val rendered = ParserErrorRenderer.renderPlain(
            ParserErrorContext(
                title = "Parsing Failed",
                filePath = null,
                line = 1,
                column = 1,
                flowSource = "appId: com.example\n",
                message = "Boom",
            )
        )

        assertThat(rendered).contains("at line 1:1")
    }

    @Test
    fun `clamps snippet window when error is on the first line`() {
        val flow = listOf(
            "badRoot: true",
            "appId: com.example",
            "---",
            "- launchApp",
        ).joinToString("\n")

        val rendered = ParserErrorRenderer.renderPlain(
            ParserErrorContext(
                title = "Parsing Failed",
                filePath = "/flow.yaml",
                line = 1,
                column = 1,
                flowSource = flow,
                message = "Unexpected key",
            )
        )

        assertThat(rendered).contains(">    1 | badRoot: true")
        assertThat(rendered).contains("       3 | ---")
    }

    @Test
    fun `multi-line message is indented`() {
        val rendered = ParserErrorRenderer.renderPlain(
            ParserErrorContext(
                title = "Config Field Required",
                filePath = "/flow.yaml",
                line = 1,
                column = 1,
                flowSource = "appId: com.example\n",
                message = "Either 'url' or 'appId' must be specified.\nFor mobile apps, use:\nappId: com.example",
            )
        )

        assertThat(rendered).contains("  Either 'url' or 'appId' must be specified.")
        assertThat(rendered).contains("  For mobile apps, use:")
        assertThat(rendered).contains("  appId: com.example")
    }
}
