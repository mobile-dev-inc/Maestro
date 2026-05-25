package maestro.cli.mcp

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class McpServerTest {

    @Test
    fun `instructions fit the 2KB MCP cap`() {
        assertThat(INSTRUCTIONS.toByteArray(Charsets.UTF_8).size).isLessThan(2048)
    }

    @Test
    fun `instructions reference every registered tool`() {
        val registeredTools = listOf(
            "list_devices",
            "take_screenshot",
            "run",
            "inspect_screen",
            "cheat_sheet",
            "list_cloud_devices",
            "run_on_cloud",
            "get_cloud_run_status",
        )
        registeredTools.forEach { tool ->
            assertThat(INSTRUCTIONS).contains(tool)
        }
    }
}
