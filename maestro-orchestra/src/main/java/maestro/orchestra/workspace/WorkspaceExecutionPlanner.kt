package maestro.orchestra.workspace

import maestro.orchestra.MaestroCommand
import maestro.orchestra.WorkspaceConfig
import maestro.orchestra.error.ValidationError
import maestro.orchestra.workspace.ExecutionOrderPlanner.getFlowsToRunInSequence
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
            validateFlowFile(input.first())
            val workspaceConfig = if (config != null) {
                YamlCommandReader.readWorkspaceConfig(config.absolute())
            } else {
                WorkspaceConfig()
            }
            return ExecutionPlan(
                flowsToRun = input.toList(),
                sequence = FlowSequence(emptyList()),
                workspaceConfig = workspaceConfig
            )
        }

        // retrieve all Flow files

        val (files, directories) = input.partition { it.isRegularFile() }

        val flowFiles = files.filter { isFlowFile(it, config) }
        val flowFilesInDirs: List<Path> = directories.flatMap { dir -> Files
            .walk(dir)
            .filter { isFlowFile(it, config) }
            .toList()
        }
        if (flowFilesInDirs.isEmpty() && flowFiles.isEmpty()) {
            throw ValidationError("""
                Flow directories do not contain any Flow files: ${directories.joinToString(", ") { it.absolutePathString() }}
            """.trimIndent())
        }

        // Filter flows based on flows config

        val workspaceConfig =
            if (config != null) YamlCommandReader.readWorkspaceConfig(config.absolute())
            else directories.firstNotNullOfOrNull { findConfigFile(it) }
                ?.let { YamlCommandReader.readWorkspaceConfig(it) }
                ?: WorkspaceConfig()

        val globs = workspaceConfig.flows ?: listOf("*")

        val matchers = globs.flatMap { glob ->
            directories.map { it.fileSystem.getPathMatcher(escapeSlashesForWindows("glob:${it.pathString}${it.fileSystem.separator}$glob")) }
        }

        val unsortedFlowFiles = flowFiles + flowFilesInDirs.filter { path ->
            matchers.any { matcher -> matcher.matches(path) }
        }.toList()

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
            val commands = validateFlowFile(it)
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

        if (workspaceConfig.local?.deterministicOrder == true) {
            println()
            println("WARNING! deterministicOrder has been deprecated in favour of executionOrder and will be removed in a future version")
            normalFlows = normalFlows.sortedBy { it.name }
        }

        // validation of media files for add media command
        allFlows.forEach {
            val commands = YamlCommandReader
                .readCommands(it)
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
        )

        logger.info("Created execution plan: $executionPlan")

        return executionPlan
    }

    private fun validateFlowFile(topLevelFlowPath: Path): List<MaestroCommand> {
        return YamlCommandReader.readCommands(topLevelFlowPath)
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
    )
}
