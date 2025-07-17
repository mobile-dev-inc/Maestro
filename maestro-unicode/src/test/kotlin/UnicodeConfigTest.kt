import com.google.common.truth.Truth.assertThat
import maestro.unicode.config.UnicodeConfig
import org.junit.jupiter.api.Test

class UnicodeConfigTest {

    @Test
    fun `default config should have expected values`() {
        val config = UnicodeConfig()
        
        assertThat(config.enabled).isTrue()
        assertThat(config.autoInstallKeyboard).isTrue()
        assertThat(config.restoreKeyboard).isTrue()
        assertThat(config.fallbackToCharInput).isTrue()
        assertThat(config.enablePerformanceOptimizations).isTrue()
        assertThat(config.chunkSize).isEqualTo(500)
        assertThat(config.delayBetweenChunks).isEqualTo(20)
        assertThat(config.maxInputLength).isEqualTo(10000)
    }

    @Test
    fun `config should be customizable`() {
        val config = UnicodeConfig(
            enabled = false,
            chunkSize = 1000,
            delayBetweenChunks = 50,
            maxInputLength = 5000
        )
        
        assertThat(config.enabled).isFalse()
        assertThat(config.chunkSize).isEqualTo(1000)
        assertThat(config.delayBetweenChunks).isEqualTo(50)
        assertThat(config.maxInputLength).isEqualTo(5000)
    }

    @Test
    fun `load should create config from YAML map`() {
        val yamlConfig = mapOf(
            "unicode" to mapOf(
                "enabled" to false,
                "chunkSize" to 300,
                "delayBetweenChunks" to 30,
                "autoInstallKeyboard" to false
            )
        )
        
        val config = UnicodeConfig.load(yamlConfig)
        
        assertThat(config.enabled).isFalse()
        assertThat(config.chunkSize).isEqualTo(300)
        assertThat(config.delayBetweenChunks).isEqualTo(30)
        assertThat(config.autoInstallKeyboard).isFalse()
    }

    @Test
    fun `load should use defaults when YAML section is missing`() {
        val yamlConfig = mapOf<String, Any>()
        
        val config = UnicodeConfig.load(yamlConfig)
        
        assertThat(config.enabled).isTrue()
        assertThat(config.chunkSize).isEqualTo(500)
        assertThat(config.autoInstallKeyboard).isTrue()
    }

    @Test
    fun `load should handle null YAML config`() {
        val config = UnicodeConfig.load(null)
        
        assertThat(config.enabled).isTrue()
        assertThat(config.chunkSize).isEqualTo(500)
        assertThat(config.autoInstallKeyboard).isTrue()
    }

    @Test
    fun `createDefault should create default configuration`() {
        val config = UnicodeConfig.createDefault()
        
        assertThat(config.enabled).isTrue()
        assertThat(config.chunkSize).isEqualTo(500)
        assertThat(config.autoInstallKeyboard).isTrue()
    }

    @Test
    fun `isEnabled should return current instance enabled state`() {
        // Create and set instance
        UnicodeConfig.createDefault()
        assertThat(UnicodeConfig.isEnabled).isTrue()
    }

    @Test
    fun `validate should return warnings for invalid values`() {
        val config = UnicodeConfig(
            chunkSize = -1,
            delayBetweenChunks = -10,
            maxInputLength = 0,
            autoInstallKeyboard = false,
            fallbackToCharInput = false
        )
        
        val warnings = config.validate()
        
        assertThat(warnings).isNotEmpty()
        // Check that warnings are returned for invalid values
        assertThat(warnings.size).isAtLeast(3)
    }

    @Test
    fun `validate should return empty list for valid config`() {
        val config = UnicodeConfig()
        
        val warnings = config.validate()
        
        assertThat(warnings).isEmpty()
    }

    @Test
    fun `withPerformanceOptimizations should return optimized config`() {
        val config = UnicodeConfig()
        val optimized = config.withPerformanceOptimizations()
        
        assertThat(optimized.enablePerformanceOptimizations).isTrue()
        assertThat(optimized.restoreKeyboard).isFalse()
        assertThat(optimized.batchInputRequests).isTrue()
        assertThat(optimized.delayBetweenChunks).isEqualTo(10)
    }

    @Test
    fun `withReliabilityOptimizations should return reliable config`() {
        val config = UnicodeConfig()
        val reliable = config.withReliabilityOptimizations()
        
        assertThat(reliable.enablePerformanceOptimizations).isFalse()
        assertThat(reliable.restoreKeyboard).isTrue()
        assertThat(reliable.fallbackToCharInput).isTrue()
        assertThat(reliable.delayBetweenChunks).isEqualTo(50)
    }

    @Test
    fun `withDebugSettings should return debug config`() {
        val config = UnicodeConfig()
        val debug = config.withDebugSettings()
        
        assertThat(debug.logUnicodeOperations).isTrue()
        assertThat(debug.fallbackToCharInput).isTrue()
        assertThat(debug.enableClipboardFallback).isTrue()
        assertThat(debug.delayBetweenChunks).isEqualTo(100)
    }
}