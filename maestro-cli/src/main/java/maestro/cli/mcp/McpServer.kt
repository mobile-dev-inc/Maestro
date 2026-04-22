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
import maestro.cli.mcp.hierarchy.HierarchySnapshotStore
import maestro.cli.session.MaestroSessionManager
import maestro.debuglog.LogConfig
import maestro.cli.mcp.tools.ListDevicesTool
import maestro.cli.mcp.tools.TakeScreenshotTool
import maestro.cli.mcp.tools.RunTool
import maestro.cli.mcp.tools.InspectViewHierarchyTool
import maestro.cli.mcp.tools.CheatSheetTool
import maestro.cli.mcp.tools.RunOnCloudTool
import maestro.cli.mcp.tools.GetCloudRunStatusTool
import maestro.cli.mcp.tools.ListCloudDevicesTool
import maestro.cli.util.WorkingDirectory
import java.io.PrintStream

internal val INSTRUCTIONS = """
    Maestro MCP authors, edits and runs UI tests (YAML flows) on Android, iOS, Chromium, or Maestro Cloud. Use for writing, running, or debugging a mobile/web UI test, reproducing a bug, or self-validating a UI change.

    Every local tool (`take_screenshot`, `inspect_view_hierarchy`, `run`) needs a `device_id` from `list_devices` first.

    Docs: https://docs.maestro.dev/llms.txt - call `cheat_sheet` before authoring any flow with assertions, conditionals, nested properties, or multiple screens.

    ## Local workflow

    `list_devices` -> `inspect_view_hierarchy` -> `run`.

    1. `list_devices`: pick a `device_id` (mobile simulator/emulator, or `chromium` for web). If empty, ask the user to boot one.
    2. `inspect_view_hierarchy`: fetch the hierarchy before targeting elements. Use `take_screenshot` when a visual helps. Re-inspect after any UI change.
    3. `run`: pass exactly one of `{ yaml }` (inline, preferred), `{ files }`, or `{ dir, include_tags, exclude_tags }`. Always include `device_id`. Pass `env` for flow variables. `run` validates syntax and checks `text:` selectors against the latest hierarchy; on miss, re-inspect and copy on-screen text verbatim. Pass `skip_selector_validation: true` for dynamically-rendered text.

    Mobile flows declare `appId` and start with `launchApp`; web flows declare `url` and start with `openLink`. `include_tags`/`exclude_tags` are bare names without `@`. Prefer one full flow over many single-command calls.

    ## Cloud workflow

    `list_cloud_devices` -> `run_on_cloud` -> `get_cloud_run_status` (poll).

    `list_cloud_devices` returns valid `{device_model, device_os}` pairs. Pass them verbatim; never lowercase, reformat, or infer. `run_on_cloud` submits a flow or folder and returns `upload_id`, `project_id`, and a dashboard URL (async). Poll `get_cloud_run_status` every 60s until `status` is terminal (SUCCESS, ERROR, CANCELED, WARNING). No tool lists past runs; ask for the `upload_id` or URL.

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
    val snapshotStore = HierarchySnapshotStore()

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
        RunTool.create(sessionManager, snapshotStore),
        InspectViewHierarchyTool.create(sessionManager, snapshotStore),
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