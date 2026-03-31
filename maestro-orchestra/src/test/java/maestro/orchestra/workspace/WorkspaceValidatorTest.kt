package maestro.orchestra.workspace

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WorkspaceValidatorTest {

    @TempDir
    lateinit var tempDir: File

    private val baseFlowContent = Paths.get("src/test/resources/workspaces/workspace_validator_flow.yaml")
        .toFile().readText()

    private fun makeWorkspaceZip(vararg entries: Pair<String, String>): File {
        val zip = File(tempDir, "workspace_${System.nanoTime()}.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return zip
    }

    private fun flowWithName(name: String): String {
        return baseFlowContent.replace("appId:", "name: $name\nappId:")
    }

    @Test
    fun `validate returns workspaceConfig and matching flows for appId`() {
        val result = WorkspaceValidator.validate(
            workspace = makeWorkspaceZip("flow.yaml" to baseFlowContent),
            appId = "com.example.app",
            envParameters = mapOf("APP_ID" to "com.example.app"),
            includeTags = emptyList(),
            excludeTags = emptyList(),
        )

        assertThat(result.isOk).isTrue()
        assertThat(result.value.flows).hasSize(1)
    }

    @Test
    fun `validate only returns flows matching the given appId`() {
        val result = WorkspaceValidator.validate(
            workspace = makeWorkspaceZip(
                "flow_a.yaml" to baseFlowContent,
                "flow_b.yaml" to baseFlowContent,
            ),
            appId = "com.example.app",
            envParameters = mapOf("APP_ID" to "com.example.app"),
            includeTags = emptyList(),
            excludeTags = emptyList(),
        )

        assertThat(result.isOk).isTrue()
        assertThat(result.value.flows).hasSize(2)
    }

    @Test
    fun `validate returns NoFlowsMatchingAppId when no flows match`() {
        val result = WorkspaceValidator.validate(
            workspace = makeWorkspaceZip("flow.yaml" to baseFlowContent),
            appId = "com.nonexistent.app",
            envParameters = mapOf("APP_ID" to "com.example.app"),
            includeTags = emptyList(),
            excludeTags = emptyList(),
        )

        assertThat(result.isErr).isTrue()
        assertThat(result.error).isInstanceOf(WorkspaceValidationError.NoFlowsMatchingAppId::class.java)
    }

    @Test
    fun `validate returns NameConflict when two matching flows have the same name`() {
        val result = WorkspaceValidator.validate(
            workspace = makeWorkspaceZip(
                "flow_a.yaml" to flowWithName("Login"),
                "flow_b.yaml" to flowWithName("Login"),
            ),
            appId = "com.example.app",
            envParameters = mapOf("APP_ID" to "com.example.app"),
            includeTags = emptyList(),
            excludeTags = emptyList(),
        )

        assertThat(result.isErr).isTrue()
        assertThat(result.error).isInstanceOf(WorkspaceValidationError.NameConflict::class.java)
    }

    @Test
    fun `validate returns InvalidWorkspaceFile for a non-zip file`() {
        val notAZip = File(tempDir, "bad.zip").also { it.writeText("not a zip") }

        val result = WorkspaceValidator.validate(
            workspace = notAZip,
            appId = "com.example.app",
            envParameters = emptyMap(),
            includeTags = emptyList(),
            excludeTags = emptyList(),
        )

        assertThat(result.isErr).isTrue()
        assertThat(result.error).isInstanceOf(WorkspaceValidationError.InvalidWorkspaceFile::class.java)
    }

    @Test
    fun `validate resolves env variables with rhino engine when explicitly requested`() {
        val rhinoFlow = """
            appId: ${'$'}{APP_ID}
            ext:
              jsEngine: rhino
            ---
            - launchApp
        """.trimIndent()

        val result = WorkspaceValidator.validate(
            workspace = makeWorkspaceZip("flow.yaml" to rhinoFlow),
            appId = "com.example.app",
            envParameters = mapOf("APP_ID" to "com.example.app"),
            includeTags = emptyList(),
            excludeTags = emptyList(),
        )

        assertThat(result.isOk).isTrue()
        assertThat(result.value.flows).hasSize(1)
        assertThat(result.value.flows.first().appId).isEqualTo("com.example.app")
    }

    @Test
    fun `validate returns EmptyWorkspace when zip has no yaml flows`() {
        val result = WorkspaceValidator.validate(
            workspace = makeWorkspaceZip("README.txt" to "nothing here"),
            appId = "com.example.app",
            envParameters = emptyMap(),
            includeTags = emptyList(),
            excludeTags = emptyList(),
        )

        assertThat(result.isErr).isTrue()
        assertThat(result.error).isInstanceOf(WorkspaceValidationError.EmptyWorkspace::class.java)
    }

    @Test
    fun `validate returns MissingLaunchApp when flow has no launchApp command`() {
        val flowWithoutLaunch = """
            appId: com.example.app
            ---
            - tapOn: "some button"
        """.trimIndent()

        val result = WorkspaceValidator.validate(
            workspace = makeWorkspaceZip("flow.yaml" to flowWithoutLaunch),
            appId = "com.example.app",
            envParameters = emptyMap(),
            includeTags = emptyList(),
            excludeTags = emptyList(),
        )

        assertThat(result.isErr).isTrue()
        assertThat(result.error).isInstanceOf(WorkspaceValidationError.MissingLaunchApp::class.java)
        val error = result.error as WorkspaceValidationError.MissingLaunchApp
        assertThat(error.flowNames).containsExactly("flow")
    }
}
