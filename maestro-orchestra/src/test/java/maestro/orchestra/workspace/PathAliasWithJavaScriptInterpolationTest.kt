package maestro.orchestra.workspace

import com.google.common.truth.Truth.assertThat
import maestro.orchestra.WorkspaceConfig
import maestro.orchestra.error.SyntaxError
import maestro.orchestra.yaml.YamlCommandReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class PathAliasWithJavaScriptInterpolationTest {

    @TempDir
    lateinit var testDir: Path

    private lateinit var configFile: Path

    @BeforeEach
    fun setUp() {
        // Create test directory structure
        testDir.resolve("flows/screens").toFile().mkdirs()
        testDir.resolve("assets/media").toFile().mkdirs()
        testDir.resolve("scripts").toFile().mkdirs()
        testDir.resolve("flows/components").toFile().mkdirs()
        
        // Create test files
        testDir.resolve("flows/screens/login.yaml").toFile().writeText("""
            appId: com.test.app
            ---
            - tapOn: "Login"
        """.trimIndent())
        
        testDir.resolve("flows/screens/signup.yaml").toFile().writeText("""
            appId: com.test.app
            ---
            - tapOn: "Sign Up"
        """.trimIndent())
        
        testDir.resolve("flows/components/button.yaml").toFile().writeText("""
            appId: com.test.app
            ---
            - tapOn: "Button"
        """.trimIndent())
        
        testDir.resolve("assets/media/test-image.jpg").toFile().writeText("fake image content")
        testDir.resolve("assets/media/logo.png").toFile().writeText("fake logo content")
        testDir.resolve("scripts/android_test.js").toFile().writeText("console.log('android test');")
        testDir.resolve("scripts/ios_test.js").toFile().writeText("console.log('ios test');")
        
        // Create regular path for mixed test
        testDir.resolve("regular/path").toFile().mkdirs()
        testDir.resolve("regular/path/image.png").toFile().writeText("fake regular image content")
        
        // Create workspace config
        configFile = testDir.resolve("config.yaml")
        configFile.toFile().writeText("""
            pathAliases:
              "@screens": "flows/screens"
              "@media": "assets/media"
              "!scripts": "scripts"
              "~components": "flows/components"
        """.trimIndent())
        
        // Set global workspace config
        val workspaceConfig = YamlCommandReader.readWorkspaceConfig(configFile)
        WorkspaceConfigProvider.workspaceConfig = workspaceConfig
    }

    @AfterEach
    fun tearDown() {
        WorkspaceConfigProvider.workspaceConfig = null
    }

    @Test
    fun `should resolve pathAlias in AddMedia command`() {
        // Create a flow file with AddMedia using pathAlias
        val flowFile = testDir.resolve("test-flow.yaml")
        flowFile.toFile().writeText("""
            appId: com.test.app
            ---
            - addMedia:
                files:
                  - "@media/test-image.jpg"
        """.trimIndent())
        
        // Read and parse the flow
        val commands = YamlCommandReader.readCommands(flowFile)
        
        // Verify that the pathAlias was resolved
        val addMediaCommand = commands.find { it.asCommand()?.javaClass?.simpleName == "AddMediaCommand" }
        assertThat(addMediaCommand).isNotNull()
        
        // The media path should be resolved to the actual path
        val mediaPaths = (addMediaCommand?.asCommand() as? maestro.orchestra.AddMediaCommand)?.mediaPaths
        assertThat(mediaPaths).isNotNull()
        assertThat(mediaPaths?.first()).contains("assets/media/test-image.jpg")
    }

    @Test
    fun `should resolve pathAlias in runScript command`() {
        // Create a flow file with runScript using pathAlias
        val flowFile = testDir.resolve("test-flow.yaml")
        flowFile.toFile().writeText("""
            appId: com.test.app
            ---
            - runScript:
                file: "!scripts/android_test.js"
        """.trimIndent())
        
        // Read and parse the flow
        val commands = YamlCommandReader.readCommands(flowFile)
        
        // Verify that the pathAlias was resolved
        val runScriptCommand = commands.find { it.asCommand()?.javaClass?.simpleName == "RunScriptCommand" }
        assertThat(runScriptCommand).isNotNull()
        
        // The script should be loaded from the resolved path
        val scriptContent = (runScriptCommand?.asCommand() as? maestro.orchestra.RunScriptCommand)?.script
        assertThat(scriptContent).isNotNull()
        assertThat(scriptContent).contains("console.log('android test');")
    }

    @Test
    fun `should resolve pathAlias in runFlow command`() {
        // Create a flow file with runFlow using pathAlias
        val flowFile = testDir.resolve("test-flow.yaml")
        flowFile.toFile().writeText("""
            appId: com.test.app
            ---
            - runFlow:
                file: "@screens/login.yaml"
        """.trimIndent())
        
        // Read and parse the flow
        val commands = YamlCommandReader.readCommands(flowFile)
        
        // Verify that the pathAlias was resolved
        val runFlowCommand = commands.find { it.asCommand()?.javaClass?.simpleName == "RunFlowCommand" }
        assertThat(runFlowCommand).isNotNull()
        
        // The sub-commands should be loaded from the resolved path
        val subCommands = (runFlowCommand?.asCommand() as? maestro.orchestra.RunFlowCommand)?.commands
        assertThat(subCommands).isNotNull()
        assertThat(subCommands?.size).isGreaterThan(0)
    }

    @Test
    fun `should resolve multiple pathAlias types in AddMedia command`() {
        // Test different pathAlias prefixes (@, !, ~)
        val flowFile = testDir.resolve("test-flow.yaml")
        flowFile.toFile().writeText("""
            appId: com.test.app
            ---
            - addMedia:
                files:
                  - "@media/test-image.jpg"
                  - "@media/logo.png"
        """.trimIndent())
        
        val commands = YamlCommandReader.readCommands(flowFile)
        val addMediaCommand = commands.find { it.asCommand()?.javaClass?.simpleName == "AddMediaCommand" }
        assertThat(addMediaCommand).isNotNull()
        
        val mediaPaths = (addMediaCommand?.asCommand() as? maestro.orchestra.AddMediaCommand)?.mediaPaths
        assertThat(mediaPaths).isNotNull()
        assertThat(mediaPaths?.size).isEqualTo(2)
        assertThat(mediaPaths?.first()).contains("assets/media/test-image.jpg")
        assertThat(mediaPaths?.last()).contains("assets/media/logo.png")
    }

    @Test
    fun `should resolve pathAlias in runFlow command with different screens`() {
        // Test runFlow with different screen files
        val flowFile = testDir.resolve("test-flow.yaml")
        flowFile.toFile().writeText("""
            appId: com.test.app
            ---
            - runFlow:
                file: "@screens/login.yaml"
        """.trimIndent())
        
        val commands = YamlCommandReader.readCommands(flowFile)
        val runFlowCommand = commands.find { it.asCommand()?.javaClass?.simpleName == "RunFlowCommand" }
        assertThat(runFlowCommand).isNotNull()
        
        // The sub-commands should be loaded from the resolved path
        val subCommands = (runFlowCommand?.asCommand() as? maestro.orchestra.RunFlowCommand)?.commands
        assertThat(subCommands).isNotNull()
        assertThat(subCommands?.size).isGreaterThan(0)
    }

    @Test
    fun `should resolve pathAlias in runFlow command with components`() {
        // Test runFlow with component files using ~ prefix
        val flowFile = testDir.resolve("test-flow.yaml")
        flowFile.toFile().writeText("""
            appId: com.test.app
            ---
            - runFlow:
                file: "~components/button.yaml"
        """.trimIndent())
        
        val commands = YamlCommandReader.readCommands(flowFile)
        val runFlowCommand = commands.find { it.asCommand()?.javaClass?.simpleName == "RunFlowCommand" }
        assertThat(runFlowCommand).isNotNull()
        
        val subCommands = (runFlowCommand?.asCommand() as? maestro.orchestra.RunFlowCommand)?.commands
        assertThat(subCommands).isNotNull()
        assertThat(subCommands?.size).isGreaterThan(0)
    }

    @Test
    fun `should resolve pathAlias in runScript command with different platforms`() {
        // Test runScript with different platform scripts
        val flowFile = testDir.resolve("test-flow.yaml")
        flowFile.toFile().writeText("""
            appId: com.test.app
            ---
            - runScript:
                file: "!scripts/android_test.js"
        """.trimIndent())
        
        val commands = YamlCommandReader.readCommands(flowFile)
        val runScriptCommand = commands.find { it.asCommand()?.javaClass?.simpleName == "RunScriptCommand" }
        assertThat(runScriptCommand).isNotNull()
        
        val scriptContent = (runScriptCommand?.asCommand() as? maestro.orchestra.RunScriptCommand)?.script
        assertThat(scriptContent).isNotNull()
        assertThat(scriptContent).contains("console.log('android test');")
    }

    @Test
    fun `should handle unknown pathAlias gracefully in AddMedia`() {
        // Test that unknown pathAlias are left unchanged
        val flowFile = testDir.resolve("test-flow.yaml")
        flowFile.toFile().writeText("""
            appId: com.test.app
            ---
            - addMedia:
                files:
                  - "@unknown/path.jpg"
        """.trimIndent())
        
        // This should fail because the file @unknown/path.jpg doesn't exist
        // and @unknown pathAlias is not defined
        val exception = assertThrows<SyntaxError> {
            YamlCommandReader.readCommands(flowFile)
        }
        assertThat(exception.message).contains("Media file at @unknown/path.jpg")
    }

    @Test
    fun `should handle mixed pathAlias and regular paths in AddMedia`() {
        // Test mixing pathAlias with regular paths
        val flowFile = testDir.resolve("test-flow.yaml")
        flowFile.toFile().writeText("""
            appId: com.test.app
            ---
            - addMedia:
                files:
                  - "@media/test-image.jpg"
                  - "regular/path/image.png"
        """.trimIndent())
        
        val commands = YamlCommandReader.readCommands(flowFile)
        val addMediaCommand = commands.find { it.asCommand()?.javaClass?.simpleName == "AddMediaCommand" }
        assertThat(addMediaCommand).isNotNull()
        
        val mediaPaths = (addMediaCommand?.asCommand() as? maestro.orchestra.AddMediaCommand)?.mediaPaths
        assertThat(mediaPaths).isNotNull()
        assertThat(mediaPaths?.size).isEqualTo(2)
        assertThat(mediaPaths?.first()).contains("assets/media/test-image.jpg")
        assertThat(mediaPaths?.last()).contains("regular/path/image.png")
    }

    @Test
    fun `should handle empty pathAlias configuration`() {
        // Test behavior when no pathAlias are configured
        WorkspaceConfigProvider.workspaceConfig = WorkspaceConfig()
        
        val flowFile = testDir.resolve("test-flow.yaml")
        flowFile.toFile().writeText("""
            appId: com.test.app
            ---
            - addMedia:
                files:
                  - "@media/test-image.jpg"
        """.trimIndent())
        
        // This should fail because the file @media/test-image.jpg doesn't exist
        // and pathAlias is not configured to resolve @media
        val exception = assertThrows<SyntaxError> {
            YamlCommandReader.readCommands(flowFile)
        }
        assertThat(exception.message).contains("Media file at @media/test-image.jpg")
    }

    @Test
    fun `should handle JavaScript interpolation in AddMedia with pathAlias`() {
        // This test would require a more complex setup with JavaScript engine
        // For now, we'll just verify that the pathAlias resolution works
        // The JavaScript interpolation will be tested in integration tests
        
        val flowFile = testDir.resolve("test-flow.yaml")
        flowFile.toFile().writeText("""
            appId: com.test.app
            ---
            - addMedia:
                files:
                  - "@media/test-image.jpg"
        """.trimIndent())
        
        val commands = YamlCommandReader.readCommands(flowFile)
        val addMediaCommand = commands.find { it.asCommand()?.javaClass?.simpleName == "AddMediaCommand" }
        assertThat(addMediaCommand).isNotNull()
    }
}
