package maestro.orchestra.validation

import maestro.orchestra.workspace.WorkspaceValidationError

/**
 * @param message Short summary of the validation failure, suitable for a status line.
 * @param error   The structured error variant.
 * @param detail  Optional rich block (snippet + caret + human message + docs) when the
 *   underlying error was a flow parser error. Suitable for a `<pre>` panel on the web.
 */
class WorkspaceValidationException(
    message: String,
    val error: WorkspaceValidationError,
    val detail: String? = null,
) : RuntimeException(message)

fun WorkspaceValidationError.toException(): WorkspaceValidationException {
    val message = when (this) {
        is WorkspaceValidationError.NoFlowsMatchingAppId ->
            "No flows in workspace match app ID '${appId}'. Found app IDs: ${foundIds.ifEmpty { setOf("none") }.joinToString()}"
        is WorkspaceValidationError.NameConflict ->
            "Duplicate flow name '${name}' in workspace. Each flow must have a unique name."
        is WorkspaceValidationError.SyntaxError ->
            "Workspace syntax error: ${this.message}"
        is WorkspaceValidationError.InvalidFlowFile ->
            this.message
        WorkspaceValidationError.EmptyWorkspace ->
            "Workspace contains no flows."
        is WorkspaceValidationError.MissingLaunchApp ->
            "Flows ${flowNames.joinToString()} are missing a launchApp command. Each flow must start with a launchApp command."
        WorkspaceValidationError.InvalidWorkspaceFile ->
            "Workspace is not a valid zip archive."
        is WorkspaceValidationError.GenericError ->
            this.message
    }
    val detail = when (this) {
        is WorkspaceValidationError.SyntaxError -> detail
        is WorkspaceValidationError.GenericError -> detail
        else -> null
    }
    return WorkspaceValidationException(message = message, error = this, detail = detail)
}
