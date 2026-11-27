package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.types.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.cli.mcp.McpMaestroSessionManager
import maestro.cli.report.TestDebugReporter
import maestro.cli.util.WorkingDirectory
import maestro.orchestra.util.Env.withDefaultEnvVars
import maestro.orchestra.util.Env.withInjectedShellEnvVars
import maestro.orchestra.error.ValidationError
import maestro.orchestra.workspace.WorkspaceExecutionPlanner
import maestro.orchestra.workspace.WorkspaceExecutionPlanner.ExecutionPlan
import maestro.orchestra.workspace.WorkspaceExecutionPlanner.FlowSequence
import maestro.orchestra.yaml.YamlCommandReader
import maestro.utils.TempFileHandler
import java.io.File
import java.nio.file.Path

object RunTool {

    private const val TOOL_NAME = "run"
    private const val TOOL_DESCRIPTION = """
        Simulate user interactions with a mobile device by running raw Maestro commands or Maestro flow files.

        Exactly one of `yaml`, `files`, or `dir` must be provided:

        1) Inline YAML (preferred for exploration/debugging):
           { "device_id": "...", "yaml": "- tapOn: 123" }

        2) Specific test files:
           { "device_id": "...", "files": ["flow1.yaml", "flow2.yaml"] }

        3) Directory with optional tag filtering:
           { "device_id": "...", "dir": "tests/", "include_tags": ["smoke"], "exclude_tags": ["slow"] }

        `env` is optional in all modes and injects environment variables available to the flow.

        Every flow writes a debug bundle (commands.json, logs/maestro.log, the failed step's
        screenshot and view hierarchy, takeScreenshot output) and returns its path as
        `artifacts_dir`. Pass `output_dir` to choose where bundles go; if you have a session
        scratch or temp directory, pass that. On failure the result
        also carries `failed_command`, `debug_message` and a `screenshot` path; read those
        files only if the error alone doesn't explain the failure. Set `include_screenshot`
        to attach the failure screenshot as an image instead, and `capture_all_steps` to
        save a screenshot of every step plus a screen recording (slower; files only).

        Syntax is validated as part of this call; no separate pre-check is needed.

        Some commands require arguments and fail to parse when written bare. `assertScreenshot`
        needs a name (e.g. `assertScreenshot: home_screen` or `assertScreenshot: { path: home_screen }`);
        never emit `- assertScreenshot` on its own. When in doubt, check `cheat_sheet`.

        If no device is running, ask the user to start one first.
        Use `inspect_screen` to get the current screen before guessing at commands.
        Use `cheat_sheet` for Maestro flow syntax.
    """

