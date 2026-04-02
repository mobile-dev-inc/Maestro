package maestro.orchestra.validation

import com.github.michaelbull.result.getOrElse
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
        ).getOrElse { error -> throw error.toException() }
    }
}
