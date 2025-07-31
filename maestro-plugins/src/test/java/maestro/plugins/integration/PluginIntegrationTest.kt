package maestro.plugins.integration

import maestro.plugins.PluginRegistry
import maestro.plugins.examples.LogCommand
import maestro.plugins.examples.WaitCommand
import maestro.orchestra.yaml.YamlCommandReader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

class PluginIntegrationTest {
    
    @BeforeEach
    fun setUp() {
        PluginRegistry.clear()
        PluginRegistry.initialize()
    }
    
    @Test
    fun `should parse and execute plugin commands in YAML flow`() {
        val yamlContent = """
            appId: com.example.app
            ---
            - launchApp
            - wait: 2.0
            - log: "Test started"
            - wait:
                seconds: 1.5
                label: "Custom wait"
            - log:
                message: "Flow completed"
                level: "info"
        """.trimIndent()
        
        val tempFile = Files.createTempFile("test-flow", ".yaml")
        Files.write(tempFile, yamlContent.toByteArray())
        
        try {
            val commands = YamlCommandReader.readCommands(tempFile)
            
            // Should have 5 commands: launchApp, wait, log, wait, log
            assertThat(commands).hasSize(5)
            
            // First command should be LaunchApp
            assertThat(commands[0].launchAppCommand).isNotNull()
            
            // Second command should be plugin Wait command
            assertThat(commands[1].pluginCommand).isInstanceOf(WaitCommand::class.java)
            val waitCommand1 = commands[1].pluginCommand as WaitCommand
            assertThat(waitCommand1.seconds).isEqualTo(2.0)
            
            // Third command should be plugin Log command
            assertThat(commands[2].pluginCommand).isInstanceOf(LogCommand::class.java)
            val logCommand1 = commands[2].pluginCommand as LogCommand
            assertThat(logCommand1.message).isEqualTo("Test started")
            
            // Fourth command should be plugin Wait command with parameters
            assertThat(commands[3].pluginCommand).isInstanceOf(WaitCommand::class.java)
            val waitCommand2 = commands[3].pluginCommand as WaitCommand
            assertThat(waitCommand2.seconds).isEqualTo(1.5)
            assertThat(waitCommand2.label).isEqualTo("Custom wait")
            
            // Fifth command should be plugin Log command with parameters
            assertThat(commands[4].pluginCommand).isInstanceOf(LogCommand::class.java)
            val logCommand2 = commands[4].pluginCommand as LogCommand
            assertThat(logCommand2.message).isEqualTo("Flow completed")
            assertThat(logCommand2.level).isEqualTo("info")
            
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }
    
    @Test
    fun `should handle mix of plugin and built-in commands`() {
        val yamlContent = """
            appId: com.example.app
            ---
            - wait: 1.0
            - tapOn: "button"
            - log: "Button tapped"
            - scroll
            - wait:
                seconds: 0.5
                optional: true
        """.trimIndent()
        
        val tempFile = Files.createTempFile("test-mixed-flow", ".yaml")
        Files.write(tempFile, yamlContent.toByteArray())
        
        try {
            val commands = YamlCommandReader.readCommands(tempFile)
            
            assertThat(commands).hasSize(5)
            
            // Check the sequence of commands
            assertThat(commands[0].pluginCommand).isInstanceOf(WaitCommand::class.java)
            assertThat(commands[1].tapOnElement).isNotNull()
            assertThat(commands[2].pluginCommand).isInstanceOf(LogCommand::class.java)
            assertThat(commands[3].scrollCommand).isNotNull()
            assertThat(commands[4].pluginCommand).isInstanceOf(WaitCommand::class.java)
            
            val lastWaitCommand = commands[4].pluginCommand as WaitCommand
            assertThat(lastWaitCommand.optional).isTrue()
            
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }
    
    @Test
    fun `should validate plugin registry initialization`() {
        // Plugin registry should be initialized automatically
        assertThat(PluginRegistry.isPluginCommand("wait")).isTrue()
        assertThat(PluginRegistry.isPluginCommand("log")).isTrue()
        assertThat(PluginRegistry.getRegisteredCommands()).containsExactlyInAnyOrder("wait", "log")
    }
}
