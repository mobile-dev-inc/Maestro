package maestro.orchestra.util

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.random.Random
import maestro.js.GraalJsEngine
import maestro.js.JsEngine
import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.DefineVariablesCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.MaestroConfig
import maestro.orchestra.util.Env.evaluateScripts
import maestro.orchestra.util.Env.evaluateValueScripts
import maestro.orchestra.util.Env.withDefaultEnvVars
import maestro.orchestra.util.Env.withEnv
import maestro.orchestra.util.Env.withInjectedShellEnvVars
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EnvTest {

    private val emptyEnv = emptyMap<String, String>()

    /** A JS engine with [vars] already bound, so `${NAME}` interpolates in the tests below. */
    private fun jsEngine(vararg vars: Pair<String, String>): JsEngine =
        GraalJsEngine().apply { vars.forEach { (key, value) -> putEnv(key, value) } }

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
    fun `withDefaultEnvVars should set default shard values when shardIndex is null`() {
        // When not sharding, shard vars default to 1/0 so flows don't fail with undefined
        val env = emptyEnv.withDefaultEnvVars(
            flowFile = File("myFlow.yml"),
            deviceId = "device-123",
            shardIndex = null
        )
        assertThat(env["MAESTRO_FILENAME"]).isEqualTo("myFlow")
        assertThat(env["MAESTRO_DEVICE_UDID"]).isEqualTo("device-123")
        assertThat(env["MAESTRO_SHARD_ID"]).isEqualTo("1")
        assertThat(env["MAESTRO_SHARD_INDEX"]).isEqualTo("0")
    }

    @Test
    fun `withDefaultEnvVars should override external shard values with defaults when shardIndex is null`() {
        // External shard values (from --env, flow env, or shell) are replaced with defaults
        val env = mapOf(
            "MAESTRO_SHARD_ID" to "99",
            "MAESTRO_SHARD_INDEX" to "98",
            "OTHER_VAR" to "preserved",
        ).withDefaultEnvVars(
            flowFile = File("myFlow.yml"),
            shardIndex = null
        )
        // Shard values are reset to defaults (not the external values)
        assertThat(env["MAESTRO_SHARD_ID"]).isEqualTo("1")
        assertThat(env["MAESTRO_SHARD_INDEX"]).isEqualTo("0")
        // Other vars are preserved
        assertThat(env["OTHER_VAR"]).isEqualTo("preserved")
        assertThat(env["MAESTRO_FILENAME"]).isEqualTo("myFlow")
    }

    @Test
    fun `withInjectedShellEnvVars only keeps MAESTRO_ vars`() {
        val env = emptyEnv.withInjectedShellEnvVars()
        assertThat(env.filterKeys { it.startsWith("MAESTRO_").not() }).isEmpty()
    }

    @Test
    fun `withInjectedShellEnvVars should not inject shard variables from shell`() {
        // Shard variables should only be controlled by internal logic (withDefaultEnvVars),
        // not from external shell environment, to prevent inconsistent state where only
        // one of MAESTRO_SHARD_ID or MAESTRO_SHARD_INDEX is set from external environment.
        val env = emptyEnv.withInjectedShellEnvVars()
        // These assertions verify that even if shell has MAESTRO_SHARD_* vars,
        // they won't be injected. The actual shell env might not have these vars,
        // but this test documents the expected behavior.
        assertThat(env.containsKey("MAESTRO_SHARD_ID")).isFalse()
        assertThat(env.containsKey("MAESTRO_SHARD_INDEX")).isFalse()
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

    @Test
    fun `withEnv fails with a helpful message when an env value is null`() {
        // YAML `AUTOMATED_EMAIL:` (no value) deserializes to null inside Map<String, String>
        // due to Kotlin generic type erasure; simulate that here with an unchecked cast.
        @Suppress("UNCHECKED_CAST")
        val envWithNull = mapOf(
            "AUTOMATED_EMAIL" to null,
            "COUNTRY" to "United Kingdom",
        ) as Map<String, String>

        val error = org.junit.jupiter.api.Assertions.assertThrows(maestro.orchestra.util.Env.EnvVariableMissingValueError::class.java) {
            emptyList<MaestroCommand>().withEnv(envWithNull)
        }
        assertThat(error.message).contains("AUTOMATED_EMAIL")
        assertThat(error.message).doesNotContain("COUNTRY")
    }

    @Test
    fun `withEnv fails when the only env key has a null value`() {
        @Suppress("UNCHECKED_CAST")
        val envWithNull = mapOf("AUTOMATED_EMAIL" to null) as Map<String, String>

        val error = org.junit.jupiter.api.Assertions.assertThrows(maestro.orchestra.util.Env.EnvVariableMissingValueError::class.java) {
            emptyList<MaestroCommand>().withEnv(envWithNull)
        }
        assertThat(error.message).contains("AUTOMATED_EMAIL")
    }

    @Test
    fun `withEnv lists every null env key`() {
        @Suppress("UNCHECKED_CAST")
        val envWithNulls = mapOf(
            "AUTOMATED_EMAIL" to null,
            "COUNTRY" to "United Kingdom",
            "REGION" to null,
        ) as Map<String, String>

        val error = org.junit.jupiter.api.Assertions.assertThrows(maestro.orchestra.util.Env.EnvVariableMissingValueError::class.java) {
            emptyList<MaestroCommand>().withEnv(envWithNulls)
        }
        assertThat(error.message).contains("AUTOMATED_EMAIL")
        assertThat(error.message).contains("REGION")
    }

    @Test
    fun `List evaluateScripts interpolates every element`() {
        val paths = listOf("./\${DIR}/a.png", "./static/b.png")

        val evaluated = paths.evaluateScripts(jsEngine("DIR" to "media"))

        assertThat(evaluated).containsExactly("./media/a.png", "./static/b.png").inOrder()
    }

    @Test
    fun `Map evaluateScripts interpolates keys and values`() {
        val permissions = mapOf("\${PERMISSION}" to "\${STATE}", "location" to "deny")

        val evaluated = permissions.evaluateScripts(
            jsEngine("PERMISSION" to "camera", "STATE" to "allow"),
            "permissions",
        )

        assertThat(evaluated).containsExactly("camera", "allow", "location", "deny")
    }

    @Test
    fun `Map evaluateScripts preserves insertion order`() {
        val map = mapOf("c" to "3", "a" to "1", "b" to "2")

        val evaluated = map.evaluateScripts(jsEngine(), "permissions")

        assertThat(evaluated.keys).containsExactly("c", "a", "b").inOrder()
    }

    @Test
    fun `Map evaluateScripts leaves non-string values untouched`() {
        // launchArguments allows booleans and numbers alongside strings
        val launchArguments = mapOf<String, Any>(
            "\${FLAG_NAME}" to true,
            "retries" to 3,
            "user" to "\${USER_NAME}",
        )

        val evaluated = launchArguments.evaluateScripts(
            jsEngine("FLAG_NAME" to "isCartScreen", "USER_NAME" to "ada"),
            "launchArguments",
        )

        assertThat(evaluated).containsExactly("isCartScreen", true, "retries", 3, "user", "ada")
    }

    @Test
    fun `Map evaluateScripts rejects keys that collide after interpolation`() {
        // YAML rejects literal duplicates, so interpolation is the only way to reach this.
        // Silently keeping the last value would hide the mistake.
        val permissions = mapOf("\${PERMISSION}" to "allow", "location" to "deny")

        val error = assertThrows<Env.DuplicateKeyError> {
            permissions.evaluateScripts(jsEngine("PERMISSION" to "location"), "permissions")
        }
        assertThat(error.key).isEqualTo("location")
        assertThat(error.message).contains("permissions")
        assertThat(error.message).contains("location")
    }

    @Test
    fun `Map evaluateScripts fails with a helpful message when a value is null`() {
        // YAML `camera:` (no value) deserializes to null inside Map<String, String>
        // due to Kotlin generic type erasure; simulate that here with an unchecked cast.
        @Suppress("UNCHECKED_CAST")
        val permissions = mapOf(
            "camera" to null,
            "location" to "deny",
            "notifications" to null,
        ) as Map<String, String>

        val error = assertThrows<Env.MissingValueError> {
            permissions.evaluateScripts(jsEngine(), "permissions")
        }
        assertThat(error.keys).containsExactly("camera", "notifications")
        assertThat(error.message).contains("permissions")
        assertThat(error.message).contains("camera")
        assertThat(error.message).contains("notifications")
        assertThat(error.message).doesNotContain("location")
    }

    @Test
    fun `Map evaluateValueScripts interpolates values and leaves keys verbatim`() {
        // env blocks declare variable names, so an interpolated key would define
        // something nothing can reference
        val env = mapOf("\${NOT_A_SCRIPT}" to "\${GREETING} world")

        val evaluated = env.evaluateValueScripts(
            jsEngine("NOT_A_SCRIPT" to "nope", "GREETING" to "hello"),
            "env",
        )

        assertThat(evaluated).containsExactly("\${NOT_A_SCRIPT}", "hello world")
    }

    @Test
    fun `Map evaluateValueScripts fails with a helpful message when a value is null`() {
        @Suppress("UNCHECKED_CAST")
        val env = mapOf("AUTOMATED_EMAIL" to null) as Map<String, String>

        val error = assertThrows<Env.MissingValueError> {
            env.evaluateValueScripts(jsEngine(), "env")
        }
        assertThat(error.keys).containsExactly("AUTOMATED_EMAIL")
        assertThat(error.message).contains("env")
        assertThat(error.message).contains("AUTOMATED_EMAIL")
    }

    @Test
    fun `Map evaluateScripts leaves an empty map alone`() {
        assertThat(emptyEnv.evaluateScripts(jsEngine(), "permissions")).isEmpty()
        assertThat(emptyEnv.evaluateValueScripts(jsEngine(), "env")).isEmpty()
    }
}
