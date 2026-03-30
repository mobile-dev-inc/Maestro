package maestro.orchestra.workspace

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import maestro.orchestra.MaestroCommand
import maestro.orchestra.WorkspaceConfig
import maestro.orchestra.error.InvalidFlowFile
import maestro.orchestra.error.SyntaxError as OrchestraSyntaxError
import maestro.orchestra.error.ValidationError
import maestro.orchestra.util.Env.withEnv
import maestro.orchestra.yaml.YamlCommandReader
import java.io.File
import java.nio.file.FileSystems
import java.util.zip.ZipError
import java.util.zip.ZipException
import kotlin.io.path.exists
import kotlin.io.path.name

data class ValidatedFlow(
    val filePath: String,
    val name: String,
    val commands: List<MaestroCommand>,
    val appId: String?,
)

data class WorkspaceValidationResult(
    val workspaceConfig: WorkspaceConfig,
    val flows: List<ValidatedFlow>,
)

sealed class WorkspaceValidationError(message: String) : RuntimeException(message) {
    object InvalidWorkspaceFile : WorkspaceValidationError("Workspace must be a zip archive")
    object EmptyWorkspace : WorkspaceValidationError("Workspace has no flows")
    data class NoFlowsMatchingAppId(val appId: String, val foundIds: Set<String>) :
        WorkspaceValidationError("No flows match appId=$appId; found: $foundIds")
    data class NameConflict(val name: String) : WorkspaceValidationError("Duplicate flow name: $name")
    data class SyntaxError(val detail: String) : WorkspaceValidationError("Syntax error: $detail")
    data class InvalidFlowFile(val detail: String) : WorkspaceValidationError(detail)
    data class GenericError(val detail: String) : WorkspaceValidationError(detail)
}

object WorkspaceValidator {

    fun validate(
        workspace: File,
        appId: String,
        envParameters: Map<String, String>,
        includeTags: List<String>,
        excludeTags: List<String>,
    ): Result<WorkspaceValidationResult, WorkspaceValidationError> {
        return try {
            val allFlows = mutableListOf<ValidatedFlow>()

            val workspaceConfig = FileSystems.newFileSystem(workspace.toPath()).use { fs ->
                val configPath = fs.getPath("/config.yaml").takeIf { it.exists() }
                    ?: fs.getPath("/config.yml").takeIf { it.exists() }
                WorkspaceExecutionPlanner.plan(
                    input = setOf(fs.getPath(".")),
                    includeTags = includeTags,
                    excludeTags = excludeTags,
                    config = configPath,
                ).workspaceConfig
            }

            FileSystems.newFileSystem(workspace.toPath()).use { fs ->
                val configPath = fs.getPath("/config.yaml").takeIf { it.exists() }
                    ?: fs.getPath("/config.yml").takeIf { it.exists() }
                val plan = WorkspaceExecutionPlanner.plan(
                    input = setOf(fs.getPath(".")),
                    includeTags = includeTags,
                    excludeTags = excludeTags,
                    config = configPath,
                )
                (plan.flowsToRun + plan.sequence.flows).forEach { path ->
                    val commands = try {
                        YamlCommandReader.readCommands(path).withEnv(envParameters)
                    } catch (e: InvalidFlowFile) {
                        throw OrchestraSyntaxError("Invalid flow file: ${e.message}")
                    }
                    val config = YamlCommandReader.getConfig(commands)
                    val flowName = config?.name ?: path.name.removeSuffix(".yaml")
                    allFlows.add(ValidatedFlow(path.toString(), flowName, commands, config?.appId))
                }
            }

            if (allFlows.isEmpty()) return Err(WorkspaceValidationError.EmptyWorkspace)

            val matching = allFlows.filter { it.appId == appId }
            if (matching.isEmpty()) {
                val found = allFlows.mapNotNull { it.appId }.toSet()
                return Err(WorkspaceValidationError.NoFlowsMatchingAppId(appId, found))
            }

            matching.groupBy { it.name }.entries.find { (_, v) -> v.size > 1 }?.let { (name, _) ->
                return Err(WorkspaceValidationError.NameConflict(name))
            }

            Ok(WorkspaceValidationResult(workspaceConfig, matching))
        } catch (_: ZipException) {
            Err(WorkspaceValidationError.InvalidWorkspaceFile)
        } catch (_: ZipError) {
            Err(WorkspaceValidationError.InvalidWorkspaceFile)
        } catch (e: OrchestraSyntaxError) {
            Err(WorkspaceValidationError.SyntaxError(e.message ?: ""))
        } catch (e: maestro.orchestra.error.InvalidFlowFile) {
            Err(WorkspaceValidationError.InvalidFlowFile(e.message ?: ""))
        } catch (e: ValidationError) {
            Err(WorkspaceValidationError.GenericError(e.message ?: ""))
        }
    }
}