    internal fun create(sessionManager: McpMaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = TOOL_NAME,
                description = TOOL_DESCRIPTION.trimIndent(),
                inputSchema = buildInputSchema(),
            )
        ) { request ->
            handle(request, sessionManager)
        }
    }

    internal fun handle(
        request: CallToolRequest,
        sessionManager: McpMaestroSessionManager,
    ): CallToolResult {
        val args = when (val parsed = RunToolArgs.parse(request.arguments)) {
            is ParseResult.Failure -> return errorResult(parsed.message)
            is ParseResult.Success -> parsed.args
        }

        val executable = try {
            resolveExecutable(args.input)
        } catch (e: FlowInputException) {
            return errorResult(e.message ?: "Invalid input")
        } catch (e: ValidationError) {
            return errorResult(e.message ?: "Invalid workspace")
        }

        return try {
            val bundleRoot = args.outputDir
                ?.let { WorkingDirectory.resolve(it).toPath().toAbsolutePath() }
                ?: TestDebugReporter.getDebugOutputPath()

            val result = sessionManager.withSession(
                deviceId = args.deviceId,
            ) { session ->
                val runner = FlowRunner(session.maestro, args.deviceId, bundleRoot, args.captureAllSteps)
                when (executable) {
                    is Executable.Inline -> runInline(runner, executable.yaml, args.env)
                    is Executable.Plan -> runPlan(runner, executable.plan, args.env)
                }
            }

            val images = if (args.includeScreenshot) {
                result.screenshots.map { McpImages.screenshotContent(it.readBytes()) }
            } else {
                emptyList()
            }
            CallToolResult(
                content = listOf(TextContent(result.payload.toString())) + images,
                isError = !result.success,
            )
        } catch (e: Exception) {
            errorResult("Failed to run flow: ${e.message}")
        }
    }

    private fun resolveExecutable(input: RunInput): Executable = when (input) {
        is RunInput.InlineYaml -> Executable.Inline(input.yaml)
        is RunInput.Files -> Executable.Plan(
            ExecutionPlan(
                flowsToRun = resolveFiles(input.paths),
                sequence = FlowSequence(emptyList()),
            )
        )
        is RunInput.Directory -> Executable.Plan(
            planDirectory(input.path, input.includeTags, input.excludeTags)
        )
    }

    private fun buildInputSchema(): ToolSchema = ToolSchema(
        properties = buildJsonObject {
            putJsonObject("device_id") {
                put("type", "string")
                put("description", "The ID of the device to run the flow on. Use `list_devices` to see connected devices.")
            }
            putJsonObject("yaml") {
                put("type", "string")
                put("description", "Inline Maestro flow YAML. Mutually exclusive with `files` and `dir`.")
            }
            putJsonObject("files") {
                put("type", "array")
                put("description", "List of YAML flow files to execute. Mutually exclusive with `yaml` and `dir`.")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("dir") {
                put("type", "string")
                put("description", "Directory containing YAML flow files. Mutually exclusive with `yaml` and `files`.")
            }
            putJsonObject("include_tags") {
                put("type", "array")
                put("description", "Only run flows with at least one of these tags. Valid only with `dir`.")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("exclude_tags") {
                put("type", "array")
                put("description", "Skip flows with any of these tags. Valid only with `dir`.")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("env") {
                put("type", "object")
                put("description", "Environment variables to inject into the flow(s).")
                putJsonObject("additionalProperties") { put("type", "string") }
            }
            putJsonObject("output_dir") {
                put("type", "string")
                put("description", "Directory to write each flow's debug bundle into. Defaults to Maestro's debug output directory.")
            }
            putJsonObject("include_screenshot") {
                put("type", "boolean")
                put("description", "Attach the screenshot of a failed step as an image. Off by default; the result always carries its path.")
            }
            putJsonObject("capture_all_steps") {
                put("type", "boolean")
                put("description", "Save a screenshot of every step and a screen recording to the bundle. Slower; nothing extra is returned inline.")
            }
        },
        required = listOf("device_id"),
    )

    private fun runInline(
        runner: FlowRunner,
        yaml: String,
        env: Map<String, String>,
    ): RunResult {
        val envWithShell = env.withInjectedShellEnvVars()
        return TempFileHandler().use { tempFiles ->
            val tempFile = tempFiles.createTempFile("maestro-flow", ".yaml")
            tempFile.writeText(yaml)
            val commands = YamlCommandReader.readCommands(tempFile.toPath())
            val flowName = YamlCommandReader.getConfig(commands)?.name ?: "inline"
            val run = runner.run(flowName, commands, envWithShell.withDefaultEnvVars(tempFile, runner.deviceId))
            RunResult(
                payload = buildJsonObject {
                    put("success", run.success)
                    put("device_id", runner.deviceId)
                    if (run.success) {
                        put("commands_executed", run.commandCount)
                        put("message", "Flow executed successfully")
                    } else {
                        put("message", "Flow execution failed")
                    }
                    putArtifacts(run)
                    if (envWithShell.isNotEmpty()) putEnv("env_vars", envWithShell)
                },
                success = run.success,
                screenshots = listOfNotNull(run.failure?.screenshot),
            )
        }
    }

    private fun runPlan(
        runner: FlowRunner,
        plan: ExecutionPlan,
        env: Map<String, String>,
    ): RunResult {
        val envWithShell = env.withInjectedShellEnvVars()
        val results = mutableListOf<FlowResult>()

        // Sequence flows run first, matching `maestro test` CLI semantics.
        // Abort sequence on failure unless `continueOnFailure` is set.
        val continueSequenceOnFailure = plan.sequence.continueOnFailure ?: true
        for (flow in plan.sequence.flows) {
            val result = runSingleFlow(runner, flow, envWithShell)
            results += result
            if (result is FlowResult.Failure && !continueSequenceOnFailure) break
        }

        plan.flowsToRun.forEach { flow ->
            results += runSingleFlow(runner, flow, envWithShell)
        }

        val allOk = results.all { it is FlowResult.Success }
        val totalCommands = results.filterIsInstance<FlowResult.Success>().sumOf { it.run.commandCount }

        val payload = buildJsonObject {
            put("success", allOk)
            put("device_id", runner.deviceId)
            put("total_flows", results.size)
            put("total_commands_executed", totalCommands)
            putJsonArray("results") {
                results.forEach { result ->
                    addJsonObject {
                        put("file", result.file)
                        put("success", result is FlowResult.Success)
                        when (result) {
                            is FlowResult.Success -> {
                                put("commands_executed", result.run.commandCount)
                                put("message", "Flow executed successfully")
                                putArtifacts(result.run)
                            }
                            is FlowResult.Failure -> {
                                put("message", "Flow execution failed")
                                if (result.run != null) putArtifacts(result.run) else put("error", result.error)
                            }
                        }
                    }
                }
            }
            if (envWithShell.isNotEmpty()) putEnv("env_vars", envWithShell)
            put(
                "message",
                if (allOk) "All flows executed successfully" else "Some flows failed to execute",
            )
        }
        val screenshots = results.filterIsInstance<FlowResult.Failure>().mapNotNull { it.run?.failure?.screenshot }
        return RunResult(payload = payload, success = allOk, screenshots = screenshots)
    }

    private fun runSingleFlow(
        runner: FlowRunner,
        flow: Path,
        envWithShell: Map<String, String>,
    ): FlowResult {
        val file = flow.toFile()
        val commands = try {
            YamlCommandReader.readCommands(flow)
        } catch (e: Exception) {
            return FlowResult.Failure(file.absolutePath, e.message ?: "Unknown error", run = null)
        }
        val flowName = YamlCommandReader.getConfig(commands)?.name ?: file.nameWithoutExtension
        val run = runner.run(flowName, commands, envWithShell.withDefaultEnvVars(file, runner.deviceId))
        return if (run.success) {
            FlowResult.Success(file.absolutePath, run)
        } else {
            FlowResult.Failure(file.absolutePath, run.failure?.error ?: "Unknown error", run)
        }
    }

    /** Fields every flow result carries: the bundle path, plus a compact failure summary when it failed. */
    private fun JsonObjectBuilder.putArtifacts(run: FlowRun) {
        put("artifacts_dir", run.artifactsDir.toString())
        val failure = run.failure ?: return
        put("error", failure.error)
        failure.failedCommand?.let { put("failed_command", it) }
        failure.debugMessage?.let { put("debug_message", it) }
        failure.screenshot?.let { put("screenshot", it.absolutePath) }
    }

    private fun resolveFiles(inputs: List<File>): List<Path> {
        val resolved = inputs.map { WorkingDirectory.resolve(it) }
        val missing = resolved.filter { !it.exists() }
        if (missing.isNotEmpty()) {
            throw FlowInputException(
                "Files not found: ${missing.joinToString(", ") { it.absolutePath }}"
            )
        }
        return resolved.map { it.toPath().toAbsolutePath() }
    }

    private fun planDirectory(
        dir: File,
        includeTags: List<String>,
        excludeTags: List<String>,
    ): ExecutionPlan = WorkspaceExecutionPlanner.plan(
        input = setOf(WorkingDirectory.resolve(dir).toPath().toAbsolutePath()),
        includeTags = includeTags,
        excludeTags = excludeTags,
        config = null,
    )

    private fun JsonObjectBuilder.putEnv(key: String, env: Map<String, String>) {
        putJsonObject(key) { env.forEach { (k, v) -> put(k, v) } }
    }

    private fun errorResult(message: String): CallToolResult =
        CallToolResult(content = listOf(TextContent(message)), isError = true)

    private data class RunResult(
        val payload: JsonObject,
        val success: Boolean,
        val screenshots: List<File> = emptyList(),
    )

    private sealed interface Executable {
        data class Inline(val yaml: String) : Executable
        data class Plan(val plan: ExecutionPlan) : Executable
    }

    private sealed interface FlowResult {
        val file: String

        data class Success(override val file: String, val run: FlowRun) : FlowResult

        /** [run] is null when the flow failed to parse and never ran. */
        data class Failure(override val file: String, val error: String, val run: FlowRun?) : FlowResult
    }

    private class FlowInputException(message: String) : RuntimeException(message)
}

internal sealed interface RunInput {
    data class InlineYaml(val yaml: String) : RunInput
    data class Files(val paths: List<File>) : RunInput
    data class Directory(
        val path: File,
        val includeTags: List<String>,
        val excludeTags: List<String>,
    ) : RunInput
}

internal data class RunToolArgs(
    val deviceId: String,
    val input: RunInput,
    val env: Map<String, String>,
    val outputDir: File? = null,
    val includeScreenshot: Boolean = false,
    val captureAllSteps: Boolean = false,
) {
    companion object {
        fun parse(arguments: JsonObject?): ParseResult {
            if (arguments == null) return ParseResult.Failure("device_id is required")

            val deviceId = arguments["device_id"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: return ParseResult.Failure("device_id is required")

            val yaml = arguments["yaml"]?.jsonPrimitive?.contentOrNull
            val files = arguments["files"]?.jsonArray?.map { it.jsonPrimitive.content }
            val dir = arguments["dir"]?.jsonPrimitive?.contentOrNull
            val includeTags = arguments["include_tags"]?.jsonArray?.map { it.jsonPrimitive.content }
                ?: emptyList()
            val excludeTags = arguments["exclude_tags"]?.jsonArray?.map { it.jsonPrimitive.content }
                ?: emptyList()

            val modesProvided = listOfNotNull(
                yaml?.let { "yaml" },
                files?.let { "files" },
                dir?.let { "dir" },
            )
            when (modesProvided.size) {
                0 -> return ParseResult.Failure(
                    "Exactly one of `yaml`, `files`, or `dir` must be provided"
                )
                1 -> Unit
                else -> return ParseResult.Failure(
                    "`yaml`, `files`, and `dir` are mutually exclusive; got: ${modesProvided.joinToString(", ")}"
                )
            }

            val input: RunInput = when {
                yaml != null -> {
                    if (includeTags.isNotEmpty() || excludeTags.isNotEmpty()) {
                        return ParseResult.Failure("`include_tags` / `exclude_tags` are only valid with `dir`")
                    }
                    RunInput.InlineYaml(yaml)
                }
                files != null -> {
                    if (files.isEmpty()) {
                        return ParseResult.Failure("`files` must contain at least one path")
                    }
                    if (includeTags.isNotEmpty() || excludeTags.isNotEmpty()) {
                        return ParseResult.Failure("`include_tags` / `exclude_tags` are only valid with `dir`")
                    }
                    RunInput.Files(files.map { File(it) })
                }
                dir != null -> RunInput.Directory(File(dir), includeTags, excludeTags)
                else -> error("unreachable")
            }

            val env = arguments["env"]?.jsonObject
                ?.mapValues { it.value.jsonPrimitive.content }
                ?: emptyMap()

            val outputDir = arguments["output_dir"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { File(it) }
            val includeScreenshot = arguments["include_screenshot"]?.jsonPrimitive?.booleanOrNull ?: false
            val captureAllSteps = arguments["capture_all_steps"]?.jsonPrimitive?.booleanOrNull ?: false

            return ParseResult.Success(
                RunToolArgs(deviceId, input, env, outputDir, includeScreenshot, captureAllSteps)
            )
        }
    }
}

internal sealed interface ParseResult {
    data class Success(val args: RunToolArgs) : ParseResult
    data class Failure(val message: String) : ParseResult
}
