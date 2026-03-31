package maestro.cli.validation

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import maestro.cli.CliError
import maestro.orchestra.workspace.WorkspaceValidationError
import maestro.orchestra.workspace.WorkspaceValidationResult
import maestro.orchestra.WorkspaceConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import maestro.orchestra.workspace.WorkspaceValidator as OrchestraWorkspaceValidator

class WorkspaceValidatorTest {

    private lateinit var validator: WorkspaceValidator

    private val dummyWorkspace = File("workspace.zip")
    private val dummyAppId = "com.example.app"
    private val dummyEnv = emptyMap<String, String>()
    private val dummyIncludeTags = emptyList<String>()
    private val dummyExcludeTags = emptyList<String>()

    @BeforeEach
    fun setUp() {
        validator = WorkspaceValidator()
        mockkObject(OrchestraWorkspaceValidator)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(OrchestraWorkspaceValidator)
    }

    @Test
    fun `returns WorkspaceValidationResult on success`() {
        val expectedResult = WorkspaceValidationResult(
            workspaceConfig = WorkspaceConfig(),
            flows = emptyList(),
        )
        every {
            OrchestraWorkspaceValidator.validate(
                workspace = dummyWorkspace,
                appId = dummyAppId,
                envParameters = dummyEnv,
                includeTags = dummyIncludeTags,
                excludeTags = dummyExcludeTags,
            )
        } returns Ok(expectedResult)

        val result = validator.validate(
            workspace = dummyWorkspace,
            appId = dummyAppId,
            env = dummyEnv,
            includeTags = dummyIncludeTags,
            excludeTags = dummyExcludeTags,
        )

        assertThat(result).isEqualTo(expectedResult)
    }

    @Test
    fun `throws CliError with matching message for NoFlowsMatchingAppId`() {
        every {
            OrchestraWorkspaceValidator.validate(any(), any(), any(), any(), any())
        } returns Err(WorkspaceValidationError.NoFlowsMatchingAppId("com.example.app", setOf("com.other.app")))

        val error = assertThrows<CliError> {
            validator.validate(dummyWorkspace, dummyAppId, dummyEnv, dummyIncludeTags, dummyExcludeTags)
        }
        assertThat(error.message).contains("No flows in workspace match app ID 'com.example.app'")
        assertThat(error.message).contains("com.other.app")
    }

    @Test
    fun `throws CliError with none when NoFlowsMatchingAppId has empty found ids`() {
        every {
            OrchestraWorkspaceValidator.validate(any(), any(), any(), any(), any())
        } returns Err(WorkspaceValidationError.NoFlowsMatchingAppId("com.example.app", emptySet()))

        val error = assertThrows<CliError> {
            validator.validate(dummyWorkspace, dummyAppId, dummyEnv, dummyIncludeTags, dummyExcludeTags)
        }
        assertThat(error.message).contains("none")
    }

    @Test
    fun `throws CliError for NameConflict`() {
        every {
            OrchestraWorkspaceValidator.validate(any(), any(), any(), any(), any())
        } returns Err(WorkspaceValidationError.NameConflict("loginFlow"))

        val error = assertThrows<CliError> {
            validator.validate(dummyWorkspace, dummyAppId, dummyEnv, dummyIncludeTags, dummyExcludeTags)
        }
        assertThat(error.message).contains("Duplicate flow name 'loginFlow'")
    }

    @Test
    fun `throws CliError for SyntaxError`() {
        every {
            OrchestraWorkspaceValidator.validate(any(), any(), any(), any(), any())
        } returns Err(WorkspaceValidationError.SyntaxError("unexpected token"))

        val error = assertThrows<CliError> {
            validator.validate(dummyWorkspace, dummyAppId, dummyEnv, dummyIncludeTags, dummyExcludeTags)
        }
        assertThat(error.message).contains("Workspace syntax error: unexpected token")
    }

    @Test
    fun `throws CliError for InvalidFlowFile`() {
        every {
            OrchestraWorkspaceValidator.validate(any(), any(), any(), any(), any())
        } returns Err(WorkspaceValidationError.InvalidFlowFile("bad flow content"))

        val error = assertThrows<CliError> {
            validator.validate(dummyWorkspace, dummyAppId, dummyEnv, dummyIncludeTags, dummyExcludeTags)
        }
        assertThat(error.message).contains("bad flow content")
    }

    @Test
    fun `throws CliError for EmptyWorkspace`() {
        every {
            OrchestraWorkspaceValidator.validate(any(), any(), any(), any(), any())
        } returns Err(WorkspaceValidationError.EmptyWorkspace)

        val error = assertThrows<CliError> {
            validator.validate(dummyWorkspace, dummyAppId, dummyEnv, dummyIncludeTags, dummyExcludeTags)
        }
        assertThat(error.message).contains("Workspace contains no flows")
    }

    @Test
    fun `throws CliError for MissingLaunchApp`() {
        every {
            OrchestraWorkspaceValidator.validate(any(), any(), any(), any(), any())
        } returns Err(WorkspaceValidationError.MissingLaunchApp(listOf("flow1", "flow2")))

        val error = assertThrows<CliError> {
            validator.validate(dummyWorkspace, dummyAppId, dummyEnv, dummyIncludeTags, dummyExcludeTags)
        }
        assertThat(error.message).contains("flow1, flow2")
        assertThat(error.message).contains("missing a launchApp command")
    }

    @Test
    fun `throws CliError for InvalidWorkspaceFile`() {
        every {
            OrchestraWorkspaceValidator.validate(any(), any(), any(), any(), any())
        } returns Err(WorkspaceValidationError.InvalidWorkspaceFile)

        val error = assertThrows<CliError> {
            validator.validate(dummyWorkspace, dummyAppId, dummyEnv, dummyIncludeTags, dummyExcludeTags)
        }
        assertThat(error.message).contains("Workspace is not a valid zip archive")
    }

    @Test
    fun `throws CliError for GenericError`() {
        every {
            OrchestraWorkspaceValidator.validate(any(), any(), any(), any(), any())
        } returns Err(WorkspaceValidationError.GenericError("something went wrong"))

        val error = assertThrows<CliError> {
            validator.validate(dummyWorkspace, dummyAppId, dummyEnv, dummyIncludeTags, dummyExcludeTags)
        }
        assertThat(error.message).contains("something went wrong")
    }
}
