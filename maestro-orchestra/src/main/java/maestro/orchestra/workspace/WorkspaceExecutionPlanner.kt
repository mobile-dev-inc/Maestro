package maestro.orchestra.workspace

import maestro.orchestra.MaestroCommand
import maestro.orchestra.MaestroConfig
import maestro.orchestra.WorkspaceConfig
import maestro.orchestra.error.ValidationError
import maestro.orchestra.workspace.ExecutionOrderPlanner.getFlowsToRunInSequence
import maestro.orchestra.yaml.YamlCommandReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.streams.toList

object WorkspaceExecutionPlanner {

    fun plan(
        input: Path,
        includeTags: List<String>,
        excludeTags: List<String>,
    ): ExecutionPlan {
        if (input.notExists()) {
            throw ValidationError("""
                Flow path does not exist: ${input.absolutePathString()}
            """.trimIndent())
        }

        if (input.isRegularFile()) {
            validateFlowFile(input)
            return ExecutionPlan(
                flowsToRun = listOf(input),
            )
        }

        // retrieve all Flow files

        val unfilteredFlowFiles = Files.walk(input).filter { isFlowFile(it) }.toList()
        if (unfilteredFlowFiles.isEmpty()) {
            throw ValidationError("""
                Flow directory does not contain any Flow files: ${input.absolutePathString()}
            """.trimIndent())
        }

        // Filter flows based on flows config

        val workspaceConfig = findConfigFile(input)
            ?.let { YamlCommandReader.readWorkspaceConfig(it) }
            ?: WorkspaceConfig()

        val globs = workspaceConfig.flows ?: listOf("*")

        val matchers = globs
            .map {
                input.fileSystem.getPathMatcher("glob:${input.pathString}/$it")
            }

        val unsortedFlowFiles = unfilteredFlowFiles
            .filter { path ->
                matchers.any { matcher -> matcher.matches(path) }
            }
            .toList()

        if (unsortedFlowFiles.isEmpty()) {
            if ("*" == globs.singleOrNull()) {
                throw ValidationError("""
                    Top-level directory does not contain any Flows: ${input.absolutePathString()}
                    To configure Maestro to run Flows in subdirectories, check out the following resources:
                      * https://maestro.mobile.dev/cli/test-suites-and-reports#inclusion-patterns
                      * https://blog.mobile.dev/maestro-best-practices-structuring-your-test-suite-54ec390c5c82
                """.trimIndent())
            } else {
                throw ValidationError("Flow inclusion pattern(s) did not match any Flow files:\n${toYamlListString(globs)}")
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
            throw ValidationError("Include / Exclude tags did not match any Flows:\n\nInclude Tags:\n${toYamlListString(allIncludeTags)}\n\nExclude Tags:\n${toYamlListString(allExcludeTags)}")
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
            val commands = YamlCommandReader.readCommands(it).mapNotNull { maestroCommand ->  maestroCommand.addMediaCommand }
            val mediaPaths = commands.flatMap { addMediaCommand -> addMediaCommand.mediaPaths }
            YamlCommandsPathValidator.validatePathsExistInWorkspace(input, it, mediaPaths)
        }

        return ExecutionPlan(
            flowsToRun = normalFlows,
            FlowSequence(
                flowsToRunInSequence,
                workspaceConfig.executionOrder?.continueOnFailure
            )
        )
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

    data class FlowSequence(
        val flows: List<Path>,
        val continueOnFailure: Boolean? = true,
    )

    data class ExecutionPlan(
        val flowsToRun: List<Path>,
        val sequence: FlowSequence? = null,
    )
}
