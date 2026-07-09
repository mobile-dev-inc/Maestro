package maestro.orchestra.debug

import com.google.common.truth.Truth.assertThat
import maestro.orchestra.DefineVariablesCommand
import maestro.orchestra.ElementSelector
import maestro.orchestra.EvalScriptCommand
import maestro.orchestra.InputTextCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.RepeatCommand
import maestro.orchestra.ScrollCommand
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.TapOnPointV2Command
import org.junit.jupiter.api.Test

class StepArtifactNamingTest {

    private fun tapOn(text: String? = null, id: String? = null) =
        MaestroCommand(command = TapOnElementCommand(selector = ElementSelector(textRegex = text, idRegex = id)))

    @Test
    fun `isNoOp is true for composite, non-visible, and empty commands`() {
        assertThat(StepArtifactNaming.isNoOp(MaestroCommand(RepeatCommand(commands = emptyList())))).isTrue()
        assertThat(StepArtifactNaming.isNoOp(MaestroCommand(DefineVariablesCommand(mapOf("a" to "b"))))).isTrue()
        assertThat(StepArtifactNaming.isNoOp(MaestroCommand())).isTrue()
        assertThat(StepArtifactNaming.isNoOp(null)).isTrue()
    }

    @Test
    fun `isNoOp is false for a visible leaf, including script commands`() {
        assertThat(StepArtifactNaming.isNoOp(MaestroCommand(ScrollCommand()))).isFalse()
        assertThat(StepArtifactNaming.isNoOp(tapOn(text = "Submit"))).isFalse()
        // evalScript/runScript are visible() == true, so they still capture (per design decision).
        assertThat(StepArtifactNaming.isNoOp(MaestroCommand(EvalScriptCommand("1")))).isFalse()
    }

    @Test
    fun `stem is 1-based and zero-padded to width 3`() {
        assertThat(StepArtifactNaming.stem(0, MaestroCommand(ScrollCommand()))).isEqualTo("step-001-scroll")
        assertThat(StepArtifactNaming.stem(41, tapOn(text = "Submit"))).isEqualTo("step-042-tapOnElement-Submit")
        assertThat(StepArtifactNaming.stem(999, MaestroCommand(ScrollCommand()))).isEqualTo("step-1000-scroll")
    }

    @Test
    fun `stem omits slug when command is null`() {
        assertThat(StepArtifactNaming.stem(2, null)).isEqualTo("step-003")
        assertThat(StepArtifactNaming.stem(2, MaestroCommand())).isEqualTo("step-003")
    }

    @Test
    fun `arg token comes from selector text, then id, then description`() {
        assertThat(StepArtifactNaming.stem(0, tapOn(text = "Submit"))).isEqualTo("step-001-tapOnElement-Submit")
        assertThat(StepArtifactNaming.stem(0, tapOn(id = "login_btn"))).isEqualTo("step-001-tapOnElement-login_btn")
    }

    @Test
    fun `arg token for point, input text keeps readable chars`() {
        assertThat(StepArtifactNaming.stem(0, MaestroCommand(TapOnPointV2Command(point = "30%,40%"))))
            .isEqualTo("step-001-tapOnPointV2-30%,40%")
        assertThat(StepArtifactNaming.stem(0, MaestroCommand(InputTextCommand(text = "sanchit"))))
            .isEqualTo("step-001-inputText-sanchit")
    }

    @Test
    fun `launchApp uses appId`() {
        assertThat(StepArtifactNaming.stem(0, MaestroCommand(LaunchAppCommand(appId = "com.example.app"))))
            .isEqualTo("step-001-launchApp-com.example.app")
    }

    @Test
    fun `sanitize maps path separators and whitespace to underscore`() {
        assertThat(StepArtifactNaming.stem(0, tapOn(text = "a/b c"))).isEqualTo("step-001-tapOnElement-a_b_c")
    }

    @Test
    fun `long slug is truncated to 40 chars`() {
        val longText = "x".repeat(80)
        val stem = StepArtifactNaming.stem(0, tapOn(text = longText))
        val slug = stem.removePrefix("step-001-")
        assertThat(slug.length).isEqualTo(40)
    }
}
