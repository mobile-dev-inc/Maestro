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

    private fun makeWorkspaceZipWithBinary(
        textEntries: List<Pair<String, String>>,
        binaryEntries: List<Pair<String, ByteArray>> = emptyList()
    ): File {
        val zip = File(tempDir, "workspace_${System.nanoTime()}.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            textEntries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
            binaryEntries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content)
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
        val flowWithMedia = """
            appId: com.example.app
            ---
            - launchApp
            - addMedia:
                - "../test-image.jpg"
        """.trimIndent()

        val configYaml = """
            flows:
              - "flows/**"
        """.trimIndent()

        val workspace = makeWorkspaceZipWithBinary(
            textEntries = listOf(
                "config.yaml" to configYaml,
                "flows/main.yaml" to flowWithMedia,
            ),
            binaryEntries = listOf("test-image.jpg" to byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        )

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
    fun `validate produces normalized filePath for flows in subdirectories`() {
        val configYaml = """
            flows:
              - "flows/**"
        """.trimIndent()

        val result = WorkspaceValidator.validate(
            workspace = makeWorkspaceZip(
                "config.yaml" to configYaml,
                "flows/folder1/test.yaml" to baseFlowContent,
            ),
            appId = "com.example.app",
            envParameters = mapOf("APP_ID" to "com.example.app"),
            includeTags = emptyList(),
            excludeTags = emptyList(),
        )

        assertThat(result.isOk).isTrue()
        val flow = result.value.flows.first()
        assertThat(flow.filePath).doesNotContain("..")
        assertThat(flow.filePath).doesNotContain("/./")
    }

    @Test
    fun `validate produces normalized sourceDescription for runFlow with relative path`() {
        val subflow = """
            appId: com.example.app
            ---
            - launchApp
        """.trimIndent()

        val mainFlow = """
            appId: com.example.app
            ---
            - launchApp
            - runFlow:
                file: ../../shared/setup.yaml
        """.trimIndent()

        val configYaml = """
            flows:
              - "tests/**"
        """.trimIndent()

        val result = WorkspaceValidator.validate(
            workspace = makeWorkspaceZip(
                "config.yaml" to configYaml,
                "tests/core/main_flow.yaml" to mainFlow,
                "shared/setup.yaml" to subflow,
            ),
            appId = "com.example.app",
            envParameters = emptyMap(),
            includeTags = emptyList(),
            excludeTags = emptyList(),
        )

        assertThat(result.isOk).isTrue()
        val mainFlowResult = result.value.flows.first { it.name == "main_flow" }

        // The runFlow sourceDescription should not contain ".." or "./"
        val runFlowCmd = mainFlowResult.commands
            .mapNotNull { it.runFlowCommand }
            .first()
        assertThat(runFlowCmd.sourceDescription).isNotNull()
    }

    @Test
    fun `validate produces normalized addMedia paths in subflows referenced via relative runFlow`() {
        val subflow = """
            appId: com.example.app
            ---
            - addMedia:
                - "../e2e-image.jpg"
        """.trimIndent()

        val mainFlow = """
            appId: com.example.app
            ---
            - launchApp
            - runFlow:
                file: ../../setup/startup.yaml
        """.trimIndent()

        val configYaml = """
            flows:
              - "tests/**"
        """.trimIndent()

        val workspace = makeWorkspaceZipWithBinary(
            textEntries = listOf(
                "config.yaml" to configYaml,
                "tests/core/test.yaml" to mainFlow,
                "setup/startup.yaml" to subflow,
            ),
            binaryEntries = listOf("e2e-image.jpg" to byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        )

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
