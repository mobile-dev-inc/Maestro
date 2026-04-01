package maestro.orchestra.validation

import maestro.orchestra.workspace.WorkspaceValidationError

class WorkspaceValidationException(message: String, val error: WorkspaceValidationError) : RuntimeException(message)

fun WorkspaceValidationError.toException(): WorkspaceValidationException = WorkspaceValidationException(
    message = when (this) {
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
    },
    error = this,
)
