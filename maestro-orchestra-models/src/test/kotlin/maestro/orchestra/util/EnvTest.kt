package maestro.orchestra.util

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.random.Random
import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.DefineVariablesCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.MaestroConfig
import maestro.orchestra.util.Env.withDefaultEnvVars
import maestro.orchestra.util.Env.withEnv
import maestro.orchestra.util.Env.withInjectedShellEnvVars
import org.junit.jupiter.api.Test

class EnvTest {

    private val emptyEnv = emptyMap<String, String>()

    @Test
    fun `withDefaultEnvVars should add file name without extension`() {
        val env = emptyEnv.withDefaultEnvVars(File("myFlow.yml"))
        assertThat(env["MAESTRO_FILENAME"]).isEqualTo("myFlow")
    }

    @Test
    fun `withDefaultEnvVars should override MAESTRO_FILENAME`() {
        val env = mapOf("MAESTRO_FILENAME" to "otherFile").withDefaultEnvVars(File("myFlow.yml"))
        assertThat(env["MAESTRO_FILENAME"]).isEqualTo("myFlow")
    }

    @Test
    fun `withDefaultEnvVars should add shard and device values`() {
        val env = emptyEnv.withDefaultEnvVars(
            flowFile = File("myFlow.yml"),
            deviceId = "device-123",
            shardIndex = 1
        )
        assertThat(env["MAESTRO_DEVICE_UDID"]).isEqualTo("device-123")
        assertThat(env["MAESTRO_SHARD_ID"]).isEqualTo("2")
        assertThat(env["MAESTRO_SHARD_INDEX"]).isEqualTo("1")
    }

    @Test
    fun `withDefaultEnvVars should override shard and device values`() {
        val env = mapOf(
            "MAESTRO_DEVICE_UDID" to "old-device",
            "MAESTRO_SHARD_ID" to "99",
            "MAESTRO_SHARD_INDEX" to "98",
        ).withDefaultEnvVars(deviceId = "device-456", shardIndex = 0)
        assertThat(env["MAESTRO_DEVICE_UDID"]).isEqualTo("device-456")
        assertThat(env["MAESTRO_SHARD_ID"]).isEqualTo("1")
        assertThat(env["MAESTRO_SHARD_INDEX"]).isEqualTo("0")
    }

    @Test
    fun `withDefaultEnvVars should not add shard values when shardIndex is null`() {
        val env = emptyEnv.withDefaultEnvVars(
            flowFile = File("myFlow.yml"),
            deviceId = "device-123",
            shardIndex = null
        )
        assertThat(env["MAESTRO_FILENAME"]).isEqualTo("myFlow")
        assertThat(env["MAESTRO_DEVICE_UDID"]).isEqualTo("device-123")
        assertThat(env.containsKey("MAESTRO_SHARD_ID")).isFalse()
        assertThat(env.containsKey("MAESTRO_SHARD_INDEX")).isFalse()
    }

    @Test
    fun `withDefaultEnvVars should preserve existing shard values when shardIndex is null`() {
        // This test documents current behavior: pre-existing shell env vars pass through
        val env = mapOf(
            "MAESTRO_SHARD_ID" to "1",
            "MAESTRO_SHARD_INDEX" to "0",
        ).withDefaultEnvVars(
            flowFile = File("myFlow.yml"),
            shardIndex = null
        )
        // Pre-existing values are preserved (not removed) when shardIndex is null
        assertThat(env["MAESTRO_SHARD_ID"]).isEqualTo("1")
        assertThat(env["MAESTRO_SHARD_INDEX"]).isEqualTo("0")
    }

    @Test
    fun `withInjectedShellEnvVars only keeps MAESTRO_ vars`() {
        val env = emptyEnv.withInjectedShellEnvVars()
        assertThat(env.filterKeys { it.startsWith("MAESTRO_").not() }).isEmpty()
    }

    @Test
    fun `withInjectedShellEnvVars does not strip previous MAESTRO_ vars`() {
        val rand = Random.nextInt()
        val env = mapOf("MAESTRO_$rand" to "$rand").withInjectedShellEnvVars()
        assertThat(env["MAESTRO_$rand"]).isEqualTo("$rand")
    }

    @Test
    fun `withEnv does not affect empty env`() {
        val commands = emptyList<MaestroCommand>()

        val withEnv = commands.withEnv(emptyEnv)

        assertThat(withEnv).isEmpty()
    }

    @Test
    fun `withEnv prepends DefineVariable command`() {
        val env = mapOf("MY_ENV_VAR" to "1234")
        val applyConfig = MaestroCommand(ApplyConfigurationCommand(MaestroConfig()))
        val defineVariables = MaestroCommand(DefineVariablesCommand(env))

        val withEnv = listOf(applyConfig).withEnv(env)

        assertThat(withEnv).containsExactly(defineVariables, applyConfig)
    }
}
