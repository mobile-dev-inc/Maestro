package maestro.plugins

import maestro.plugins.examples.LogCommand
import maestro.plugins.examples.LogCommandPlugin
import maestro.plugins.examples.WaitCommand
import maestro.plugins.examples.WaitCommandPlugin
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import com.fasterxml.jackson.core.JsonLocation

class PluginRegistryTest {
    
    @BeforeEach
    fun setUp() {
        PluginRegistry.clear()
    }
    
    @Test
    fun `should register plugin successfully`() {
        val plugin = WaitCommandPlugin()
        
        PluginRegistry.registerPlugin(plugin)
        
        assertThat(PluginRegistry.getPlugin("wait")).isEqualTo(plugin)
        assertThat(PluginRegistry.getPlugin(WaitCommand::class.java)).isEqualTo(plugin)
        assertThat(PluginRegistry.isPluginCommand("wait")).isTrue()
        assertThat(PluginRegistry.getRegisteredCommands()).contains("wait")
    }
    
    @Test
    fun `should throw exception when registering duplicate command name`() {
        val plugin1 = WaitCommandPlugin()
        val plugin2 = object : CommandPlugin<WaitCommand> {
            override val commandName = "wait"
            override val commandClass = WaitCommand::class.java
            override fun parseCommand(yamlContent: Any?, location: JsonLocation) = WaitCommand(1.0)
            override suspend fun executeCommand(command: WaitCommand, context: PluginExecutionContext) = false
        }
        
        PluginRegistry.registerPlugin(plugin1)
        
        assertThatThrownBy {
            PluginRegistry.registerPlugin(plugin2)
        }.isInstanceOf(PluginRegistrationException::class.java)
            .hasMessageContaining("Command 'wait' is already registered")
    }
    
    @Test
    fun `should validate plugin command name`() {
        val pluginWithBlankName = object : CommandPlugin<WaitCommand> {
            override val commandName = ""
            override val commandClass = WaitCommand::class.java
            override fun parseCommand(yamlContent: Any?, location: JsonLocation) = WaitCommand(1.0)
            override suspend fun executeCommand(command: WaitCommand, context: PluginExecutionContext) = false
        }
        
        assertThatThrownBy {
            PluginRegistry.registerPlugin(pluginWithBlankName)
        }.isInstanceOf(PluginRegistrationException::class.java)
            .hasMessageContaining("command name cannot be blank")
    }
    
    @Test
    fun `should validate plugin command name does not contain spaces`() {
        val pluginWithSpaces = object : CommandPlugin<WaitCommand> {
            override val commandName = "my command"
            override val commandClass = WaitCommand::class.java
            override fun parseCommand(yamlContent: Any?, location: JsonLocation) = WaitCommand(1.0)
            override suspend fun executeCommand(command: WaitCommand, context: PluginExecutionContext) = false
        }
        
        assertThatThrownBy {
            PluginRegistry.registerPlugin(pluginWithSpaces)
        }.isInstanceOf(PluginRegistrationException::class.java)
            .hasMessageContaining("cannot contain spaces")
    }
    
    @Test
    fun `should initialize with service loader`() {
        PluginRegistry.initialize()
        
        // Should automatically discover and register the example plugins
        assertThat(PluginRegistry.isPluginCommand("wait")).isTrue()
        assertThat(PluginRegistry.isPluginCommand("log")).isTrue()
        assertThat(PluginRegistry.getRegisteredCommands()).containsExactlyInAnyOrder("wait", "log")
    }
    
    @Test
    fun `should parse plugin command successfully`() {
        PluginRegistry.registerPlugin(WaitCommandPlugin())
        
        val yamlContent = mapOf("seconds" to 5.0, "label" to "Wait a bit")
        val location = JsonLocation(null, 0, 1, 1)
        
        val command = PluginRegistry.parsePluginCommand("wait", yamlContent, location)
        
        assertThat(command).isInstanceOf(WaitCommand::class.java)
        val waitCommand = command as WaitCommand
        assertThat(waitCommand.seconds).isEqualTo(5.0)
        assertThat(waitCommand.label).isEqualTo("Wait a bit")
    }
    
    @Test
    fun `should return null for unknown command`() {
        val command = PluginRegistry.parsePluginCommand("unknown", null, JsonLocation(null, 0, 1, 1))
        assertThat(command).isNull()
    }
}
