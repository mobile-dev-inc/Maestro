package maestro.orchestra.workspace

import maestro.orchestra.MaestroConfig
import maestro.orchestra.WorkspaceConfig
import maestro.orchestra.yaml.YamlCommandReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.pathString
import kotlin.streams.toList

object WorkspaceExecutionPlanner {

    fun plan(
        input: Path,
        includeTags: List<String>,
        excludeTags: List<String>,
    ): ExecutionPlan {
        if (input.isRegularFile()) {
            return ExecutionPlan(
                flowsToRun = listOf(input),
            )
        }

        val workspaceConfig = findConfigFile(input)
            ?.let { YamlCommandReader.readWorkspaceConfig(it) }
            ?: WorkspaceConfig()

        val globs = workspaceConfig.flows ?: listOf("*")

        val matchers = globs
            .map {
                input.fileSystem.getPathMatcher("glob:${input.pathString}/$it")
            }

        val globalIncludeTags = workspaceConfig.includeTags?.toList() ?: emptyList()
        val globalExcludeTags = workspaceConfig.excludeTags?.toList() ?: emptyList()

        // filter out all Flow files
        val unsortedFlowFiles = Files.walk(input)
            .filter { path ->
                matchers.any { matcher -> matcher.matches(path) }
            }
            .toList()
            .filter { it.nameWithoutExtension != "config" }
            .filter {
                it.isRegularFile()
                    && (
                    it.name.endsWith(".yaml")
                        || it.name.endsWith(".yml")
                    )
            }

        // retrieve config for each Flow file
        val configPerFlowFile = unsortedFlowFiles.associateWith {
            val commands = YamlCommandReader.readCommands(it)
            YamlCommandReader.getConfig(commands)
        }

        // filter out all Flows not matching tags if present
        val allFlows = unsortedFlowFiles.filter {
            val config = configPerFlowFile[it]
            val tags = config?.tags ?: emptyList()

            (includeTags.isEmpty() || tags.any(includeTags::contains))
                && (globalIncludeTags.isEmpty() || tags.any(globalIncludeTags::contains))
                && (excludeTags.isEmpty() || !tags.any(excludeTags::contains))
                && (globalExcludeTags.isEmpty() || !tags.any(globalExcludeTags::contains))
        }

        val flowsToRunInSequence = getFlowsToRunInSequence(allFlows, configPerFlowFile, workspaceConfig) ?: emptyList()
        var normalFlows = allFlows - flowsToRunInSequence.toSet()

        if (workspaceConfig.local?.deterministicOrder == true) {
            println()
            println("WARNING! deterministicOrder has been deprecated in favour of executionOrder and will be removed in a future version")
            normalFlows = normalFlows.sortedBy { it.name }
        }

        return ExecutionPlan(
            flowsToRun = normalFlows,
            FlowSequence(
                flowsToRunInSequence,
                workspaceConfig.executionOrder?.continueOnFailure
            )
        )
    }

    private fun findConfigFile(input: Path): Path? {
        return input.resolve("config.yaml")
            .takeIf { it.exists() }
            ?: input.resolve("config.yml")
                .takeIf { it.exists() }
    }

    private fun parseFileName(file: Path): String {
        return file.fileName.toString().substringBeforeLast(".")
    }

    private fun getFlowsToRunInSequence(
        list: List<Path>,
        configPerFlowFile: Map<Path, MaestroConfig?>,
        workspaceConfig: WorkspaceConfig)
    : List<Path>? {
        if (workspaceConfig.executionOrder?.flowsOrder?.isNotEmpty() == true) {
            val flowsOrder = workspaceConfig.executionOrder?.flowsOrder!!.distinct()

            return flowsOrder.map { flowName ->
                list.find {
                    val config = configPerFlowFile[it]
                    val name = config?.name ?: parseFileName(it)
                    flowName == name
                } ?: error("Could not find Flow with name $flowName")
            }
        }
        return null
    }

    data class FlowSequence(
        val flows: List<Path>,
        val continueOnFailure: Boolean? = true
    )

    data class ExecutionPlan(
        val flowsToRun: List<Path>,
        val sequence: FlowSequence? = null
    )

}
