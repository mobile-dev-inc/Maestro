package maestro.plugins

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class PluginExceptionsTest {
    
    @Test
    fun `PluginValidationException should store message and cause correctly`() {
        val cause = RuntimeException("Original error")
        val message = "Validation failed"
        
        val exception = PluginValidationException(message, cause)
        
        assertEquals(message, exception.message)
        assertEquals(cause, exception.cause)
    }
    
    @Test
    fun `PluginValidationException should work without cause`() {
        val message = "Validation failed"
        
        val exception = PluginValidationException(message)
        
        assertEquals(message, exception.message)
        assertNull(exception.cause)
    }
    
    @Test
    fun `PluginExecutionException should store message and cause correctly`() {
        val cause = RuntimeException("Original error")
        val message = "Execution failed"
        
        val exception = PluginExecutionException(message, cause)
        
        assertEquals(message, exception.message)
        assertEquals(cause, exception.cause)
    }
    
    @Test
    fun `PluginExecutionException should work without cause`() {
        val message = "Execution failed"
        
        val exception = PluginExecutionException(message)
        
        assertEquals(message, exception.message)
        assertNull(exception.cause)
    }
    
    @Test
    fun `PluginLoadingException should store message and cause correctly`() {
        val cause = RuntimeException("Original error")
        val message = "Loading failed"
        
        val exception = PluginLoadingException(message, cause)
        
        assertEquals(message, exception.message)
        assertEquals(cause, exception.cause)
    }
    
    @Test
    fun `PluginLoadingException should work without cause`() {
        val message = "Loading failed"
        
        val exception = PluginLoadingException(message)
        
        assertEquals(message, exception.message)
        assertNull(exception.cause)
    }
    
    @Test
    fun `PluginRegistrationException should store message and cause correctly`() {
        val cause = RuntimeException("Original error")
        val message = "Registration failed"
        
        val exception = PluginRegistrationException(message, cause)
        
        assertEquals(message, exception.message)
        assertEquals(cause, exception.cause)
    }
    
    @Test
    fun `PluginRegistrationException should work without cause`() {
        val message = "Registration failed"
        
        val exception = PluginRegistrationException(message)
        
        assertEquals(message, exception.message)
        assertNull(exception.cause)
    }
    
    @Test
    fun `all plugin exceptions should extend Exception`() {
        assertTrue(Exception::class.java.isAssignableFrom(PluginValidationException::class.java))
        assertTrue(Exception::class.java.isAssignableFrom(PluginExecutionException::class.java))
        assertTrue(Exception::class.java.isAssignableFrom(PluginLoadingException::class.java))
        assertTrue(Exception::class.java.isAssignableFrom(PluginRegistrationException::class.java))
    }
}
