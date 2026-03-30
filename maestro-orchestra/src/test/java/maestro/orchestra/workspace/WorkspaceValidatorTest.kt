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

    private fun makeWorkspaceZip(flowYaml: String, configYaml: String? = null): File {
        val zip = File(tempDir, "workspace.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            if (configYaml != null) {
                zos.putNextEntry(ZipEntry("config.yaml"))
                zos.write(configYaml.toByteArray())
                zos.closeEntry()
            }
            zos.putNextEntry(ZipEntry("flow.yaml"))
            zos.write(flowYaml.toByteArray())
            zos.closeEntry()
        }
        return zip
    }

    @Test
    fun `validate returns workspaceConfig and matching flows for appId`() {
        val flowYaml = """
            appId: com.example.app
            ---
            - launchApp
        """.trimIndent()

        val result = WorkspaceValidator.validate(
            workspace = makeWorkspaceZip(flowYaml),
            appId = "com.example.app",
            envParameters = emptyMap(),
            includeTags = emptyList(),
            excludeTags = emptyList(),
        )

        assertThat(result.isOk).isTrue()
        assertThat(result.value.flows).hasSize(1)
    }

    @Test
    fun `validate returns error when no flows match appId`() {
        val flowYaml = """
            appId: com.other.app
            ---
            - launchApp
        """.trimIndent()

        val result = WorkspaceValidator.validate(
            workspace = makeWorkspaceZip(flowYaml),
            appId = "com.example.app",
            envParameters = emptyMap(),
            includeTags = emptyList(),
            excludeTags = emptyList(),
        )

        assertThat(result.isErr).isTrue()
    }
}
