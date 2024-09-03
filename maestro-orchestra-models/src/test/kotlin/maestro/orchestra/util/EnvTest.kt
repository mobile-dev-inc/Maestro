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
