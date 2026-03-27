package maestro.plugins

import maestro.Maestro
import maestro.js.JsEngine
import maestro.orchestra.MaestroConfig
import maestro.utils.Insights
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import io.mockk.mockk
import java.nio.file.Path
import java.nio.file.Paths

class PluginExecutionContextTest {
    
    @Test
    fun `PluginExecutionContext should store all provided values correctly`() {
        // Arrange
        val maestro = mockk<Maestro>()
        val jsEngine = mockk<JsEngine>()
        val config = mockk<MaestroConfig>()
        val httpClient = mockk<OkHttpClient>()
        val screenshotsDir = Paths.get("/tmp/screenshots")
        val insights = mockk<Insights>()
        val lookupTimeoutMs = 5000L
        val optionalLookupTimeoutMs = 1000L
        val copiedText = "test text"
        val setCopiedText: (String?) -> Unit = { }
        
        // Act
        val context = PluginExecutionContext(
            maestro = maestro,
            jsEngine = jsEngine,
            config = config,
            httpClient = httpClient,
            screenshotsDir = screenshotsDir,
            insights = insights,
            lookupTimeoutMs = lookupTimeoutMs,
            optionalLookupTimeoutMs = optionalLookupTimeoutMs,
            copiedText = copiedText,
            setCopiedText = setCopiedText
        )
        
        // Assert
        assertEquals(maestro, context.maestro)
        assertEquals(jsEngine, context.jsEngine)
        assertEquals(config, context.config)
        assertEquals(httpClient, context.httpClient)
        assertEquals(screenshotsDir, context.screenshotsDir)
        assertEquals(insights, context.insights)
        assertEquals(lookupTimeoutMs, context.lookupTimeoutMs)
        assertEquals(optionalLookupTimeoutMs, context.optionalLookupTimeoutMs)
        assertEquals(copiedText, context.copiedText)
        assertEquals(setCopiedText, context.setCopiedText)
    }
    
    @Test
    fun `PluginExecutionContext should handle null values correctly`() {
        // Arrange
        val maestro = mockk<Maestro>()
        val jsEngine = mockk<JsEngine>()
        val insights = mockk<Insights>()
        val lookupTimeoutMs = 5000L
        val optionalLookupTimeoutMs = 1000L
        val setCopiedText: (String?) -> Unit = { }
        
        // Act
        val context = PluginExecutionContext(
            maestro = maestro,
            jsEngine = jsEngine,
            config = null,
            httpClient = null,
            screenshotsDir = null,
            insights = insights,
            lookupTimeoutMs = lookupTimeoutMs,
            optionalLookupTimeoutMs = optionalLookupTimeoutMs,
            copiedText = null,
            setCopiedText = setCopiedText
        )
        
        // Assert
        assertEquals(maestro, context.maestro)
        assertEquals(jsEngine, context.jsEngine)
        assertNull(context.config)
        assertNull(context.httpClient)
        assertNull(context.screenshotsDir)
        assertEquals(insights, context.insights)
        assertEquals(lookupTimeoutMs, context.lookupTimeoutMs)
        assertEquals(optionalLookupTimeoutMs, context.optionalLookupTimeoutMs)
        assertNull(context.copiedText)
        assertEquals(setCopiedText, context.setCopiedText)
    }
    
    @Test
    fun `PluginExecutionContext should be a data class with proper equality`() {
        // Arrange
        val maestro = mockk<Maestro>()
        val jsEngine = mockk<JsEngine>()
        val insights = mockk<Insights>()
        val setCopiedText: (String?) -> Unit = { }
        
        val context1 = PluginExecutionContext(
            maestro = maestro,
            jsEngine = jsEngine,
            config = null,
            httpClient = null,
            screenshotsDir = null,
            insights = insights,
            lookupTimeoutMs = 5000L,
            optionalLookupTimeoutMs = 1000L,
            copiedText = null,
            setCopiedText = setCopiedText
        )
        
        val context2 = PluginExecutionContext(
            maestro = maestro,
            jsEngine = jsEngine,
            config = null,
            httpClient = null,
            screenshotsDir = null,
            insights = insights,
            lookupTimeoutMs = 5000L,
            optionalLookupTimeoutMs = 1000L,
            copiedText = null,
            setCopiedText = setCopiedText
        )
        
        // Assert - contexts with same data should be equal
        assertEquals(context1, context2)
        assertEquals(context1.hashCode(), context2.hashCode())
    }
    
    @Test
    fun `PluginExecutionContext should support copy functionality`() {
        // Arrange
        val maestro = mockk<Maestro>()
        val jsEngine = mockk<JsEngine>()
        val insights = mockk<Insights>()
        val setCopiedText: (String?) -> Unit = { }
        
        val originalContext = PluginExecutionContext(
            maestro = maestro,
            jsEngine = jsEngine,
            config = null,
            httpClient = null,
            screenshotsDir = null,
            insights = insights,
            lookupTimeoutMs = 5000L,
            optionalLookupTimeoutMs = 1000L,
            copiedText = "original",
            setCopiedText = setCopiedText
        )
        
        // Act
        val copiedContext = originalContext.copy(copiedText = "modified")
        
        // Assert
        assertEquals("modified", copiedContext.copiedText)
        assertEquals("original", originalContext.copiedText)
        assertEquals(originalContext.maestro, copiedContext.maestro)
        assertEquals(originalContext.jsEngine, copiedContext.jsEngine)
    }
}
