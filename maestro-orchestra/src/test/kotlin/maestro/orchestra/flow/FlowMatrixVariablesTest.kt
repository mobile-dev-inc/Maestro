package maestro.orchestra.flow

import com.google.common.truth.Truth.assertThat
import maestro.MaestroException
import maestro.orchestra.devicecore.DeviceCoreEvidence
import maestro.orchestra.devicecore.FakeDeviceProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/**
 * Tier-2 recovery of the deleted `maestro-test` `IntegrationTest`'s variables/env family (cases
 * 028, 057, 058, 060, 077, 093, 127, 137 — see git history at c1538ee7^:maestro-test/src/test/kotlin/maestro/test/IntegrationTest.kt).
 * Arrange changes from seeding a `FakeDriver` screen to seeding evidence/outcomes at the
 * [FakeDeviceProvider] seam; assertions port from the old driver's recorded `Event` stream to the
 * equivalent provider state.
 *
 * GROUND TRUTH (verified empirically, see task-7-report.md): [maestro.orchestra.devicecore.DeviceGateway]
 * only wires `connect`/`close`/`launchApp`/`tap`/`assertVisibility` to device-core as of this task;
 * every other verb (`inputText`, `openLink`, `setLocation`, `takeScreenshot`, `startScreenRecording`,
 * ...) is a declared-but-unbuilt roadmap verb that throws [MaestroException.NotImplemented]. Cases
 * 028/057/058/060/077/137 exercise one of those unwired verbs, so `Orchestra.runFlow` throws instead
 * of returning a `FlowResult` — the default `onCommandFailed` rethrows. Those cases are recovered as
 * far as the current gateway allows: whatever ran before the unwired verb (launchApp, taps, asserts)
 * is asserted on the provider, and the thrown [MaestroException.NotImplemented] is asserted to name
 * the expected still-unwired capability, which also proves the case's env/variable interpolation
 * evaluated cleanly (a bad interpolation would throw a different, earlier exception). Cases 093 and
 * 127 touch no unwired verb and recover with a full `result.success` assertion, as originally written.
 */
class FlowMatrixVariablesTest {

    // Mirrors the deleted IntegrationTest's tearDown (git history): allocateCommandArtifact() has no
    // artifactsDir configured in this harness, so takeScreenshotCommand's fallback File(...) sink
    // still gets opened (and the interpolated filename written) before driver.takeScreenshot() throws
    // NotImplemented -- same CWD-relative-file behavior the original test cleaned up after itself.
    @AfterEach
    fun tearDown() {
        File("137_shard_device_env_vars_test-device_shard1_idx0.png").delete()
    }

    @Test
    fun `Case 028 - Env`() {
        // Old arrange seeded a screen with a button element; new arrange seeds the answer at the
        // provider: any selector naming "button" resolves visible, everything else is absent -- so
        // the two assertVisible(button_*) pass and the two assertNotVisible(nonExistent*) pass too.
        val provider = FakeDeviceProvider { sel ->
            if (sel.toString().contains("button")) DeviceCoreEvidence.resolvedVisible(sel.toString())
            else DeviceCoreEvidence.absent(sel.toString())
        }

        val exception = assertThrows<MaestroException.NotImplemented> {
            FlowMatrix.run(
                "028_env",
                provider = provider,
                env = mapOf(
                    "APP_ID" to "com.example.app",
                    "BUTTON_ID" to "button_id",
                    "BUTTON_TEXT" to "button_text",
                    "PASSWORD" to "testPassword",
                    "NON_EXISTENT_TEXT" to "nonExistentText",
                    "NON_EXISTENT_ID" to "nonExistentId",
                    "URL" to "secretUrl",
                    "LAT" to "37.82778",
                    "LNG" to "-122.48167",
                ),
            )
        }

        // Old asserts a full Event stream through inputText/openLink/setLocation/startRecording.
        // inputText is not yet a wired DeviceGateway verb, so the flow throws there; what IS wired
        // recovers fully -- env-substituted launchApp plus the two env-selector taps and four
        // env-selector asserts all ran before the throw.
        assertThat(exception.message).contains("inputText")
        assertThat(provider.launchedApps).containsExactly("com.example.app")
        assertThat(provider.tapCount).isEqualTo(2)
    }

