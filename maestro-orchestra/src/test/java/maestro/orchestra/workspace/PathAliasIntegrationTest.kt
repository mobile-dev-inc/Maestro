package maestro.orchestra.workspace

import com.google.common.truth.Truth.assertThat
import maestro.orchestra.WorkspaceConfig
import maestro.orchestra.error.SyntaxError
import maestro.orchestra.yaml.YamlCommandReader
import maestro.orchestra.workspace.WorkspaceExecutionPlanner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PathAliasIntegrationTest {

    @TempDir
    lateinit var testDir: Path

    private lateinit var configFile: Path

    @BeforeEach
    fun setUp() {
        // Create comprehensive test directory structure
        testDir.resolve("flows/screens").toFile().mkdirs()
        testDir.resolve("flows/components").toFile().mkdirs()
        testDir.resolve("assets/media").toFile().mkdirs()
        testDir.resolve("scripts").toFile().mkdirs()
        testDir.resolve("utils").toFile().mkdirs()
        
        // Create test flow files
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
        
        testDir.resolve("flows/components/input.yaml").toFile().writeText("""
            appId: com.test.app
            ---
            - inputText: "Test Input"
        """.trimIndent())
        
        // Create test media files
        testDir.resolve("assets/media/test-image.jpg").toFile().writeText("fake image content")
        testDir.resolve("assets/media/logo.png").toFile().writeText("fake logo content")
        testDir.resolve("assets/media/background.jpg").toFile().writeText("fake background content")
        
        // Create regular path for mixed test
        testDir.resolve("regular/path").toFile().mkdirs()
        testDir.resolve("regular/path/image.png").toFile().writeText("fake regular image content")
        
        // Create test script files
        testDir.resolve("scripts/android_test.js").toFile().writeText("console.log('android test');")
        testDir.resolve("scripts/ios_test.js").toFile().writeText("console.log('ios test');")
        testDir.resolve("scripts/common_test.js").toFile().writeText("console.log('common test');")
        
        // Create test utility files
        testDir.resolve("utils/helper.js").toFile().writeText("function helper() { return 'helper'; }")
        
        // Create workspace config with comprehensive pathAlias setup
        configFile = testDir.resolve("config.yaml")
        configFile.toFile().writeText("""
            pathAliases:
              "@screens": "flows/screens"
              "@components": "flows/components"
              "@media": "assets/media"
              "!scripts": "scripts"
              "~utils": "utils"
        """.trimIndent())
    }

    @AfterEach
    fun tearDown() {
        WorkspaceConfigProvider.workspaceConfig = null
    }

    @Test
    fun `should resolve pathAlias in workspace execution planning`() {
        // Test that pathAlias resolution works in the workspace execution planner
        val workspaceConfig = YamlCommandReader.readWorkspaceConfig(configFile)
        WorkspaceConfigProvider.workspaceConfig = workspaceConfig
        
        // Create a main flow that uses pathAlias
        val mainFlow = testDir.resolve("main-flow.yaml")
        mainFlow.toFile().writeText("""
            appId: com.test.app
            ---
            - runFlow:
                file: "@screens/login.yaml"
            - addMedia:
                files:
                  - "@media/test-image.jpg"
            - runScript:
                file: "!scripts/android_test.js"
        """.trimIndent())
        
        // Test workspace execution planning
        val executionPlan = WorkspaceExecutionPlanner.plan(
            input = setOf(mainFlow),
            includeTags = emptyList(),
            excludeTags = emptyList(),
            config = configFile
        )
        
        assertThat(executionPlan.workspaceConfig).isNotNull()
        assertThat(executionPlan.workspaceConfig.pathAliases).isNotNull()
        assertThat(executionPlan.flowsToRun).contains(mainFlow)
    }

    @Test
    fun `should validate pathAlias paths exist`() {
        // Test pathAlias validation
        val workspaceConfig = YamlCommandReader.readWorkspaceConfig(configFile)
        
        // Validate that all pathAlias paths exist
        val errors = PathResolver.validate(workspaceConfig, testDir.toFile())
        assertThat(errors).isEmpty()
    }

    @Test
    fun `should detect non-existent pathAlias paths`() {
        // Create a config with non-existent pathAlias paths
        val invalidConfigFile = testDir.resolve("invalid-config.yaml")
        invalidConfigFile.toFile().writeText("""
            pathAliases:
              "@screens": "flows/screens"
              "@invalid": "non-existent/path"
        """.trimIndent())
        
        val invalidWorkspaceConfig = YamlCommandReader.readWorkspaceConfig(invalidConfigFile)
        
        // Validate should detect the non-existent path
        val errors = PathResolver.validate(invalidWorkspaceConfig, testDir.toFile())
        assertThat(errors).isNotEmpty()
        assertThat(errors.any { it.contains("non-existent") }).isTrue()
    }

    @Test
    fun `should handle circular pathAlias references`() {
        // Create a config with circular references
        val circularConfigFile = testDir.resolve("circular-config.yaml")
        circularConfigFile.toFile().writeText("""
            pathAliases:
              "@a": "@b/path"
              "@b": "@a/path"
        """.trimIndent())
        
        val circularWorkspaceConfig = YamlCommandReader.readWorkspaceConfig(circularConfigFile)
        
        // Validate should detect circular references
        val errors = PathResolver.validate(circularWorkspaceConfig, testDir.toFile())
        assertThat(errors).isNotEmpty()
        assertThat(errors.any { it.contains("Circular references are not supported") }).isTrue()
    }

    @Test
    fun `should resolve all pathAlias types in different commands`() {
        // Test all pathAlias types (@, !, ~) in different commands
        val workspaceConfig = YamlCommandReader.readWorkspaceConfig(configFile)
        WorkspaceConfigProvider.workspaceConfig = workspaceConfig
        
        val flowFile = testDir.resolve("comprehensive-test.yaml")
        flowFile.toFile().writeText("""
            appId: com.test.app
            ---
            # Test @screens pathAlias
            - runFlow:
                file: "@screens/login.yaml"
            
            # Test @components pathAlias  
            - runFlow:
                file: "@components/button.yaml"
            
            # Test @media pathAlias
            - addMedia:
                files:
                  - "@media/test-image.jpg"
                  - "@media/logo.png"
            
            # Test !scripts pathAlias
            - runScript:
                file: "!scripts/android_test.js"
            
            # Test ~utils pathAlias
            - runScript:
                file: "~utils/helper.js"
        """.trimIndent())
        
        val commands = YamlCommandReader.readCommands(flowFile)
        
        // Verify all commands are parsed correctly
        assertThat(commands).isNotEmpty()
        
        // Verify runFlow commands are resolved
        val runFlowCommands = commands.filter { it.asCommand()?.javaClass?.simpleName == "RunFlowCommand" }
        assertThat(runFlowCommands).hasSize(2)
        
        // Verify addMedia command is resolved
        val addMediaCommands = commands.filter { it.asCommand()?.javaClass?.simpleName == "AddMediaCommand" }
        assertThat(addMediaCommands).hasSize(1)
        
        // Verify runScript commands are resolved
        val runScriptCommands = commands.filter { it.asCommand()?.javaClass?.simpleName == "RunScriptCommand" }
        assertThat(runScriptCommands).hasSize(2)
    }

    @Test
    fun `should handle mixed pathAlias and regular paths`() {
        // Test mixing pathAlias with regular paths
        val workspaceConfig = YamlCommandReader.readWorkspaceConfig(configFile)
        WorkspaceConfigProvider.workspaceConfig = workspaceConfig
        
        val flowFile = testDir.resolve("mixed-paths.yaml")
        flowFile.toFile().writeText("""
            appId: com.test.app
            ---
            - addMedia:
                files:
                  - "@media/test-image.jpg"  # pathAlias
                  - "regular/path/image.png"  # regular path
                  - "@media/logo.png"  # pathAlias
        """.trimIndent())
        
        val commands = YamlCommandReader.readCommands(flowFile)
        val addMediaCommand = commands.find { it.asCommand()?.javaClass?.simpleName == "AddMediaCommand" }
        assertThat(addMediaCommand).isNotNull()
        
        val mediaPaths = (addMediaCommand?.asCommand() as? maestro.orchestra.AddMediaCommand)?.mediaPaths
        assertThat(mediaPaths).isNotNull()
        assertThat(mediaPaths?.size).isEqualTo(3)
        
        // First path should be resolved from pathAlias
        assertThat(mediaPaths?.get(0)).contains("assets/media/test-image.jpg")
        // Second path should remain unchanged (regular path)
        assertThat(mediaPaths?.get(1)).contains("regular/path/image.png")
        // Third path should be resolved from pathAlias
        assertThat(mediaPaths?.get(2)).contains("assets/media/logo.png")
    }

    @Test
    fun `should handle empty pathAlias configuration gracefully`() {
        // Test behavior when no pathAlias are configured
        val emptyConfigFile = testDir.resolve("empty-config.yaml")
        emptyConfigFile.toFile().writeText("""
            pathAliases: {}
        """.trimIndent())
        
        val emptyWorkspaceConfig = YamlCommandReader.readWorkspaceConfig(emptyConfigFile)
        WorkspaceConfigProvider.workspaceConfig = emptyWorkspaceConfig
        
        val flowFile = testDir.resolve("no-aliases.yaml")
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
    fun `should handle JavaScript interpolation in runFlow command execution`() {
        // Set up workspace config for this test
        val workspaceConfig = YamlCommandReader.readWorkspaceConfig(configFile)
        WorkspaceConfigProvider.workspaceConfig = workspaceConfig
        
        // Create a sub-flow file
        val subFlowFile = testDir.resolve("sub-flow.yaml")
        subFlowFile.toFile().writeText("""
            appId: com.test.app
            ---
            - tapOn: "Dynamic Button"
        """.trimIndent())
        
        val flowFile = testDir.resolve("js-interpolation-flow.yaml")
        flowFile.toFile().writeText("""
            appId: com.test.app
            ---
            - runFlow:
                file: "sub-flow.yaml"
                label: "Dynamic Flow"
        """.trimIndent())
        
        val commands = YamlCommandReader.readCommands(flowFile)
        val runFlowCommand = commands.find { it.asCommand()?.javaClass?.simpleName == "RunFlowCommand" }
        assertThat(runFlowCommand).isNotNull()
        
        // Verify the command was created successfully
        val command = runFlowCommand?.asCommand() as? maestro.orchestra.RunFlowCommand
        assertThat(command).isNotNull()
        assertThat(command?.sourceDescription).isEqualTo("sub-flow.yaml")
        assertThat(command?.label).isEqualTo("Dynamic Flow")
    }

    @Test
    fun `should handle JavaScript interpolation in runScript command execution`() {
        // Set up workspace config for this test
        val workspaceConfig = YamlCommandReader.readWorkspaceConfig(configFile)
        WorkspaceConfigProvider.workspaceConfig = workspaceConfig
        
        // Create a script file with JavaScript interpolation
        val scriptFile = testDir.resolve("dynamic-script.js")
        scriptFile.toFile().writeText("""
            console.log('Script executed');
            console.log('Timestamp: ' + new Date().toISOString());
        """.trimIndent())
        
        val flowFile = testDir.resolve("js-interpolation-script.yaml")
        flowFile.toFile().writeText("""
            appId: com.test.app
            ---
            - runScript:
                file: "dynamic-script.js"
                env:
                  PLATFORM: "test"
        """.trimIndent())
        
        val commands = YamlCommandReader.readCommands(flowFile)
        val runScriptCommand = commands.find { it.asCommand()?.javaClass?.simpleName == "RunScriptCommand" }
        assertThat(runScriptCommand).isNotNull()
        
        // Verify the command was created successfully
        val command = runScriptCommand?.asCommand() as? maestro.orchestra.RunScriptCommand
        assertThat(command).isNotNull()
        assertThat(command?.sourceDescription).isEqualTo("dynamic-script.js")
        assertThat(command?.script).contains("console.log('Script executed')")
        assertThat(command?.env).containsEntry("PLATFORM", "test")
    }

    @Test
    fun `should handle JavaScript interpolation with pathAlias in runFlow and runScript`() {
        // Set up workspace config for this test
        val workspaceConfig = YamlCommandReader.readWorkspaceConfig(configFile)
        WorkspaceConfigProvider.workspaceConfig = workspaceConfig
        
        // Create files in pathAlias directories
        val screensDir = testDir.resolve("flows/screens")
        screensDir.toFile().mkdirs()
        val subFlowFile = screensDir.resolve("login.yaml")
        subFlowFile.toFile().writeText("""
            appId: com.test.app
            ---
            - tapOn: "Login"
        """.trimIndent())
        
        val scriptsDir = testDir.resolve("scripts")
        scriptsDir.toFile().mkdirs()
        val scriptFile = scriptsDir.resolve("auth.js")
        scriptFile.toFile().writeText("""
            console.log('Authentication script');
        """.trimIndent())
        
        val flowFile = testDir.resolve("pathalias-js-interpolation.yaml")
        flowFile.toFile().writeText("""
            appId: com.test.app
            ---
            - runFlow:
                file: "@screens/login.yaml"
                label: "Login Flow"
            - runScript:
                file: "!scripts/auth.js"
                env:
                  ENV: "test"
        """.trimIndent())
        
        val commands = YamlCommandReader.readCommands(flowFile)
        
        // Verify runFlow command
        val runFlowCommand = commands.find { it.asCommand()?.javaClass?.simpleName == "RunFlowCommand" }
        assertThat(runFlowCommand).isNotNull()
        val flowCommand = runFlowCommand?.asCommand() as? maestro.orchestra.RunFlowCommand
        assertThat(flowCommand?.sourceDescription).isEqualTo("@screens/login.yaml")
        assertThat(flowCommand?.label).isEqualTo("Login Flow")
        
        // Verify runScript command
        val runScriptCommand = commands.find { it.asCommand()?.javaClass?.simpleName == "RunScriptCommand" }
        assertThat(runScriptCommand).isNotNull()
        val scriptCommand = runScriptCommand?.asCommand() as? maestro.orchestra.RunScriptCommand
        assertThat(scriptCommand?.sourceDescription).isEqualTo("!scripts/auth.js")
        assertThat(scriptCommand?.script).contains("console.log('Authentication script')")
        assertThat(scriptCommand?.env).containsEntry("ENV", "test")
    }
}
