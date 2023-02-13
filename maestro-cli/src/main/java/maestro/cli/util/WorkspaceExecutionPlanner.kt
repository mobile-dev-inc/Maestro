package maestro.cli.util

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

        val entries = Files.walk(input)
            .filter { path ->
                matchers.any { matcher -> matcher.matches(path) }
            }
            .toList()

        return ExecutionPlan(
            flowsToRun = entries
                .filter { it.nameWithoutExtension != "config" }
                .filter {
                    it.isRegularFile()
                        && (
                        it.name.endsWith(".yaml")
                            || it.name.endsWith(".yml")
                        )
                }
                .filter {
                    val commands = YamlCommandReader.readCommands(it)
                    val config = YamlCommandReader.getConfig(commands)
                    val tags = config?.tags ?: emptyList()

                    (includeTags.isEmpty() || tags.any(includeTags::contains))
                        && (excludeTags.isEmpty() || !tags.any(excludeTags::contains))
                }
        )
    }

    private fun findConfigFile(input: Path): Path? {
        return input.resolve("config.yaml")
            .takeIf { it.exists() }
            ?: input.resolve("config.yml")
                .takeIf { it.exists() }
    }

    data class ExecutionPlan(
        val flowsToRun: List<Path>,
    )

}