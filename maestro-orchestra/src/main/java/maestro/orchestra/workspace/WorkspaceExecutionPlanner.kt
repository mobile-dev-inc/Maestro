package maestro.orchestra.workspace

import maestro.orchestra.CustomCommandDef
import maestro.orchestra.MaestroCommand
import maestro.orchestra.WorkspaceConfig
import maestro.orchestra.error.SyntaxError
import maestro.orchestra.error.ValidationError
import maestro.orchestra.workspace.ExecutionOrderPlanner.getFlowsToRunInSequence
import maestro.orchestra.yaml.MaestroFlowParser
import maestro.orchestra.yaml.YamlCommandReader
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.streams.toList
import maestro.utils.isRegularFile

object WorkspaceExecutionPlanner {

    private val logger = LoggerFactory.getLogger(WorkspaceExecutionPlanner::class.java)

    fun plan(
        input: Set<Path>,
        includeTags: List<String>,
        excludeTags: List<String>,
        config: Path?,
    ): ExecutionPlan {
        if (input.any { it.notExists() }) {
            throw ValidationError("""
                Flow path does not exist: ${input.find { it.notExists() }?.absolutePathString()}
            """.trimIndent())
        }

        if (input.isRegularFile) {
            val flow = input.first()
            val singleFileCustomCommands = discoverCustomCommandsFromSubflowsDir(flow)
            validateFlowFile(flow, singleFileCustomCommands)
            val workspaceConfig = if (config != null) {
                YamlCommandReader.readWorkspaceConfig(config.absolute())
            } else {
                WorkspaceConfig()
            }
            return ExecutionPlan(
                flowsToRun = input.toList(),
                sequence = FlowSequence(emptyList()),
                workspaceConfig = workspaceConfig,
                customCommands = singleFileCustomCommands,
            )
        }

        // retrieve all Flow files

        val (files, directories) = input.partition { it.isRegularFile() }

        // Resolve config path before filtering, so auto-discovered configs are also excluded
        val resolvedConfigPath = config?.absolute()
            ?: directories.firstNotNullOfOrNull { findConfigFile(it) }

        val flowFiles = files.filter { isFlowFile(it, resolvedConfigPath) }
        val flowFilesInDirs: List<Path> = directories.flatMap { dir -> Files
            .walk(dir)
            .filter { isFlowFile(it, resolvedConfigPath) }
            .toList()
        }
        if (flowFilesInDirs.isEmpty() && flowFiles.isEmpty()) {
            throw ValidationError("""
                Flow directories do not contain any Flow files: ${directories.joinToString(", ") { it.absolutePathString() }}
            """.trimIndent())
        }

        val customCommands = discoverCustomCommands(flowFiles + flowFilesInDirs)

        // Command-definition files (any file declaring `command:`) are not runnable flows.
        val customCommandFiles = customCommands.values.map { it.sourceFile }.toSet()

        // Filter flows based on flows config

        val workspaceConfig =
            if (resolvedConfigPath != null) YamlCommandReader.readWorkspaceConfig(resolvedConfigPath)
            else WorkspaceConfig()

        val globs = workspaceConfig.flows ?: listOf("*")

        val matchers = globs.flatMap { glob ->
            directories.map { it.fileSystem.getPathMatcher(escapeSlashesForWindows("glob:${it.pathString}${it.fileSystem.separator}$glob")) }
        }

        val unsortedFlowFiles = (flowFiles + flowFilesInDirs.filter { path ->
            matchers.any { matcher -> matcher.matches(path) }
        }).filter { it.absolute() !in customCommandFiles }

        if (unsortedFlowFiles.isEmpty()) {
            if ("*" == globs.singleOrNull()) {
                val message = """
                    Top-level directories do not contain any Flows: ${directories.joinToString(", ") { it.absolutePathString() }}
                    To configure Maestro to run Flows in subdirectories, check out the following resources:
                      * https://maestro.mobile.dev/cli/test-suites-and-reports#inclusion-patterns
                      * https://blog.mobile.dev/maestro-best-practices-structuring-your-test-suite-54ec390c5c82
                """.trimIndent()
                throw ValidationError(message)
            } else {
                val message = """
                    |Flow inclusion pattern(s) did not match any Flow files:
                    |${toYamlListString(globs)}
                    """.trimMargin()
                throw ValidationError(message)
            }
        }

        // Filter flows based on tags

        val configPerFlowFile = unsortedFlowFiles.associateWith {
            val commands = validateFlowFile(it, customCommands)
            YamlCommandReader.getConfig(commands)
        }

        val allIncludeTags = includeTags + (workspaceConfig.includeTags?.toList() ?: emptyList())
        val allExcludeTags = excludeTags + (workspaceConfig.excludeTags?.toList() ?: emptyList())
        val allFlows = unsortedFlowFiles.filter {
            val config = configPerFlowFile[it]
            val tags = config?.tags ?: emptyList()

            (allIncludeTags.isEmpty() || tags.any(allIncludeTags::contains))
                && (allExcludeTags.isEmpty() || !tags.any(allExcludeTags::contains))
        }

        if (allFlows.isEmpty()) {
            val message = """
                |Include / Exclude tags did not match any Flows:
                |
                |Include Tags:
                |${toYamlListString(allIncludeTags)}
                |
                |Exclude Tags:
                |${toYamlListString(allExcludeTags)}
                """.trimMargin()
            throw ValidationError(message)
        }

        // Handle sequential execution

        val pathsByName = allFlows.associateBy {
            val config = configPerFlowFile[it]
            (config?.name ?: parseFileName(it))
        }
        val flowsToRunInSequence = workspaceConfig.executionOrder?.flowsOrder?.let {
            getFlowsToRunInSequence(pathsByName, it)
        } ?: emptyList()
        var normalFlows = allFlows - flowsToRunInSequence.toSet()

        // validation of media files for add media command
        allFlows.forEach {
            val commands = YamlCommandReader
                .readCommands(it, customCommands)
                .mapNotNull { maestroCommand -> maestroCommand.addMediaCommand }
            val mediaPaths = commands.flatMap { addMediaCommand -> addMediaCommand.mediaPaths }
            YamlCommandsPathValidator.validatePathsExistInWorkspace(input, it, mediaPaths)
        }

        val executionPlan = ExecutionPlan(
            flowsToRun = normalFlows,
            sequence = FlowSequence(
                flowsToRunInSequence,
                workspaceConfig.executionOrder?.continueOnFailure
            ),
            workspaceConfig = workspaceConfig,
            customCommands = customCommands,
        )

        logger.info("Created execution plan: $executionPlan")

        return executionPlan
    }

