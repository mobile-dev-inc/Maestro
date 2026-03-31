package maestro.cli.validation

import com.github.michaelbull.result.getOrElse
import maestro.cli.CliError
import maestro.orchestra.workspace.WorkspaceValidationError
import maestro.orchestra.workspace.WorkspaceValidationResult
import java.io.File
import maestro.orchestra.workspace.WorkspaceValidator as OrchestraWorkspaceValidator

class WorkspaceValidator {

    fun validate(
        workspace: File,
        appId: String,
        env: Map<String, String>,
        includeTags: List<String>,
        excludeTags: List<String>,
    ): WorkspaceValidationResult {
        return OrchestraWorkspaceValidator.validate(
            workspace = workspace,
            appId = appId,
            envParameters = env,
            includeTags = includeTags,
            excludeTags = excludeTags,
        ).getOrElse { error -> throw error.toCliError() }
    }
}

private fun WorkspaceValidationError.toCliError(): CliError = CliError(
    when (this) {
        is WorkspaceValidationError.NoFlowsMatchingAppId ->
            "No flows in workspace match app ID '${appId}'. Found app IDs: ${foundIds.ifEmpty { setOf("none") }.joinToString()}"
        is WorkspaceValidationError.NameConflict ->
            "Duplicate flow name '${name}' in workspace. Each flow must have a unique name."
        is WorkspaceValidationError.SyntaxError ->
            "Workspace syntax error: ${detail}"
        is WorkspaceValidationError.InvalidFlowFile ->
            detail
        WorkspaceValidationError.EmptyWorkspace ->
            "Workspace contains no flows."
        is WorkspaceValidationError.MissingLaunchApp ->
            "Flows ${flowNames.joinToString()} are missing a launchApp command. Each flow must start with a launchApp command."
        WorkspaceValidationError.InvalidWorkspaceFile ->
            "Workspace is not a valid zip archive."
        is WorkspaceValidationError.GenericError ->
            detail
    }
)
