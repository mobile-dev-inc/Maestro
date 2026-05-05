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

    private val baseFlowContent = WorkspaceValidatorTest::class.java
        .getResource("/workspaces/workspace_validator_flow.yaml")!!
        .readText()

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

    private fun zipWorkspaceResource(resourcePath: String): File {
        val resourceDir = File(javaClass.getResource(resourcePath)!!.toURI())
        val zip = File(tempDir, "workspace_${System.nanoTime()}.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            resourceDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val entryName = file.relativeTo(resourceDir).path
                zos.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zos) }
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
    fun `validate fails with clear error when jsEngine rhino is requested`() {
        val rhinoFlow = """
            appId: com.example.app
            ext:
              jsEngine: rhino
            ---
            - launchApp
        """.trimIndent()

        val result = WorkspaceValidator.validate(
            workspace = makeWorkspaceZip("flow.yaml" to rhinoFlow),
            appId = "com.example.app",
            envParameters = emptyMap(),
            includeTags = emptyList(),
            excludeTags = emptyList(),
        )

        assertThat(result.isErr).isTrue()
        val error = result.error
        assertThat(error).isInstanceOf(WorkspaceValidationError.GenericError::class.java)
        assertThat((error as WorkspaceValidationError.GenericError).detail).contains("Rhino JS engine has been removed")
        assertThat(error.detail).contains("jsEngine: rhino")
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

    @Test
    fun `validate returns MissingLaunchApp when root flow has no launchApp and is not referenced as subflow`() {
        val flowWithLaunch = """
            appId: com.example.app
            ---
            - launchApp
        """.trimIndent()

        val flowWithoutLaunch = """
            appId: com.example.app
            ---
            - tapOn: "some button"
        """.trimIndent()

        val result = WorkspaceValidator.validate(
            workspace = makeWorkspaceZip(
                "flow_a.yaml" to flowWithLaunch,
                "flow_b.yaml" to flowWithoutLaunch,
            ),
            appId = "com.example.app",
            envParameters = emptyMap(),
            includeTags = emptyList(),
            excludeTags = emptyList(),
        )

        assertThat(result.isErr).isTrue()
        assertThat(result.error).isInstanceOf(WorkspaceValidationError.MissingLaunchApp::class.java)
        val error = result.error as WorkspaceValidationError.MissingLaunchApp
        assertThat(error.flowNames).containsExactly("flow_b")
    }

    @Test
    fun `validate accepts flow when runFlow references subflow containing launchApp`() {
        val launchFlow = """
            appId: com.example.app
            ---
            - launchApp
        """.trimIndent()

        val mainFlow = """
            appId: com.example.app
            ---
            - runFlow:
                file: launch.yaml
            - tapOn: "some button"
        """.trimIndent()

        val result = WorkspaceValidator.validate(
            workspace = makeWorkspaceZip(
                "main_flow.yaml" to mainFlow,
                "launch.yaml" to launchFlow,
            ),
            appId = "com.example.app",
            envParameters = emptyMap(),
            includeTags = emptyList(),
            excludeTags = emptyList(),
        )

        assertThat(result.isOk).isTrue()
        assertThat(result.value.flows).hasSize(2)
    }

    @Test
    fun `validate accepts flow when onFlowStart hook contains launchApp`() {
        val flowWithOnFlowStartLaunch = """
            appId: com.example.app
            onFlowStart:
              - launchApp
            ---
            - tapOn: "some button"
        """.trimIndent()

        val result = WorkspaceValidator.validate(
            workspace = makeWorkspaceZip("flow.yaml" to flowWithOnFlowStartLaunch),
            appId = "com.example.app",
            envParameters = emptyMap(),
            includeTags = emptyList(),
            excludeTags = emptyList(),
        )

        assertThat(result.isOk).isTrue()
        assertThat(result.value.flows).hasSize(1)
    }

    @Test
    fun `validate accepts flow when onFlowStart hook has runFlow referencing subflow with launchApp`() {
        val launchFlow = """
            appId: com.example.app
            ---
            - launchApp
        """.trimIndent()

        val mainFlow = """
            appId: com.example.app
            onFlowStart:
              - runFlow:
                  file: launch.yaml
            ---
            - tapOn: "some button"
        """.trimIndent()

        val result = WorkspaceValidator.validate(
            workspace = makeWorkspaceZip(
                "main_flow.yaml" to mainFlow,
                "launch.yaml" to launchFlow,
            ),
            appId = "com.example.app",
            envParameters = emptyMap(),
            includeTags = emptyList(),
            excludeTags = emptyList(),
        )

        assertThat(result.isOk).isTrue()
        assertThat(result.value.flows).hasSize(2)
    }

    @Test
    fun `validate accepts flow when retry references subflow containing launchApp`() {
        val launchFlow = """
            appId: com.example.app
            ---
            - launchApp
        """.trimIndent()

        val mainFlow = """
            appId: com.example.app
            ---
            - retry:
                file: launch.yaml
            - tapOn: "some button"
        """.trimIndent()

        val result = WorkspaceValidator.validate(
            workspace = makeWorkspaceZip(
                "main_flow.yaml" to mainFlow,
                "launch.yaml" to launchFlow,
            ),
            appId = "com.example.app",
            envParameters = emptyMap(),
            includeTags = emptyList(),
            excludeTags = emptyList(),
        )

        assertThat(result.isOk).isTrue()
        assertThat(result.value.flows).hasSize(2)
    }

    @Test
    fun `validate produces normalized addMedia paths for relative references`() {
        val workspace = zipWorkspaceResource("/workspaces/016_normalized_media_paths")

        val result = WorkspaceValidator.validate(
            workspace = workspace,
            appId = "com.example.app",
            envParameters = emptyMap(),
            includeTags = emptyList(),
            excludeTags = emptyList(),
        )

        assertThat(result.isOk).isTrue()
        val commands = result.value.flows.first().commands
        val addMediaCmd = commands.mapNotNull { it.addMediaCommand }.first()
        addMediaCmd.mediaPaths.forEach { path ->
            assertThat(path).doesNotContain("..")
        }
    }

    @Test
    fun `validate produces normalized addMedia paths in subflows referenced via relative runFlow`() {
        val workspace = zipWorkspaceResource("/workspaces/017_normalized_media_paths_subflow")

        val result = WorkspaceValidator.validate(
            workspace = workspace,
            appId = "com.example.app",
            envParameters = emptyMap(),
            includeTags = emptyList(),
            excludeTags = emptyList(),
        )

        assertThat(result.isOk).isTrue()
        val mainFlowResult = result.value.flows.first { it.name == "test" }
        val addMediaCmd = mainFlowResult.commands
            .mapNotNull { it.runFlowCommand }
            .flatMap { it.commands }
            .mapNotNull { it.addMediaCommand }
            .first()
        addMediaCmd.mediaPaths.forEach { path ->
            assertThat(path).doesNotContain("..")
        }
    }
}