    private fun validateFlowFile(
        topLevelFlowPath: Path,
        customCommands: Map<String, CustomCommandDef> = emptyMap(),
    ): List<MaestroCommand> {
        return YamlCommandReader.readCommands(topLevelFlowPath, customCommands)
    }

    internal fun discoverCustomCommandsFromSubflowsDir(flowFile: Path): Map<String, CustomCommandDef> {
        val parent = flowFile.absolute().parent ?: return emptyMap()
        val cmdDir = parent.resolve("subflows")
        if (!cmdDir.isDirectory()) return emptyMap()
        val files = Files.walk(cmdDir).use { stream ->
            stream.filter { it.isRegularFile() && isYamlFile(it) }.toList()
        }
        return discoverCustomCommands(files)
    }

    private fun isYamlFile(path: Path): Boolean {
        val name = path.fileName.toString().lowercase()
        return name.endsWith(".yaml") || name.endsWith(".yml")
    }

    internal fun discoverCustomCommands(files: Iterable<Path>): Map<String, CustomCommandDef> {
        val registry = mutableMapOf<String, CustomCommandDef>()
        val seenFiles = mutableSetOf<Path>()
        for (file in files) {
            val canonical = file.absolute().normalize()
            if (!seenFiles.add(canonical)) continue
            val config = try {
                YamlCommandReader.readConfig(file)
            } catch (e: Exception) {
                // Skip files that fail to parse during the pre-pass. If the file is a
                // runnable flow, the real parse error will surface during validation;
                // if it was meant as a command-definition file, callers see "Invalid
                // Command" — log a hint so the cause is recoverable from DEBUG output.
                logger.debug("Skipping {} during custom-command discovery: {}", file, e.message)
                continue
            }
            val name = config.command ?: continue
            val arguments = config.toCustomCommandArguments().orEmpty()

            if (MaestroFlowParser.isBuiltInCommand(name)) {
                throw SyntaxError(
                    "Custom command name '$name' (declared in ${file.absolutePathString()}) " +
                        "collides with a built-in Maestro command."
                )
            }
            val existing = registry[name]
            if (existing != null) {
                throw SyntaxError(
                    "Duplicate custom command name '$name': declared in both " +
                        "${existing.sourceFile.absolutePathString()} and ${file.absolutePathString()}."
                )
            }
            registry[name] = CustomCommandDef(
                name = name,
                sourceFile = file.absolute(),
                arguments = arguments,
            )
        }

        // Reject nesting: a custom-command body cannot invoke another custom command.
        for (def in registry.values) {
            val body = try {
                YamlCommandReader.readCommands(def.sourceFile, registry)
            } catch (e: Exception) {
                logger.debug("Skipping nesting check for {}: {}", def.sourceFile, e.message)
                continue
            }
            val nested = body.firstNotNullOfOrNull { mc ->
                mc.runFlowCommand?.customCommandName
                    ?.takeIf { it != def.name }
                    ?.let(registry::get)
            }
            if (nested != null) {
                throw SyntaxError(
                    "Custom command '${nested.name}' invoked inside custom command '${def.name}' " +
                        "— nesting is not supported."
                )
            }
        }
        return registry
    }

    private fun findConfigFile(input: Path): Path? {
        return input.resolve("config.yaml")
            .takeIf { it.exists() }
            ?: input.resolve("config.yml")
                .takeIf { it.exists() }
    }

    private fun toYamlListString(strings: List<String>): String {
        return strings.joinToString("\n") { "- $it" }
    }

    private fun parseFileName(file: Path): String {
        return file.fileName.toString().substringBeforeLast(".")
    }

    private fun escapeSlashesForWindows(pathString: String): String {
        return pathString.replace("\\","\\\\")
    }

    data class FlowSequence(
        val flows: List<Path>,
        val continueOnFailure: Boolean? = true,
    )

    data class ExecutionPlan(
        val flowsToRun: List<Path>,
        val sequence: FlowSequence,
        val workspaceConfig: WorkspaceConfig = WorkspaceConfig(),
        val customCommands: Map<String, CustomCommandDef> = emptyMap(),
    )
}
