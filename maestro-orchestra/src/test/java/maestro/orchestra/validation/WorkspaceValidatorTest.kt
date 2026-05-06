package maestro.orchestra.validation

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import maestro.orchestra.WorkspaceConfig
import maestro.orchestra.workspace.WorkspaceValidationError
import maestro.orchestra.workspace.WorkspaceValidationResult
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
    fun `throws WorkspaceValidationException for NoFlowsMatchingAppId`() {
        every {
            OrchestraWorkspaceValidator.validate(any(), any(), any(), any(), any())
        } returns Err(WorkspaceValidationError.NoFlowsMatchingAppId("com.example.app", setOf("com.other.app")))

        val error = assertThrows<WorkspaceValidationException> {
            validator.validate(dummyWorkspace, dummyAppId, dummyEnv, dummyIncludeTags, dummyExcludeTags)
        }
        assertThat(error.message).contains("No flows in workspace match app ID 'com.example.app'")
        assertThat(error.message).contains("com.other.app")
    }

    @Test
    fun `throws WorkspaceValidationException with none when NoFlowsMatchingAppId has empty found ids`() {
        every {
            OrchestraWorkspaceValidator.validate(any(), any(), any(), any(), any())
        } returns Err(WorkspaceValidationError.NoFlowsMatchingAppId("com.example.app", emptySet()))

        val error = assertThrows<WorkspaceValidationException> {
            validator.validate(dummyWorkspace, dummyAppId, dummyEnv, dummyIncludeTags, dummyExcludeTags)
        }
        assertThat(error.message).contains("none")
    }

    @Test
    fun `throws WorkspaceValidationException for NameConflict`() {
        every {
            OrchestraWorkspaceValidator.validate(any(), any(), any(), any(), any())
        } returns Err(WorkspaceValidationError.NameConflict("loginFlow"))

        val error = assertThrows<WorkspaceValidationException> {
            validator.validate(dummyWorkspace, dummyAppId, dummyEnv, dummyIncludeTags, dummyExcludeTags)
        }
        assertThat(error.message).contains("Duplicate flow name 'loginFlow'")
    }

    @Test
    fun `throws WorkspaceValidationException for SyntaxError`() {
        every {
            OrchestraWorkspaceValidator.validate(any(), any(), any(), any(), any())
        } returns Err(WorkspaceValidationError.SyntaxError("unexpected token"))

        val error = assertThrows<WorkspaceValidationException> {
            validator.validate(dummyWorkspace, dummyAppId, dummyEnv, dummyIncludeTags, dummyExcludeTags)
        }
        assertThat(error.message).contains("Workspace syntax error: unexpected token")
    }

    @Test
    fun `throws WorkspaceValidationException for InvalidFlowFile`() {
        every {
            OrchestraWorkspaceValidator.validate(any(), any(), any(), any(), any())
        } returns Err(WorkspaceValidationError.InvalidFlowFile("bad flow content"))

        val error = assertThrows<WorkspaceValidationException> {
            validator.validate(dummyWorkspace, dummyAppId, dummyEnv, dummyIncludeTags, dummyExcludeTags)
        }
        assertThat(error.message).contains("bad flow content")
    }

    @Test
    fun `throws WorkspaceValidationException for EmptyWorkspace`() {
        every {
            OrchestraWorkspaceValidator.validate(any(), any(), any(), any(), any())
        } returns Err(WorkspaceValidationError.EmptyWorkspace)

        val error = assertThrows<WorkspaceValidationException> {
            validator.validate(dummyWorkspace, dummyAppId, dummyEnv, dummyIncludeTags, dummyExcludeTags)
        }
        assertThat(error.message).contains("Workspace contains no flows")
    }

    @Test
    fun `throws WorkspaceValidationException for MissingLaunchApp`() {
        every {
            OrchestraWorkspaceValidator.validate(any(), any(), any(), any(), any())
        } returns Err(WorkspaceValidationError.MissingLaunchApp(listOf("flow1", "flow2")))

        val error = assertThrows<WorkspaceValidationException> {
            validator.validate(dummyWorkspace, dummyAppId, dummyEnv, dummyIncludeTags, dummyExcludeTags)
        }
        assertThat(error.message).contains("flow1, flow2")
        assertThat(error.message).contains("missing a launchApp command")
    }

    @Test
    fun `throws WorkspaceValidationException for InvalidWorkspaceFile`() {
        every {
            OrchestraWorkspaceValidator.validate(any(), any(), any(), any(), any())
        } returns Err(WorkspaceValidationError.InvalidWorkspaceFile)

        val error = assertThrows<WorkspaceValidationException> {
            validator.validate(dummyWorkspace, dummyAppId, dummyEnv, dummyIncludeTags, dummyExcludeTags)
        }
        assertThat(error.message).contains("Workspace is not a valid zip archive")
    }

    @Test
    fun `throws WorkspaceValidationException for GenericError`() {
        every {
            OrchestraWorkspaceValidator.validate(any(), any(), any(), any(), any())
        } returns Err(WorkspaceValidationError.GenericError("something went wrong"))

        val error = assertThrows<WorkspaceValidationException> {
            validator.validate(dummyWorkspace, dummyAppId, dummyEnv, dummyIncludeTags, dummyExcludeTags)
        }
        assertThat(error.message).contains("something went wrong")
    }

    @Test
    fun `propagates detail through SyntaxError to WorkspaceValidationException detail`() {
        val richBlock = "       1 | bad: yaml\n           ^\n\n  Boom."
        every {
            OrchestraWorkspaceValidator.validate(any(), any(), any(), any(), any())
        } returns Err(WorkspaceValidationError.SyntaxError(message = "Invalid command at /flow.yaml:1:1", detail = richBlock))

        val error = assertThrows<WorkspaceValidationException> {
            validator.validate(dummyWorkspace, dummyAppId, dummyEnv, dummyIncludeTags, dummyExcludeTags)
        }
        assertThat(error.detail).isEqualTo(richBlock)
    }

    @Test
    fun `WorkspaceValidationException detail is null for variants without richDetail`() {
        every {
            OrchestraWorkspaceValidator.validate(any(), any(), any(), any(), any())
        } returns Err(WorkspaceValidationError.NameConflict("loginFlow"))

        val error = assertThrows<WorkspaceValidationException> {
            validator.validate(dummyWorkspace, dummyAppId, dummyEnv, dummyIncludeTags, dummyExcludeTags)
        }
        assertThat(error.detail).isNull()
    }
}
