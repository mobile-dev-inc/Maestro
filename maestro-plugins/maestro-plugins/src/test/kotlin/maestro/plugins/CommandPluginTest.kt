package maestro.plugins

import maestro.js.JsEngine
import maestro.orchestra.Command
import maestro.orchestra.PluginCommand
import com.fasterxml.jackson.core.JsonLocation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import io.mockk.mockk

class CommandPluginTest {
    
    // Test command data class
    data class TestCommandData(
        val value: String,
        val number: Int = 0
    )
    
    // Mock implementation of CommandPlugin for testing
    class TestCommandPlugin : CommandPlugin<TestCommandData> {
        override val commandName = "testCommand"
        override val commandClass = TestCommandData::class.java
        
        var parseCommandCalled = false
        var executeCommandCalled = false
        var getDescriptionCalled = false
        var validateCommandCalled = false
        var evaluateScriptsCalled = false
        
        var shouldThrowOnParse = false
        var shouldThrowOnExecute = false
        var shouldThrowOnValidate = false
        var executionResult = true
        
        override fun parseCommand(yamlContent: Any?, location: JsonLocation): TestCommandData {
            parseCommandCalled = true
            if (shouldThrowOnParse) {
                throw RuntimeException("Parse error")
            }
            return when (yamlContent) {
                is Map<*, *> -> {
                    val value = yamlContent["value"] as? String ?: "default"
                    val number = yamlContent["number"] as? Int ?: 0
                    TestCommandData(value, number)
                }
                is String -> TestCommandData(yamlContent)
                else -> TestCommandData("default")
            }
        }
        
        override suspend fun executeCommand(commandData: TestCommandData, context: PluginExecutionContext): Boolean {
            executeCommandCalled = true
            if (shouldThrowOnExecute) {
                throw RuntimeException("Execute error")
            }
            return executionResult
        }
        
        override fun getDescription(commandData: TestCommandData): String {
            getDescriptionCalled = true
            return "Test command with value: ${commandData.value}"
        }
        
        override fun validateCommand(commandData: TestCommandData) {
            validateCommandCalled = true
            if (shouldThrowOnValidate) {
                throw PluginValidationException("Validation error")
            }
            if (commandData.value.isBlank()) {
                throw PluginValidationException("Value cannot be blank")
            }
        }
        
        override fun evaluateScripts(commandData: TestCommandData, jsEngine: JsEngine): TestCommandData {
            evaluateScriptsCalled = true
            return commandData.copy(value = "evaluated_${commandData.value}")
        }
        
        fun reset() {
            parseCommandCalled = false
            executeCommandCalled = false
            getDescriptionCalled = false
            validateCommandCalled = false
            evaluateScriptsCalled = false
            shouldThrowOnParse = false
            shouldThrowOnExecute = false
            shouldThrowOnValidate = false
            executionResult = true
        }
    }
    
    @Test
    fun `CommandPlugin should have correct command name and class`() {
        val plugin = TestCommandPlugin()
        
        assertEquals("testCommand", plugin.commandName)
        assertEquals(TestCommandData::class.java, plugin.commandClass)
    }
    
    @Test
    fun `parseCommand should be called and return parsed data`() {
        val plugin = TestCommandPlugin()
        val location = mockk<JsonLocation>()
        val yamlContent = mapOf("value" to "test", "number" to 42)
        
        val result = plugin.parseCommand(yamlContent, location)
        
        assertTrue(plugin.parseCommandCalled)
        assertEquals("test", result.value)
        assertEquals(42, result.number)
    }
    
    @Test
    fun `parseCommand should handle string input`() {
        val plugin = TestCommandPlugin()
        val location = mockk<JsonLocation>()
        
        val result = plugin.parseCommand("string_value", location)
        
        assertTrue(plugin.parseCommandCalled)
        assertEquals("string_value", result.value)
        assertEquals(0, result.number)
    }
    
    @Test
    fun `parseCommand should handle null input`() {
        val plugin = TestCommandPlugin()
        val location = mockk<JsonLocation>()
        
        val result = plugin.parseCommand(null, location)
        
        assertTrue(plugin.parseCommandCalled)
        assertEquals("default", result.value)
        assertEquals(0, result.number)
    }
    
