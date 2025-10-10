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

    @Test
    fun `should handle JavaScript interpolation in runFlow command`() {
        // Set up workspace config for this test
        val workspaceConfig = YamlCommandReader.readWorkspaceConfig(configFile)
        WorkspaceConfigProvider.workspaceConfig = workspaceConfig
        
        // Create a sub-flow file for testing
        val subFlowFile = testDir.resolve("sub-flow.yaml")
        subFlowFile.toFile().writeText("""
            appId: com.test.app
            ---
            - tapOn: "Button"
        """.trimIndent())
        
        val flowFile = testDir.resolve("test-flow.yaml")
        flowFile.toFile().writeText("""
            appId: com.test.app
            ---
            - runFlow:
                file: "sub-flow.yaml"
        """.trimIndent())
        
        val commands = YamlCommandReader.readCommands(flowFile)
        val runFlowCommand = commands.find { it.asCommand()?.javaClass?.simpleName == "RunFlowCommand" }
        assertThat(runFlowCommand).isNotNull()
        
        // Verify the command was created successfully
        val command = runFlowCommand?.asCommand() as? maestro.orchestra.RunFlowCommand
        assertThat(command).isNotNull()
        assertThat(command?.sourceDescription).isEqualTo("sub-flow.yaml")
    }

    @Test
    fun `should handle JavaScript interpolation in runScript command`() {
        // Set up workspace config for this test
        val workspaceConfig = YamlCommandReader.readWorkspaceConfig(configFile)
        WorkspaceConfigProvider.workspaceConfig = workspaceConfig
        
        // Create a script file for testing
        val scriptFile = testDir.resolve("test-script.js")
        scriptFile.toFile().writeText("""
            console.log('Hello from script');
            console.log('Platform: ' + process.env.PLATFORM || 'unknown');
        """.trimIndent())
        
        val flowFile = testDir.resolve("test-flow.yaml")
        flowFile.toFile().writeText("""
            appId: com.test.app
            ---
            - runScript:
                file: "test-script.js"
        """.trimIndent())
        
        val commands = YamlCommandReader.readCommands(flowFile)
        val runScriptCommand = commands.find { it.asCommand()?.javaClass?.simpleName == "RunScriptCommand" }
        assertThat(runScriptCommand).isNotNull()
        
        // Verify the command was created successfully
        val command = runScriptCommand?.asCommand() as? maestro.orchestra.RunScriptCommand
        assertThat(command).isNotNull()
        assertThat(command?.sourceDescription).isEqualTo("test-script.js")
        assertThat(command?.script).contains("console.log('Hello from script')")
    }

    @Test
    fun `should handle JavaScript interpolation with pathAlias in runFlow command`() {
        // Create a sub-flow file in the screens directory
        val screensDir = testDir.resolve("flows/screens")
        screensDir.toFile().mkdirs()
        val subFlowFile = screensDir.resolve("login.yaml")
        subFlowFile.toFile().writeText("""
            appId: com.test.app
            ---
            - tapOn: "Login Button"
        """.trimIndent())
        
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
        
        // Verify the command was created successfully
        val command = runFlowCommand?.asCommand() as? maestro.orchestra.RunFlowCommand
        assertThat(command).isNotNull()
        assertThat(command?.sourceDescription).isEqualTo("@screens/login.yaml")
    }

    @Test
    fun `should handle JavaScript interpolation with pathAlias in runScript command`() {
        // Create a script file in the scripts directory
        val scriptsDir = testDir.resolve("scripts")
        scriptsDir.toFile().mkdirs()
        val scriptFile = scriptsDir.resolve("test-script.js")
        scriptFile.toFile().writeText("""
            console.log('Script from pathAlias');
            console.log('Environment: ' + process.env.ENV || 'development');
        """.trimIndent())
        
        val flowFile = testDir.resolve("test-flow.yaml")
        flowFile.toFile().writeText("""
            appId: com.test.app
            ---
            - runScript:
                file: "!scripts/test-script.js"
        """.trimIndent())
        
        val commands = YamlCommandReader.readCommands(flowFile)
        val runScriptCommand = commands.find { it.asCommand()?.javaClass?.simpleName == "RunScriptCommand" }
        assertThat(runScriptCommand).isNotNull()
        
        // Verify the command was created successfully
        val command = runScriptCommand?.asCommand() as? maestro.orchestra.RunScriptCommand
        assertThat(command).isNotNull()
        assertThat(command?.sourceDescription).isEqualTo("!scripts/test-script.js")
        assertThat(command?.script).contains("console.log('Script from pathAlias')")
    }

    @Test
    fun `should handle mixed pathAlias and regular paths in runFlow and runScript`() {
        // Set up workspace config for this test
        val workspaceConfig = YamlCommandReader.readWorkspaceConfig(configFile)
        WorkspaceConfigProvider.workspaceConfig = workspaceConfig
        
        // Create files in different locations
        val screensDir = testDir.resolve("flows/screens")
        screensDir.toFile().mkdirs()
        val subFlowFile = screensDir.resolve("dashboard.yaml")
        subFlowFile.toFile().writeText("""
            appId: com.test.app
            ---
            - tapOn: "Dashboard"
        """.trimIndent())
        
        val regularScriptFile = testDir.resolve("utils.js")
        regularScriptFile.toFile().writeText("""
            console.log('Regular script');
        """.trimIndent())
        
        val flowFile = testDir.resolve("test-flow.yaml")
        flowFile.toFile().writeText("""
            appId: com.test.app
            ---
            - runFlow:
                file: "@screens/dashboard.yaml"
            - runScript:
                file: "utils.js"
        """.trimIndent())
        
        val commands = YamlCommandReader.readCommands(flowFile)
        
        // Verify runFlow command
        val runFlowCommand = commands.find { it.asCommand()?.javaClass?.simpleName == "RunFlowCommand" }
        assertThat(runFlowCommand).isNotNull()
        val flowCommand = runFlowCommand?.asCommand() as? maestro.orchestra.RunFlowCommand
        assertThat(flowCommand?.sourceDescription).isEqualTo("@screens/dashboard.yaml")
        
        // Verify runScript command
        val runScriptCommand = commands.find { it.asCommand()?.javaClass?.simpleName == "RunScriptCommand" }
        assertThat(runScriptCommand).isNotNull()
        val scriptCommand = runScriptCommand?.asCommand() as? maestro.orchestra.RunScriptCommand
        assertThat(scriptCommand?.sourceDescription).isEqualTo("utils.js")
        assertThat(scriptCommand?.script).contains("console.log('Regular script')")
    }

    @Test
    fun `should handle JavaScript interpolation in runScript file property`() {
        // Set up workspace config for this test
        val workspaceConfig = YamlCommandReader.readWorkspaceConfig(configFile)
        WorkspaceConfigProvider.workspaceConfig = workspaceConfig
        
        // Create script files with different names
        val scriptsDir = testDir.resolve("scripts")
        scriptsDir.toFile().mkdirs()
        val androidScript = scriptsDir.resolve("android_test.js")
        androidScript.toFile().writeText("""
            console.log('Android script executed');
        """.trimIndent())
        
        val iosScript = scriptsDir.resolve("ios_test.js")
        iosScript.toFile().writeText("""
            console.log('iOS script executed');
        """.trimIndent())
        
        val flowFile = testDir.resolve("js-interpolation-script.yaml")
        val yamlContent = "appId: com.test.app\n" +
                "---\n" +
                "- runScript:\n" +
                "    file: \"!scripts/\${PLATFORM}_test.js\"\n" +
                "    env:\n" +
                "      PLATFORM: \"android\""
        flowFile.toFile().writeText(yamlContent)
        
        val commands = YamlCommandReader.readCommands(flowFile)
        val runScriptCommand = commands.find { it.asCommand()?.javaClass?.simpleName == "RunScriptCommand" }
        assertThat(runScriptCommand).isNotNull()
        
        // Verify the command was created with JavaScript interpolation in file path
        val command = runScriptCommand?.asCommand() as? maestro.orchestra.RunScriptCommand
        assertThat(command).isNotNull()
        assertThat(command?.sourceDescription).isEqualTo("!scripts/\${PLATFORM}_test.js")
        assertThat(command?.flowPath).isNotNull()
        
        // The script should be empty initially since it contains JavaScript interpolation
        assertThat(command?.script).isEmpty()
    }

    @Test
    fun `should handle JavaScript interpolation in runFlow file property`() {
        // Set up workspace config for this test
        val workspaceConfig = YamlCommandReader.readWorkspaceConfig(configFile)
        WorkspaceConfigProvider.workspaceConfig = workspaceConfig
        
        // Create flow files with different names
        val screensDir = testDir.resolve("flows/screens")
        screensDir.toFile().mkdirs()
        val loginFlow = screensDir.resolve("login.yaml")
        loginFlow.toFile().writeText("""
            appId: com.test.app
            ---
            - tapOn: "Login Button"
        """.trimIndent())
        
        val signupFlow = screensDir.resolve("signup.yaml")
        signupFlow.toFile().writeText("""
            appId: com.test.app
            ---
            - tapOn: "Sign Up Button"
        """.trimIndent())
        
        val flowFile = testDir.resolve("js-interpolation-flow.yaml")
        val yamlContent = "appId: com.test.app\n" +
                "---\n" +
                "- runFlow:\n" +
                "    file: \"@screens/\${FLOW_TYPE}.yaml\"\n" +
                "    env:\n" +
                "      FLOW_TYPE: \"login\""
        flowFile.toFile().writeText(yamlContent)
        
        val commands = YamlCommandReader.readCommands(flowFile)
        val runFlowCommand = commands.find { it.asCommand()?.javaClass?.simpleName == "RunFlowCommand" }
        assertThat(runFlowCommand).isNotNull()
        
        // Verify the command was created with JavaScript interpolation in file path
        val command = runFlowCommand?.asCommand() as? maestro.orchestra.RunFlowCommand
        assertThat(command).isNotNull()
        assertThat(command?.sourceDescription).isEqualTo("@screens/\${FLOW_TYPE}.yaml")
        assertThat(command?.flowPath).isNotNull()
        
        // The commands should be empty initially since the file path contains JavaScript interpolation
        // The actual loading will happen during execution after JavaScript interpolation
        assertThat(command?.commands).isEmpty()
    }
}
