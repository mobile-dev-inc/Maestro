package maestro.cli.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import maestro.cli.session.MaestroSessionManager
import maestro.debuglog.LogConfig
import maestro.cli.mcp.tools.ListDevicesTool
import maestro.cli.mcp.tools.TakeScreenshotTool
import maestro.cli.mcp.tools.RunTool
import maestro.cli.mcp.tools.InspectScreenTool
import maestro.cli.mcp.tools.CheatSheetTool
import maestro.cli.mcp.tools.RunOnCloudTool
import maestro.cli.mcp.tools.GetCloudRunStatusTool
import maestro.cli.mcp.tools.ListCloudDevicesTool
import maestro.cli.util.WorkingDirectory
import java.io.PrintStream

internal val INSTRUCTIONS = """
    Maestro MCP authors, edits and runs UI tests via declarative YAML flows on Android emulators, iOS simulators, Chromium browsers, or Maestro Cloud. Use when the user wants to write, run, or debug a mobile or web UI test, reproduce a bug, or self-validate a user-facing change you just built.

    Every local tool (`take_screenshot`, `inspect_screen`, `run`) needs a `device_id` from `list_devices` first.

    Docs: https://docs.maestro.dev/llms.txt. Call `cheat_sheet` before authoring unfamiliar commands, required args, nested properties, conditionals, or multi-screen flows.

    ## Local workflow

    `list_devices` -> `inspect_screen` -> `run`.

    1. `list_devices`: pick a `device_id` (mobile simulator/emulator, or `chromium` for web). If empty, ask the user to boot one. Use only IDs returned.
    2. `inspect_screen`: fetch the current screen's view hierarchy before targeting elements. Use `take_screenshot` when a visual helps. Re-inspect after any UI change.
    3. `run`: pass exactly one of `{ yaml }` (inline, preferred for exploration), `{ files }`, or `{ dir, include_tags, exclude_tags }`. Always include `device_id`. Pass `env` for flow variables. `run` validates syntax.

    Mobile flows declare `appId` and start with `launchApp`; web flows declare `url` and start with `openLink`. `include_tags`/`exclude_tags` are bare names without `@`. Prefer one full flow over many single-command calls.

    ## Cloud workflow

    `list_cloud_devices` -> `run_on_cloud` -> `get_cloud_run_status` (poll).

    `list_cloud_devices` returns valid `{device_model, device_os}` pairs. Pass them verbatim; never lowercase, reformat, or infer. `run_on_cloud` submits a flow or folder, returns `upload_id`, `project_id`, and a dashboard URL (async). Poll `get_cloud_run_status` every 60s until `status` is terminal (SUCCESS, ERROR, CANCELED, WARNING). Tags only apply with a folder. No tool lists past runs; ask for the `upload_id` or URL for previous runs.

    Auth: `maestro login` (or `MAESTRO_CLOUD_API_KEY` for non-interactive). Never echo the API key.
""".trimIndent()

// Captures the real stdout so the MCP protocol channel stays pristine even after
// `claimMcpStdout()` routes `System.out` to stderr. Defaults to `System.out` for
// test/dev paths that invoke `runMaestroMcpServer()` without going through main().
private var mcpProtocolOut: PrintStream = System.out

/**
 * Must run before any MCP-adjacent class loads: static init (kotlin-logging banner,
 * first-run analytics notice, third-party println-on-load) writes to whatever stdout
 * is at that moment and corrupts the JSON-RPC handshake for strict clients like
 * Claude Desktop.
 */
internal fun claimMcpStdout() {
    mcpProtocolOut = System.out
    System.setOut(System.err)
}

fun runMaestroMcpServer() {
    // LogConfig silences log4j; the stdout redirect in `claimMcpStdout` catches
    // everything else. Keep both; they cover different noise sources.
    LogConfig.configure(logFileName = null, printToConsole = false)

    val sessionManager = MaestroSessionManager

    val server = Server(
        serverInfo = Implementation(
            name = "maestro",
            version = "1.0.0"
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true)
            )
        ),
        instructions = INSTRUCTIONS
    )

    server.addTools(listOf(
        ListDevicesTool.create(),
        TakeScreenshotTool.create(sessionManager),
        RunTool.create(sessionManager),
        InspectScreenTool.create(sessionManager),
        CheatSheetTool.create(),
        ListCloudDevicesTool.create(),
        RunOnCloudTool.create(),
        GetCloudRunStatusTool.create()
    ))

    val transport = StdioServerTransport(
        System.`in`.asSource().buffered(),
        mcpProtocolOut.asSink().buffered()
    )

    System.err.println("MCP Server: Started. Waiting for messages. Working directory: ${WorkingDirectory.baseDir}")

    runBlocking {
        val session = server.createSession(transport)
        val done = Job()
        session.onClose { done.complete() }
        done.join()
    }
}