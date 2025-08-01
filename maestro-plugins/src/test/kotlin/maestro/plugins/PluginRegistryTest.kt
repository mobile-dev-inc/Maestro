package maestro.plugins

import maestro.orchestra.Command
import maestro.orchestra.PluginCommand
import com.fasterxml.jackson.core.JsonLocation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class PluginRegistryTest {
    
    @TempDir
    lateinit var tempDir: Path
    
    // Test command data classes
    data class TestCommandData(val value: String)
    data class AnotherTestCommandData(val name: String)
    
    // Mock CommandPlugin implementations
    class MockPlugin(
        override val commandName: String,
        private val shouldThrowOnParse: Boolean = false,
        private val shouldThrowOnExecute: Boolean = false,
        private val shouldThrowOnValidate: Boolean = false
    ) : CommandPlugin<TestCommandData> {
        
        override val commandClass = TestCommandData::class.java
        
        var parseCommandCalled = false
        var executeCommandCalled = false
        var validateCommandCalled = false
        var getDescriptionCalled = false
        
        override fun parseCommand(yamlContent: Any?, location: JsonLocation): TestCommandData {
            parseCommandCalled = true
            if (shouldThrowOnParse) {
                throw RuntimeException("Parse error")
            }
            val value = when (yamlContent) {
                is Map<*, *> -> yamlContent["value"] as? String ?: "default"
                is String -> yamlContent
                else -> "default"
            }
            return TestCommandData(value)
        }
        
        override suspend fun executeCommand(commandData: TestCommandData, context: PluginExecutionContext): Boolean {
            executeCommandCalled = true
            if (shouldThrowOnExecute) {
                throw RuntimeException("Execute error")
            }
            return true
        }
        
        override fun getDescription(commandData: TestCommandData): String {
            getDescriptionCalled = true
            return "Mock command: ${commandData.value}"
        }
        
        override fun validateCommand(commandData: TestCommandData) {
            validateCommandCalled = true
            if (shouldThrowOnValidate) {
                throw PluginValidationException("Validation error")
            }
        }
    }
    
    class AnotherMockPlugin : CommandPlugin<AnotherTestCommandData> {
        override val commandName = "anotherCommand"
        override val commandClass = AnotherTestCommandData::class.java
        
        override fun parseCommand(yamlContent: Any?, location: JsonLocation): AnotherTestCommandData {
            val name = when (yamlContent) {
                is Map<*, *> -> yamlContent["name"] as? String ?: "default"
                is String -> yamlContent
                else -> "default"
            }
            return AnotherTestCommandData(name)
        }
        
        override suspend fun executeCommand(commandData: AnotherTestCommandData, context: PluginExecutionContext): Boolean {
            return true
        }
    }
    
    @BeforeEach
    fun setUp() {
        // Clear the registry before each test
        PluginRegistry.clear()
    }
    
    @AfterEach
    fun tearDown() {
        // Clear the registry after each test
        PluginRegistry.clear()
    }
    
    @Test
    fun `initialize should clear existing plugins and reload`() {
        // Arrange
        val plugin1 = MockPlugin("testCommand1")
        PluginRegistry.registerPlugin(plugin1)
        assertTrue(PluginRegistry.isPluginCommand("testCommand1"))
        
        // Act
        PluginRegistry.initialize(tempDir)
        
        // Assert - plugin should be cleared after initialization
        assertFalse(PluginRegistry.isPluginCommand("testCommand1"))
    }
    
    @Test
    fun `registerPlugin should register plugin successfully`() {
        // Arrange
        val plugin = MockPlugin("testCommand")
        
        // Act
        PluginRegistry.registerPlugin(plugin)
        
        // Assert
        assertTrue(PluginRegistry.isPluginCommand("testCommand"))
        assertEquals(plugin, PluginRegistry.getPlugin("testCommand"))
        assertEquals(plugin, PluginRegistry.getPlugin(TestCommandData::class.java))
        assertTrue(PluginRegistry.getRegisteredCommands().contains("testCommand"))
    }
    
    @Test
    fun `registerPlugin should throw when command name already exists`() {
        // Arrange
        val plugin1 = MockPlugin("duplicateCommand")
        val plugin2 = MockPlugin("duplicateCommand")
        PluginRegistry.registerPlugin(plugin1)
        
        // Act & Assert
        assertThrows(PluginRegistrationException::class.java) {
            PluginRegistry.registerPlugin(plugin2)
        }
    }
    
    @Test
    fun `registerPlugin should validate plugin before registration`() {
        // Arrange - plugin with blank command name
        val invalidPlugin = object : CommandPlugin<TestCommandData> {
            override val commandName = ""
            override val commandClass = TestCommandData::class.java
            
            override fun parseCommand(yamlContent: Any?, location: JsonLocation): TestCommandData {
                return TestCommandData("test")
            }
            
            override suspend fun executeCommand(commandData: TestCommandData, context: PluginExecutionContext): Boolean {
                return true
            }
        }
        
        // Act & Assert
        assertThrows(PluginRegistrationException::class.java) {
            PluginRegistry.registerPlugin(invalidPlugin)
        }
    }
    
    @Test
    fun `registerPlugin should not allow command names with spaces`() {
        // Arrange
        val invalidPlugin = object : CommandPlugin<TestCommandData> {
            override val commandName = "invalid command"
            override val commandClass = TestCommandData::class.java
            
            override fun parseCommand(yamlContent: Any?, location: JsonLocation): TestCommandData {
                return TestCommandData("test")
            }
            
            override suspend fun executeCommand(commandData: TestCommandData, context: PluginExecutionContext): Boolean {
                return true
            }
        }
        
        // Act & Assert
        assertThrows(PluginRegistrationException::class.java) {
            PluginRegistry.registerPlugin(invalidPlugin)
        }
    }
    
    @Test
    fun `getPlugin should return null for non-existent command`() {
        // Act & Assert
        assertNull(PluginRegistry.getPlugin("nonExistentCommand"))
        assertNull(PluginRegistry.getPlugin(TestCommandData::class.java))
    }
    
    @Test
    fun `isPluginCommand should return correct values`() {
        // Arrange
        val plugin = MockPlugin("existingCommand")
        PluginRegistry.registerPlugin(plugin)
        
        // Act & Assert
        assertTrue(PluginRegistry.isPluginCommand("existingCommand"))
        assertFalse(PluginRegistry.isPluginCommand("nonExistentCommand"))
    }
    
    @Test
    fun `getRegisteredCommands should return all command names`() {
        // Arrange
        val plugin1 = MockPlugin("command1")
        val plugin2 = AnotherMockPlugin()
        PluginRegistry.registerPlugin(plugin1)
        PluginRegistry.registerPlugin(plugin2)
        
        // Act
        val commands = PluginRegistry.getRegisteredCommands()
        
        // Assert
        assertEquals(2, commands.size)
        assertTrue(commands.contains("command1"))
        assertTrue(commands.contains("anotherCommand"))
    }
    
    @Test
    fun `parsePluginCommand should parse command successfully`() {
        // Arrange
        val plugin = MockPlugin("testCommand")
        PluginRegistry.registerPlugin(plugin)
        val location = mockk<JsonLocation>()
        val yamlContent = mapOf(
            "value" to "test_value",
            "label" to "Test Label",
            "optional" to true
        )
        
        // Act
        val command = PluginRegistry.parsePluginCommand("testCommand", yamlContent, location)
        
        // Assert
        assertNotNull(command)
        assertTrue(command is PluginCommand)
        val pluginCommand = command as PluginCommand
        assertEquals("testCommand", pluginCommand.pluginName)
        assertEquals("Test Label", pluginCommand.label)
        assertTrue(pluginCommand.optional)
        assertEquals("Mock command: test_value", pluginCommand.pluginDescription)
        assertTrue(plugin.parseCommandCalled)
        assertTrue(plugin.validateCommandCalled)
        assertTrue(plugin.getDescriptionCalled)
    }
    
    @Test
    fun `parsePluginCommand should handle missing label and optional`() {
        // Arrange
        val plugin = MockPlugin("testCommand")
        PluginRegistry.registerPlugin(plugin)
        val location = mockk<JsonLocation>()
        val yamlContent = mapOf("value" to "test_value")
        
        // Act
        val command = PluginRegistry.parsePluginCommand("testCommand", yamlContent, location)
        
        // Assert
        assertNotNull(command)
        val pluginCommand = command as PluginCommand
        assertNull(pluginCommand.label)
        assertFalse(pluginCommand.optional)
    }
    
    @Test
    fun `parsePluginCommand should return null for non-existent plugin`() {
        // Arrange
        val location = mockk<JsonLocation>()
        val yamlContent = mapOf("value" to "test")
        
        // Act
        val command = PluginRegistry.parsePluginCommand("nonExistentCommand", yamlContent, location)
        
        // Assert
        assertNull(command)
    }
    
    @Test
    fun `parsePluginCommand should throw PluginValidationException on parse error`() {
        // Arrange
        val plugin = MockPlugin("testCommand", shouldThrowOnParse = true)
        PluginRegistry.registerPlugin(plugin)
        val location = mockk<JsonLocation>()
        val yamlContent = mapOf("value" to "test")
        
        // Act & Assert
        assertThrows(PluginValidationException::class.java) {
            PluginRegistry.parsePluginCommand("testCommand", yamlContent, location)
        }
    }
    
    @Test
    fun `parsePluginCommand should throw PluginValidationException on validation error`() {
        // Arrange
        val plugin = MockPlugin("testCommand", shouldThrowOnValidate = true)
        PluginRegistry.registerPlugin(plugin)
        val location = mockk<JsonLocation>()
        val yamlContent = mapOf("value" to "test")
        
        // Act & Assert
        assertThrows(PluginValidationException::class.java) {
            PluginRegistry.parsePluginCommand("testCommand", yamlContent, location)
        }
    }
    
    @Test
    fun `executePluginCommand should execute successfully`() {
        // Arrange
        val plugin = MockPlugin("testCommand")
        PluginRegistry.registerPlugin(plugin)
        val commandData = TestCommandData("test")
        val pluginCommand = PluginCommand(
            pluginName = "testCommand",
            commandData = commandData,
            pluginDescription = "Test command",
            label = null,
            optional = false
        )
        val context = mockk<PluginExecutionContext>()
        
        // Act
        val result = kotlinx.coroutines.runBlocking {
            PluginRegistry.executePluginCommand(pluginCommand, context)
        }
        
        // Assert
        assertTrue(result)
        assertTrue(plugin.executeCommandCalled)
    }
    
    @Test
    fun `executePluginCommand should throw for non-plugin command`() {
        // Arrange
        val command = mockk<Command>()
        val context = mockk<PluginExecutionContext>()
        
        // Act & Assert
        assertThrows(PluginExecutionException::class.java) {
            kotlinx.coroutines.runBlocking {
                PluginRegistry.executePluginCommand(command, context)
            }
        }
    }
    
    @Test
    fun `executePluginCommand should throw for non-existent plugin`() {
        // Arrange
        val commandData = TestCommandData("test")
        val pluginCommand = PluginCommand(
            pluginName = "nonExistentCommand",
            commandData = commandData,
            pluginDescription = "Test command",
            label = null,
            optional = false
        )
        val context = mockk<PluginExecutionContext>()
        
        // Act & Assert
        assertThrows(PluginExecutionException::class.java) {
            kotlinx.coroutines.runBlocking {
                PluginRegistry.executePluginCommand(pluginCommand, context)
            }
        }
    }
    
    @Test
    fun `executePluginCommand should throw PluginExecutionException on execution error`() {
        // Arrange
        val plugin = MockPlugin("testCommand", shouldThrowOnExecute = true)
        PluginRegistry.registerPlugin(plugin)
        val commandData = TestCommandData("test")
        val pluginCommand = PluginCommand(
            pluginName = "testCommand",
            commandData = commandData,
            pluginDescription = "Test command",
            label = null,
            optional = false
        )
        val context = mockk<PluginExecutionContext>()
        
        // Act & Assert
        assertThrows(PluginExecutionException::class.java) {
            kotlinx.coroutines.runBlocking {
                PluginRegistry.executePluginCommand(pluginCommand, context)
            }
        }
    }
    
    @Test
    fun `setPluginsDirectory should set custom directory before initialization`() {
        // Arrange
        val customDir = tempDir.resolve("custom-plugins")
        Files.createDirectories(customDir)
        
        // Act
        PluginRegistry.setPluginsDirectory(customDir)
        PluginRegistry.initialize()
        
        // No exception should be thrown
        assertTrue(true)
    }
    
    @Test
    fun `initialize should handle non-existent plugins directory gracefully`() {
        // Arrange
        val nonExistentDir = tempDir.resolve("non-existent")
        
        // Act & Assert - should not throw
        assertDoesNotThrow {
            PluginRegistry.initialize(nonExistentDir)
        }
    }
    
    @Test
    fun `initialize should handle empty plugins directory gracefully`() {
        // Arrange
        val emptyDir = tempDir.resolve("empty")
        Files.createDirectories(emptyDir)
        
        // Act & Assert - should not throw
        assertDoesNotThrow {
            PluginRegistry.initialize(emptyDir)
        }
    }
    
    @Test
    fun `clear should remove all plugins and reset state`() {
        // Arrange
        val plugin = MockPlugin("testCommand")
        PluginRegistry.registerPlugin(plugin)
        assertTrue(PluginRegistry.isPluginCommand("testCommand"))
        
        // Act
        PluginRegistry.clear()
        
        // Assert
        assertFalse(PluginRegistry.isPluginCommand("testCommand"))
        assertTrue(PluginRegistry.getRegisteredCommands().isEmpty())
        assertNull(PluginRegistry.getPlugin("testCommand"))
    }
    
    @Test
    fun `getRegisteredCommands should return empty set when no plugins registered`() {
        // Act
        val commands = PluginRegistry.getRegisteredCommands()
        
        // Assert
        assertTrue(commands.isEmpty())
    }
    
    @Test
    fun `multiple plugins can be registered and retrieved correctly`() {
        // Arrange
        val plugin1 = MockPlugin("command1")
        val plugin2 = AnotherMockPlugin()
        
        // Act
        PluginRegistry.registerPlugin(plugin1)
        PluginRegistry.registerPlugin(plugin2)
        
        // Assert
        assertEquals(plugin1, PluginRegistry.getPlugin("command1"))
        assertEquals(plugin2, PluginRegistry.getPlugin("anotherCommand"))
        assertEquals(plugin1, PluginRegistry.getPlugin(TestCommandData::class.java))
        assertEquals(plugin2, PluginRegistry.getPlugin(AnotherTestCommandData::class.java))
        
        val commands = PluginRegistry.getRegisteredCommands()
        assertEquals(2, commands.size)
        assertTrue(commands.contains("command1"))
        assertTrue(commands.contains("anotherCommand"))
    }
}