    @Test
    fun `executeCommand should be called and return result`() {
        val plugin = TestCommandPlugin()
        val commandData = TestCommandData("test")
        val context = mockk<PluginExecutionContext>()
        
        val result = kotlinx.coroutines.runBlocking {
            plugin.executeCommand(commandData, context)
        }
        
        assertTrue(plugin.executeCommandCalled)
        assertTrue(result)
    }
    
    @Test
    fun `executeCommand should return false when configured`() {
        val plugin = TestCommandPlugin()
        plugin.executionResult = false
        val commandData = TestCommandData("test")
        val context = mockk<PluginExecutionContext>()
        
        val result = kotlinx.coroutines.runBlocking {
            plugin.executeCommand(commandData, context)
        }
        
        assertTrue(plugin.executeCommandCalled)
        assertFalse(result)
    }
    
    @Test
    fun `getDescription should return custom description`() {
        val plugin = TestCommandPlugin()
        val commandData = TestCommandData("test_value")
        
        val description = plugin.getDescription(commandData)
        
        assertTrue(plugin.getDescriptionCalled)
        assertEquals("Test command with value: test_value", description)
    }
    
    @Test
    fun `default getDescription should return generic description`() {
        val plugin = object : CommandPlugin<TestCommandData> {
            override val commandName = "customCommand"
            override val commandClass = TestCommandData::class.java
            
            override fun parseCommand(yamlContent: Any?, location: JsonLocation): TestCommandData {
                return TestCommandData("test")
            }
            
            override suspend fun executeCommand(commandData: TestCommandData, context: PluginExecutionContext): Boolean {
                return true
            }
            // Using default getDescription implementation
        }
        
        val commandData = TestCommandData("test")
        val description = plugin.getDescription(commandData)
        
        assertEquals("customCommand command", description)
    }
    
    @Test
    fun `validateCommand should be called`() {
        val plugin = TestCommandPlugin()
        val commandData = TestCommandData("valid_value")
        
        assertDoesNotThrow {
            plugin.validateCommand(commandData)
        }
        
        assertTrue(plugin.validateCommandCalled)
    }
    
    @Test
    fun `validateCommand should throw when data is invalid`() {
        val plugin = TestCommandPlugin()
        val commandData = TestCommandData("") // blank value should be invalid
        
        assertThrows(PluginValidationException::class.java) {
            plugin.validateCommand(commandData)
        }
        
        assertTrue(plugin.validateCommandCalled)
    }
    
    @Test
    fun `default validateCommand should not throw`() {
        val plugin = object : CommandPlugin<TestCommandData> {
            override val commandName = "testCommand"
            override val commandClass = TestCommandData::class.java
            
            override fun parseCommand(yamlContent: Any?, location: JsonLocation): TestCommandData {
                return TestCommandData("test")
            }
            
            override suspend fun executeCommand(commandData: TestCommandData, context: PluginExecutionContext): Boolean {
                return true
            }
            // Using default validateCommand implementation
        }
        
        val commandData = TestCommandData("")
        
        assertDoesNotThrow {
            plugin.validateCommand(commandData)
        }
    }
    
    @Test
    fun `evaluateScripts should be called and return modified data`() {
        val plugin = TestCommandPlugin()
        val commandData = TestCommandData("original")
        val jsEngine = mockk<JsEngine>()
        
        val result = plugin.evaluateScripts(commandData, jsEngine)
        
        assertTrue(plugin.evaluateScriptsCalled)
        assertEquals("evaluated_original", result.value)
    }
    
    @Test
    fun `default evaluateScripts should return unchanged data`() {
        val plugin = object : CommandPlugin<TestCommandData> {
            override val commandName = "testCommand"
            override val commandClass = TestCommandData::class.java
            
            override fun parseCommand(yamlContent: Any?, location: JsonLocation): TestCommandData {
                return TestCommandData("test")
            }
            
            override suspend fun executeCommand(commandData: TestCommandData, context: PluginExecutionContext): Boolean {
                return true
            }
            // Using default evaluateScripts implementation
        }
        
        val commandData = TestCommandData("unchanged")
        val jsEngine = mockk<JsEngine>()
        
        val result = plugin.evaluateScripts(commandData, jsEngine)
        
        assertEquals(commandData, result)
    }
}
