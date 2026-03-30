package maestro.orchestra.workspace

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WorkspaceValidatorTest {

    @TempDir
    lateinit var tempDir: File

    private fun makeWorkspaceZip(vararg flows: Pair<String, String>, configYaml: String? = null): File {
        val zip = File(tempDir, "workspace_${System.nanoTime()}.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            if (configYaml != null) {
                zos.putNextEntry(ZipEntry("config.yaml"))
                zos.write(configYaml.toByteArray())
                zos.closeEntry()
            }
            flows.forEach { (filename, yaml) ->
                zos.putNextEntry(ZipEntry(filename))
                zos.write(yaml.toByteArray())
                zos.closeEntry()
            }
        }
        return zip
    }

    private fun flow(appId: String, name: String? = null): String {
        val header = buildString {
            append("appId: $appId\n")
            if (name != null) append("name: $name\n")
        }
        return "$header---\n- launchApp"
    }

    @Test
    fun `validate returns workspaceConfig and matching flows for appId`() {
        val result = WorkspaceValidator.validate(
            workspace = makeWorkspaceZip("flow.yaml" to flow("com.example.app")),
            appId = "com.example.app",
            envParameters = emptyMap(),
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
                "flow_a.yaml" to flow("com.example.app"),
                "flow_b.yaml" to flow("com.other.app"),
            ),
            appId = "com.example.app",
            envParameters = emptyMap(),
            includeTags = emptyList(),
            excludeTags = emptyList(),
        )

        assertThat(result.isOk).isTrue()
        assertThat(result.value.flows).hasSize(1)
        assertThat(result.value.flows.first().appId).isEqualTo("com.example.app")
    }

    @Test
    fun `validate returns NoFlowsMatchingAppId when no flows match`() {
        val result = WorkspaceValidator.validate(
            workspace = makeWorkspaceZip("flow.yaml" to flow("com.other.app")),
            appId = "com.example.app",
            envParameters = emptyMap(),
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
                "flow_a.yaml" to flow("com.example.app", name = "Login"),
                "flow_b.yaml" to flow("com.example.app", name = "Login"),
            ),
            appId = "com.example.app",
            envParameters = emptyMap(),
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
    fun `validate returns EmptyWorkspace when zip has no yaml flows`() {
        val emptyZip = File(tempDir, "empty.zip")
        ZipOutputStream(emptyZip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("README.txt"))
            zos.write("nothing here".toByteArray())
            zos.closeEntry()
        }

        val result = WorkspaceValidator.validate(
            workspace = emptyZip,
            appId = "com.example.app",
            envParameters = emptyMap(),
            includeTags = emptyList(),
            excludeTags = emptyList(),
        )

        assertThat(result.isErr).isTrue()
        assertThat(result.error).isInstanceOf(WorkspaceValidationError.EmptyWorkspace::class.java)
    }
}
