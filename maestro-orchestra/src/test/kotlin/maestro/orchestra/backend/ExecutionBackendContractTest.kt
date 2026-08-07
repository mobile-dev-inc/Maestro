package maestro.orchestra.backend

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import maestro.DeviceInfo
import maestro.FindElementResult
import maestro.TreeNode
import maestro.ViewHierarchy
import maestro.device.Platform
import maestro.orchestra.Command
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import maestro.orchestra.TapOnElementCommand
import org.junit.jupiter.api.Test

/**
 * Compile-level contract test. Pins the ExecutionBackend seam's type surface before any
 * behavior exists. Not a behavior test: the no-op ExecutionBackend impl below asserts nothing
 * about device interaction, it only proves the interface is implementable and callable as
 * specified in the task brief.
 */
class ExecutionBackendContractTest {

    @Test
    fun `CommandExecutionResult and StepTrace fields are accessible`() {
        val result = CommandExecutionResult(
            mutating = true,
            trace = StepTrace(
                verdict = Verdict.PASS,
                chosenElement = ChosenElement(0, 0, 10, 10, 5, 5, "OK", "btn_ok", null),
            ),
        )

        assertThat(result.mutating).isTrue()
        assertThat(result.trace).isNotNull()
        assertThat(result.trace!!.verdict).isEqualTo(Verdict.PASS)
        assertThat(result.trace!!.declined).isFalse()
        assertThat(result.trace!!.declinedReason).isNull()
        assertThat(result.trace!!.evidence).isEmpty()

        val chosenElement = result.trace!!.chosenElement
        assertThat(chosenElement).isNotNull()
        assertThat(chosenElement!!.x).isEqualTo(0)
        assertThat(chosenElement.y).isEqualTo(0)
        assertThat(chosenElement.width).isEqualTo(10)
        assertThat(chosenElement.height).isEqualTo(10)
        assertThat(chosenElement.centerX).isEqualTo(5)
        assertThat(chosenElement.centerY).isEqualTo(5)
        assertThat(chosenElement.text).isEqualTo("OK")
        assertThat(chosenElement.resourceId).isEqualTo("btn_ok")
        assertThat(chosenElement.index).isNull()
    }

    @Test
    fun `BackendContext fields are accessible`() {
        val context = BackendContext(lookupTimeoutMs = 17000, optionalLookupTimeoutMs = 7000)

        assertThat(context.lookupTimeoutMs).isEqualTo(17000)
        assertThat(context.optionalLookupTimeoutMs).isEqualTo(7000)
    }

    @Test
    fun `a no-op ExecutionBackend implementation compiles and is callable`() = runBlocking {
        val backend = object : ExecutionBackend {
            override fun open(appId: String?) {}

            override fun close() {}

            override suspend fun execute(command: Command, context: BackendContext): CommandExecutionResult {
                return CommandExecutionResult(mutating = false)
            }

            override fun viewHierarchy(excludeKeyboardElements: Boolean): ViewHierarchy {
                return ViewHierarchy(TreeNode())
            }

            override suspend fun findElement(
                selector: ElementSelector,
                optional: Boolean,
                timeoutMs: Long?,
                context: BackendContext,
            ): FindElementResult = error("no-op backend")

            override suspend fun evaluateCondition(
                condition: Condition?,
                commandOptional: Boolean,
                timeoutMs: Long?,
                context: BackendContext,
            ): Boolean = false

            override val deviceInfo: DeviceInfo
                get() = DeviceInfo(
                    platform = Platform.ANDROID,
                    widthPixels = 1080,
                    heightPixels = 1920,
                    widthGrid = 1080,
                    heightGrid = 1920,
                )
        }

        backend.open(appId = "com.example.app")

        val result = backend.execute(
            command = TapOnElementCommand(selector = ElementSelector()),
            context = BackendContext(lookupTimeoutMs = 17000, optionalLookupTimeoutMs = 7000),
        )
        assertThat(result.mutating).isFalse()

        val hierarchy = backend.viewHierarchy()
        assertThat(hierarchy).isNotNull()

        assertThat(backend.deviceInfo.platform).isEqualTo(Platform.ANDROID)

        backend.close()
    }
}