    @Test
    fun `Case 057 - Pass inner env variables to runFlow`() {
        // Old asserts Event.InputText("Inner Parameter" / "Outer Parameter" / "Overridden Parameter")
        // -- inputText is not a wired DeviceGateway verb, so recovery stops at proving the nested
        // runFlow + env-override interpolation reaches command execution without erroring itself.
        val exception = assertThrows<MaestroException.NotImplemented> {
            FlowMatrix.run("057_runFlow_env", env = mapOf("OUTER_ENV" to "Outer Parameter"))
        }
        assertThat(exception.message).contains("inputText")
    }

    @Test
    fun `Case 058 - Inline env parameters`() {
        // Old asserts Event.InputText("Inline Parameter" / "Overridden Parameter"); same inputText
        // gap as Case 057.
        val exception = assertThrows<MaestroException.NotImplemented> {
            FlowMatrix.run("058_inline_env")
        }
        assertThat(exception.message).contains("inputText")
    }

    @Test
    fun `Case 060 - Pass env param to an env param`() {
        // Old asserts Event.InputText("Value") x3 (env passed through two levels of runFlow env);
        // same inputText gap.
        val exception = assertThrows<MaestroException.NotImplemented> {
            FlowMatrix.run("060_pass_env_to_env", env = mapOf("PARAM" to "Value"))
        }
        assertThat(exception.message).contains("inputText")
    }

    @Test
    fun `Case 077 - Env special characters`() {
        // Old asserts two Event.InputText with the same special-character payload (outer literal +
        // inner env-file value); same inputText gap. Recovery proves interpolating a value full of
        // regex/YAML-hostile characters doesn't itself throw before reaching the command.
        val exception = assertThrows<MaestroException.NotImplemented> {
            FlowMatrix.run(
                "077_env_special_characters",
                env = mapOf("OUTER" to "!@#\$&*()_+{}|:\"<>?[]\\\\;',./"),
            )
        }
        assertThat(exception.message).contains("inputText")
    }

    @Test
    fun `Case 093 - JS default values`() {
        val provider = FakeDeviceProvider { DeviceCoreEvidence.absent(it.toString()) }
        val result = FlowMatrix.run("093_js_default_value", provider = provider)
        assertThat(result.success).isTrue()
        // old assert: Event.LaunchApp("com.example.default") -> now the launched app recorded at the provider
        assertThat(provider.launchedApps).contains("com.example.default")
    }

    @Test
    fun `Case 127 GraalJS - Environment variables should be isolated between flows`() {
        // No device verb at all (assertTrue/evalScript/runScript/runFlow are all JS-engine / control
        // flow); fully recovers with the same "should succeed" shape as the original.
        val result = FlowMatrix.run("127_env_vars_isolation_graaljs")
        assertThat(result.success).isTrue()
    }

    @Test
    fun `Case 137 - Shard and device env vars`() {
        // Old readCommands passed deviceId="test-device", shardIndex=0 (-> MAESTRO_SHARD_ID=1,
        // MAESTRO_SHARD_INDEX=0); FlowMatrix.run has no deviceId/shardIndex parameter, but
        // Env.withDefaultEnvVars defaults shardIndex to 0 when absent, producing the identical
        // MAESTRO_SHARD_ID/INDEX values, and MAESTRO_DEVICE_UDID isn't itself an internal-only var
        // (only MAESTRO_SHARD_ID/INDEX are), so passing it through `env` survives withDefaultEnvVars.
        val provider = FakeDeviceProvider { DeviceCoreEvidence.absent(it.toString()) }
        val exception = assertThrows<MaestroException.NotImplemented> {
            FlowMatrix.run(
                "137_shard_device_env_vars",
                provider = provider,
                env = mapOf("MAESTRO_DEVICE_UDID" to "test-device"),
            )
        }
        // takeScreenshot itself is not a wired DeviceGateway verb, so the actual screen capture never
        // happens -- but takeScreenshotCommand opens its output sink (via allocateCommandArtifact's
        // fallback File(...)) BEFORE calling driver.takeScreenshot(), so the interpolated filename is
        // still created on disk. That recovers the old test's exact assertion in full:
        // assert(File("137_shard_device_env_vars_test-device_shard1_idx0.png").exists()), proving
        // MAESTRO_DEVICE_UDID / MAESTRO_SHARD_ID / MAESTRO_SHARD_INDEX all resolved correctly.
        assertThat(exception.message).contains("takeScreenshot")
        assertThat(provider.launchedApps).containsExactly("com.example.app")
        assertThat(File("137_shard_device_env_vars_test-device_shard1_idx0.png").exists()).isTrue()
    }
}
