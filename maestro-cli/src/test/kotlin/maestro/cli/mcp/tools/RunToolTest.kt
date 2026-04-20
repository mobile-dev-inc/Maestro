package maestro.cli.mcp.tools

import com.google.common.truth.Truth.assertThat
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import maestro.cli.session.MaestroSessionManager
import org.junit.jupiter.api.Test

class RunToolTest {

    @Test
    fun `parse accepts inline yaml mode`() {
        val args = expectParseSuccess(
            buildArgs {
                put("device_id", "device-1")
                put("yaml", "- tapOn: Search")
            }
        )

        assertThat(args.deviceId).isEqualTo("device-1")
        assertThat(args.input).isInstanceOf(RunInput.InlineYaml::class.java)
        assertThat((args.input as RunInput.InlineYaml).yaml).isEqualTo("- tapOn: Search")
        assertThat(args.env).isEmpty()
    }

    @Test
    fun `parse accepts files mode`() {
        val args = expectParseSuccess(
            buildArgs {
                put("device_id", "device-1")
                putJsonArray("files") {
                    add("flow1.yaml")
                    add("flow2.yaml")
                }
            }
        )

        val files = args.input as RunInput.Files
        assertThat(files.paths.map { it.path }).containsExactly("flow1.yaml", "flow2.yaml").inOrder()
    }

    @Test
    fun `parse accepts dir mode with tags`() {
        val args = expectParseSuccess(
            buildArgs {
                put("device_id", "device-1")
                put("dir", "tests/")
                putJsonArray("include_tags") {
                    add("smoke")
                    add("regression")
                }
                putJsonArray("exclude_tags") { add("slow") }
            }
        )

        val dir = args.input as RunInput.Directory
        assertThat(dir.path.path).isEqualTo("tests")
        assertThat(dir.includeTags).containsExactly("smoke", "regression").inOrder()
        assertThat(dir.excludeTags).containsExactly("slow")
    }

    @Test
    fun `parse rejects missing device_id`() {
        val failure = expectParseFailure(
            buildArgs { put("yaml", "- tapOn: x") }
        )
        assertThat(failure).contains("device_id")
    }

    @Test
    fun `parse rejects no mode provided`() {
        val failure = expectParseFailure(
            buildArgs { put("device_id", "device-1") }
        )
        assertThat(failure).contains("Exactly one of")
    }

    @Test
    fun `parse rejects multiple modes provided`() {
        val failure = expectParseFailure(
            buildArgs {
                put("device_id", "device-1")
                put("yaml", "- tapOn: x")
                putJsonArray("files") { add("a.yaml") }
            }
        )
        assertThat(failure).contains("mutually exclusive")
    }

    @Test
    fun `parse rejects all three modes provided`() {
        val failure = expectParseFailure(
            buildArgs {
                put("device_id", "device-1")
                put("yaml", "- tapOn: x")
                putJsonArray("files") { add("a.yaml") }
                put("dir", "tests/")
            }
        )
        assertThat(failure).contains("mutually exclusive")
    }

    @Test
    fun `parse rejects empty files list`() {
        val failure = expectParseFailure(
            buildArgs {
                put("device_id", "device-1")
                putJsonArray("files") {}
            }
        )
        assertThat(failure).contains("at least one path")
    }

    @Test
    fun `parse rejects tags with inline yaml`() {
        val failure = expectParseFailure(
            buildArgs {
                put("device_id", "device-1")
                put("yaml", "- tapOn: x")
                putJsonArray("include_tags") { add("smoke") }
            }
        )
        assertThat(failure).contains("only valid with `dir`")
    }

    @Test
    fun `parse rejects tags with files mode`() {
        val failure = expectParseFailure(
            buildArgs {
                put("device_id", "device-1")
                putJsonArray("files") { add("a.yaml") }
                putJsonArray("exclude_tags") { add("slow") }
            }
        )
        assertThat(failure).contains("only valid with `dir`")
    }

    @Test
    fun `parse captures env map`() {
        val args = expectParseSuccess(
            buildArgs {
                put("device_id", "device-1")
                put("yaml", "- tapOn: x")
                putJsonObject("env") {
                    put("APP_ID", "com.example.app")
                    put("LANG", "en")
                }
            }
        )

        assertThat(args.env).containsExactly("APP_ID", "com.example.app", "LANG", "en")
    }

    @Test
    fun `handle returns error result when args are invalid`() {
        val result = RunTool.handle(
            CallToolRequest(CallToolRequestParams(name = "run", arguments = buildArgs { put("yaml", "- tapOn: x") })),
            MaestroSessionManager,
        )

        assertThat(result.isError).isTrue()
        val text = (result.content.single() as TextContent).text
        assertThat(text).contains("device_id")
    }

    private fun expectParseSuccess(arguments: JsonObject): RunToolArgs {
        val result = RunToolArgs.parse(arguments)
        assertThat(result).isInstanceOf(ParseResult.Success::class.java)
        return (result as ParseResult.Success).args
    }

    private fun expectParseFailure(arguments: JsonObject): String {
        val result = RunToolArgs.parse(arguments)
        assertThat(result).isInstanceOf(ParseResult.Failure::class.java)
        return (result as ParseResult.Failure).message
    }

    private fun buildArgs(builder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject(builder)
}
