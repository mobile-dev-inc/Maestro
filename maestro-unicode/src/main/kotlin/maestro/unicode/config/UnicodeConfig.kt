package maestro.unicode.config

/**
 * Configuration class for Unicode input handling in Maestro.
 * Provides settings for customizing Unicode behavior, performance, and fallbacks.
 */
data class UnicodeConfig(
    val enabled: Boolean = true,
    val autoInstallKeyboard: Boolean = true,
    val restoreKeyboard: Boolean = true,
    val fallbackToCharInput: Boolean = true,
    val enablePerformanceOptimizations: Boolean = true,
    val chunkSize: Int = 500,
    val delayBetweenChunks: Long = 20,
    val delayBetweenCharacters: Long = 10,
    val keyboardSwitchDelay: Long = 100,
    val maxInputLength: Int = 10000,
    val enableRtlSupport: Boolean = true,
    val enableEmojiSupport: Boolean = true,
    val enableClipboardFallback: Boolean = true,
    val logUnicodeOperations: Boolean = false,
    val cacheKeyboardState: Boolean = true,
    val batchInputRequests: Boolean = true,
    val maxBatchSize: Int = 10,
    val batchTimeoutMs: Long = 100
) {
    
    companion object {
        @Volatile
        private var instance: UnicodeConfig? = null
        private val lock = Any()
        
        /**
         * Loads configuration from YAML or creates default configuration.
         */
        fun load(yamlConfig: Map<String, Any>?): UnicodeConfig = synchronized(lock) {
            @Suppress("UNCHECKED_CAST")
            val unicodeSection = yamlConfig?.get("unicode") as? Map<String, Any>
            return UnicodeConfig(
                enabled = unicodeSection?.get("enabled") as? Boolean ?: true,
                autoInstallKeyboard = unicodeSection?.get("autoInstallKeyboard") as? Boolean ?: true,
                restoreKeyboard = unicodeSection?.get("restoreKeyboard") as? Boolean ?: true,
                fallbackToCharInput = unicodeSection?.get("fallbackToCharInput") as? Boolean ?: true,
                enablePerformanceOptimizations = unicodeSection?.get("enablePerformanceOptimizations") as? Boolean ?: true,
                chunkSize = (unicodeSection?.get("chunkSize") as? Number)?.toInt() ?: 500,
                delayBetweenChunks = (unicodeSection?.get("delayBetweenChunks") as? Number)?.toLong() ?: 20,
                delayBetweenCharacters = (unicodeSection?.get("delayBetweenCharacters") as? Number)?.toLong() ?: 10,
                keyboardSwitchDelay = (unicodeSection?.get("keyboardSwitchDelay") as? Number)?.toLong() ?: 100,
                maxInputLength = (unicodeSection?.get("maxInputLength") as? Number)?.toInt() ?: 10000,
                enableRtlSupport = unicodeSection?.get("enableRtlSupport") as? Boolean ?: true,
                enableEmojiSupport = unicodeSection?.get("enableEmojiSupport") as? Boolean ?: true,
                enableClipboardFallback = unicodeSection?.get("enableClipboardFallback") as? Boolean ?: true,
                logUnicodeOperations = unicodeSection?.get("logUnicodeOperations") as? Boolean ?: false,
                cacheKeyboardState = unicodeSection?.get("cacheKeyboardState") as? Boolean ?: true,
                batchInputRequests = unicodeSection?.get("batchInputRequests") as? Boolean ?: true,
                maxBatchSize = (unicodeSection?.get("maxBatchSize") as? Number)?.toInt() ?: 10,
                batchTimeoutMs = (unicodeSection?.get("batchTimeoutMs") as? Number)?.toLong() ?: 100
            ).also { instance = it }
        }
        
        /**
         * Creates a default configuration.
         */
        fun createDefault(): UnicodeConfig = synchronized(lock) {
            return UnicodeConfig().also { instance = it }
        }
        
        /**
         * Gets the current configuration instance.
         */
        fun getInstance(): UnicodeConfig {
            return instance ?: synchronized(lock) {
                instance ?: createDefault()
            }
        }
        
        /**
         * Quick access to check if Unicode is enabled.
         */
        val isEnabled: Boolean
            get() = getInstance().enabled
            
        /**
         * Quick access to check if keyboard should be restored.
         */
        val shouldRestoreKeyboard: Boolean
            get() = getInstance().restoreKeyboard
            
        /**
         * Quick access to check if performance optimizations are enabled.
         */
        val isPerformanceOptimized: Boolean
            get() = getInstance().enablePerformanceOptimizations
    }
    
    /**
     * Validates the configuration and returns any warnings.
     */
    fun validate(): List<String> {
        val warnings = mutableListOf<String>()
        
        if (chunkSize <= 0) {
            warnings.add("chunkSize must be positive, using default: 500")
        }
        
        if (chunkSize > 2000) {
            warnings.add("chunkSize is very large (${chunkSize}), this may cause input delays")
        }
        
        if (delayBetweenChunks < 0) {
            warnings.add("delayBetweenChunks cannot be negative, using default: 20")
        }
        
        if (delayBetweenCharacters < 0) {
            warnings.add("delayBetweenCharacters cannot be negative, using default: 10")
        }
        
        if (maxInputLength < 1) {
            warnings.add("maxInputLength must be at least 1, using default: 10000")
        }
        
        if (maxBatchSize < 1) {
            warnings.add("maxBatchSize must be at least 1, using default: 10")
        }
        
        if (maxBatchSize > 100) {
            warnings.add("maxBatchSize is very large (${maxBatchSize}), this may impact performance")
        }
        
        if (batchTimeoutMs < 0) {
            warnings.add("batchTimeoutMs cannot be negative, using default: 100")
        }
        
        if (!autoInstallKeyboard && !fallbackToCharInput) {
            warnings.add("Both autoInstallKeyboard and fallbackToCharInput are disabled, Unicode input may fail")
        }
        
        return warnings
    }
    
    /**
     * Returns a configuration optimized for performance.
     */
    fun withPerformanceOptimizations(): UnicodeConfig {
        return copy(
            enablePerformanceOptimizations = true,
            restoreKeyboard = false,
            batchInputRequests = true,
            maxBatchSize = 20,
            cacheKeyboardState = true,
            delayBetweenChunks = 10,
            delayBetweenCharacters = 5
        )
    }
    
    /**
     * Returns a configuration optimized for reliability.
     */
    fun withReliabilityOptimizations(): UnicodeConfig {
        return copy(
            enablePerformanceOptimizations = false,
            restoreKeyboard = true,
            batchInputRequests = false,
            fallbackToCharInput = true,
            enableClipboardFallback = true,
            delayBetweenChunks = 50,
            delayBetweenCharacters = 20,
            keyboardSwitchDelay = 200
        )
    }
    
    /**
     * Returns a configuration for debugging Unicode issues.
     */
    fun withDebugSettings(): UnicodeConfig {
        return copy(
            logUnicodeOperations = true,
            fallbackToCharInput = true,
            enableClipboardFallback = true,
            delayBetweenChunks = 100,
            delayBetweenCharacters = 50,
            keyboardSwitchDelay = 300
        )
    }
}

/**
 * Exception thrown when Unicode configuration is invalid.
 */
class InvalidUnicodeConfigException(message: String) : RuntimeException(message)