package maestro.unicode

import dadb.Dadb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import maestro.unicode.config.UnicodeConfig
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Main handler for Unicode text input in Maestro.
 * This class coordinates between different input methods and provides
 * a unified interface for Unicode text input with fallback strategies.
 */
class UnicodeInputHandler(
    private val dadb: Dadb,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    
    companion object {
        private val logger = LoggerFactory.getLogger(UnicodeInputHandler::class.java)
        private const val RTL_MARK = '\u200F'
        private const val LTR_MARK = '\u200E'
    }
    
    private val keyboardManager = AdbKeyboardManager(dadb, scope)
    private val inputQueue = Channel<InputRequest>(Channel.UNLIMITED)
    private val isInitialized = AtomicBoolean(false)
    private val operationCounter = AtomicLong(0)
    private val performanceMetrics = ConcurrentHashMap<String, Long>()
    
    // Batch processing for performance
    private val batchProcessor = if (UnicodeConfig.getInstance().batchInputRequests) {
        BatchProcessor()
    } else null
    
    init {
        // Process input requests sequentially to avoid IME conflicts
        scope.launch {
            for (request in inputQueue) {
                processInputRequest(request)
            }
        }
    }
    
    /**
     * Ensures the Unicode input system is ready for use.
     */
    suspend fun ensureReady(): Boolean {
        return withContext(scope.coroutineContext) {
            if (isInitialized.get()) {
                return@withContext true
            }
            
            val config = UnicodeConfig.getInstance()
            if (!config.enabled) {
                logger.info("Unicode input is disabled in configuration")
                return@withContext false
            }
            
            try {
                logger.info("Initializing Unicode input system...")
                
                // Validate configuration
                val warnings = config.validate()
                warnings.forEach { warning ->
                    logger.warn("Configuration warning: $warning")
                }
                
                // Initialize ADB Keyboard - try to ensure it's available for Unicode text
                val keyboardReady = keyboardManager.ensureInstalled()
                if (!keyboardReady) {
                    if (config.fallbackToCharInput) {
                        logger.warn("ADB Keyboard not available, will use fallback methods for Unicode text")
                        isInitialized.set(true)
                        return@withContext true
                    } else {
                        logger.error("ADB Keyboard not available and fallback is disabled")
                        return@withContext false
                    }
                } else {
                    logger.info("ADB Keyboard is ready for Unicode input")
                }
                
                isInitialized.set(true)
                logger.info("Unicode input system initialized successfully")
                return@withContext true
                
            } catch (e: Exception) {
                logger.error("Failed to initialize Unicode input system", e)
                return@withContext false
            }
        }
    }
    
    /**
     * Inputs Unicode text using the best available method.
     */
    fun inputText(text: String) {
        if (text.isEmpty()) {
            return
        }
        
        val operationId = operationCounter.incrementAndGet()
        logger.debug("Queuing Unicode input operation $operationId: ${text.length} characters")
        
        runBlocking {
            inputQueue.send(InputRequest(text, operationId))
        }
    }
    
    /**
     * Inputs Unicode text synchronously.
     */
    suspend fun inputTextSync(text: String): Boolean {
        return withContext(scope.coroutineContext) {
            val operationId = operationCounter.incrementAndGet()
            logger.debug("Processing Unicode input operation $operationId synchronously")
            
            processInputRequest(InputRequest(text, operationId))
        }
    }
    
    /**
     * Processes a single input request.
     */
    private suspend fun processInputRequest(request: InputRequest): Boolean {
        return withContext(scope.coroutineContext) {
            val startTime = System.currentTimeMillis()
            
            try {
                logger.debug("Processing input request ${request.operationId}")
                
                // Analyze text characteristics
                val characterSets = UnicodeDetector.getCharacterSets(request.text)
                val complexity = UnicodeDetector.getTextComplexity(request.text)
                val inputMethod = UnicodeDetector.getRecommendedInputMethod(request.text)
                
                logger.debug("Input analysis - Sets: $characterSets, Complexity: $complexity, Method: $inputMethod")
                
                // Route to appropriate input method
                val result = when (inputMethod) {
                    UnicodeDetector.InputMethod.STANDARD -> {
                        handleStandardInput(request.text)
                    }
                    UnicodeDetector.InputMethod.UNICODE_WITH_EMOJI_SUPPORT -> {
                        handleEmojiInput(request.text)
                    }
                    UnicodeDetector.InputMethod.UNICODE_WITH_RTL_SUPPORT -> {
                        handleRtlInput(request.text)
                    }
                    UnicodeDetector.InputMethod.UNICODE_WITH_IME -> {
                        handleComplexScriptInput(request.text)
                    }
                    else -> {
                        handleStandardUnicodeInput(request.text)
                    }
                }
                
                // Record performance metrics
                val duration = System.currentTimeMillis() - startTime
                performanceMetrics["operation_${request.operationId}"] = duration
                
                if (result) {
                    logger.debug("Successfully processed input request ${request.operationId} in ${duration}ms")
                } else {
                    logger.warn("Failed to process input request ${request.operationId}")
                }
                
                return@withContext result
                
            } catch (e: Exception) {
                logger.error("Error processing input request ${request.operationId}", e)
                
                // Try fallback methods
                return@withContext attemptFallbackInput(request.text)
            }
        }
    }
    
    /**
     * Handles standard ASCII input.
     */
    private suspend fun handleStandardInput(text: String): Boolean {
        return withContext(scope.coroutineContext) {
            // For ASCII-only text, we can use the existing input method
            // or ADB Keyboard for consistency
            val config = UnicodeConfig.getInstance()
            
            if (config.enablePerformanceOptimizations) {
                // Use existing character input for ASCII
                fallbackToCharacterInput(text)
            } else {
                // Use ADB Keyboard for consistency
                handleStandardUnicodeInput(text)
            }
        }
    }
    
    /**
     * Handles standard Unicode input.
     */
    private suspend fun handleStandardUnicodeInput(text: String): Boolean {
        return withContext(scope.coroutineContext) {
            val config = UnicodeConfig.getInstance()
            
            // Ensure ADB Keyboard is available and switch to it
            val keyboardReady = keyboardManager.ensureInstalled()
            if (!keyboardReady) {
                logger.warn("ADB Keyboard not available for Unicode input, trying fallback")
                return@withContext attemptFallbackInput(text)
            }
            
            val switchSuccess = keyboardManager.switchToAdbKeyboard()
            if (!switchSuccess) {
                logger.warn("Failed to switch to ADB Keyboard, trying fallback")
                return@withContext attemptFallbackInput(text)
            }
            
            delay(config.keyboardSwitchDelay)
            
            try {
                // Split long texts to avoid broadcast limits
                val success = if (text.length > config.chunkSize) {
                    sendTextInChunks(text)
                } else {
                    keyboardManager.sendUnicodeText(text)
                }
                
                return@withContext success
                
            } finally {
                // Restore original keyboard if configured
                if (config.restoreKeyboard) {
                    keyboardManager.restoreOriginalKeyboard()
                }
            }
        }
    }
    
    /**
     * Handles RTL (Right-to-Left) text input.
     */
    private suspend fun handleRtlInput(text: String): Boolean {
        return withContext(scope.coroutineContext) {
            val config = UnicodeConfig.getInstance()
            
            if (!config.enableRtlSupport) {
                logger.warn("RTL support is disabled, falling back to standard input")
                return@withContext handleStandardUnicodeInput(text)
            }
            
            // Process RTL text with proper direction markers
            val processedText = processRtlText(text)
            
            return@withContext handleStandardUnicodeInput(processedText)
        }
    }
    
    /**
     * Handles emoji input with special considerations.
     */
    private suspend fun handleEmojiInput(text: String): Boolean {
        return withContext(scope.coroutineContext) {
            val config = UnicodeConfig.getInstance()
            
            if (!config.enableEmojiSupport) {
                logger.warn("Emoji support is disabled, falling back to standard input")
                return@withContext handleStandardUnicodeInput(text)
            }
            
            // Get Android version for emoji compatibility
            val androidVersion = getAndroidVersion()
            
            return@withContext if (androidVersion >= 23) {
                // Modern Android versions handle emoji well
                handleStandardUnicodeInput(text)
            } else {
                // Older Android versions might need special handling
                handleLegacyEmojiInput(text)
            }
        }
    }
    
    /**
     * Handles complex script input (Devanagari, Thai, etc.).
     */
    private suspend fun handleComplexScriptInput(text: String): Boolean {
        return withContext(scope.coroutineContext) {
            // Complex scripts may need special IME handling
            // For now, treat as standard Unicode with longer delays
            val config = UnicodeConfig.getInstance()
            
            val originalDelay = config.delayBetweenChunks
            val extendedConfig = config.copy(
                delayBetweenChunks = originalDelay * 2,
                chunkSize = config.chunkSize / 2
            )
            
            // Temporarily use extended config
            UnicodeConfig.load(mapOf("unicode" to mapOf(
                "delayBetweenChunks" to extendedConfig.delayBetweenChunks,
                "chunkSize" to extendedConfig.chunkSize
            )))
            
            try {
                return@withContext handleStandardUnicodeInput(text)
            } finally {
                // Restore original config
                UnicodeConfig.load(null)
            }
        }
    }
    
    /**
     * Attempts fallback input methods when primary method fails.
     */
    private suspend fun attemptFallbackInput(text: String): Boolean {
        return withContext(scope.coroutineContext) {
            val config = UnicodeConfig.getInstance()
            
            if (!config.fallbackToCharInput) {
                logger.error("Fallback input is disabled")
                return@withContext false
            }
            
            logger.info("Attempting fallback input methods for Unicode text")
            
            // Try clipboard method
            if (config.enableClipboardFallback) {
                logger.info("Trying clipboard fallback method")
                val clipboardSuccess = attemptClipboardInput(text)
                if (clipboardSuccess) {
                    logger.info("✓ Clipboard fallback successful")
                    return@withContext true
                } else {
                    logger.warn("✗ Clipboard fallback failed")
                }
            }
            
            // Try character-by-character input
            logger.info("Trying character-by-character fallback method")
            val charSuccess = fallbackToCharacterInput(text)
            if (charSuccess) {
                logger.info("✓ Character-by-character fallback successful")
                return@withContext true
            } else {
                logger.warn("✗ Character-by-character fallback failed")
            }
            
            logger.error("✗ All fallback methods failed for Unicode text")
            return@withContext false
        }
    }
    
    /**
     * Sends text in chunks to avoid broadcast size limits.
     */
    private suspend fun sendTextInChunks(text: String): Boolean {
        return withContext(scope.coroutineContext) {
            val config = UnicodeConfig.getInstance()
            val chunks = text.chunked(config.chunkSize)
            
            logger.debug("Sending text in ${chunks.size} chunks")
            
            for ((index, chunk) in chunks.withIndex()) {
                val success = keyboardManager.sendUnicodeText(chunk)
                if (!success) {
                    logger.error("Failed to send chunk ${index + 1}/${chunks.size}")
                    return@withContext false
                }
                
                // Delay between chunks
                if (index < chunks.size - 1) {
                    delay(config.delayBetweenChunks)
                }
            }
            
            return@withContext true
        }
    }
    
    /**
     * Processes RTL text with proper direction markers.
     */
    private fun processRtlText(text: String): String {
        val hasRtlChars = UnicodeDetector.containsBidirectionalText(text)
        val hasNumbers = text.any { it.isDigit() }
        
        return when {
            hasRtlChars && hasNumbers -> {
                // Mixed RTL and numbers may need special handling
                "$RTL_MARK$text"
            }
            hasRtlChars -> {
                // Pure RTL text
                "$RTL_MARK$text"
            }
            else -> {
                // No RTL characters, return as-is
                text
            }
        }
    }
    
    /**
     * Handles emoji input on legacy Android versions.
     */
    private suspend fun handleLegacyEmojiInput(text: String): Boolean {
        return withContext(scope.coroutineContext) {
            val config = UnicodeConfig.getInstance()
            
            // On older Android versions, send emoji character by character
            // with longer delays
            val characters = text.toCharArray()
            
            for ((index, char) in characters.withIndex()) {
                val success = keyboardManager.sendUnicodeText(char.toString())
                if (!success) {
                    logger.error("Failed to send emoji character at position $index")
                    return@withContext false
                }
                
                // Longer delay for emoji on older devices
                if (index < characters.size - 1) {
                    delay(config.delayBetweenCharacters * 3)
                }
            }
            
            return@withContext true
        }
    }
    
    /**
     * Attempts to use clipboard for text input.
     */
    private suspend fun attemptClipboardInput(text: String): Boolean {
        return withContext(scope.coroutineContext) {
            try {
                // Escape text for shell command
                val escapedText = text.replace("'", "'\"'\"'")
                
                // Try standard clipboard setting (API 23+)
                val clipResult = dadb.shell("am broadcast -a android.intent.action.CLIPBOARD_SET -e text '$escapedText'")
                if (clipResult.exitCode != 0) {
                    logger.debug("Standard clipboard broadcast failed, trying alternative")
                    // Alternative method - set clipboard directly
                    dadb.shell("service call clipboard 2 s16 '$escapedText'")
                }
                
                delay(100)
                
                // Send paste command (Ctrl+V keycode)
                val pasteResult = dadb.shell("input keyevent 279")
                delay(100)
                
                return@withContext pasteResult.exitCode == 0
                
            } catch (e: Exception) {
                logger.debug("Clipboard input failed", e)
                return@withContext false
            }
        }
    }
    
    /**
     * Falls back to character-by-character input using the existing method.
     */
    private suspend fun fallbackToCharacterInput(text: String): Boolean {
        return withContext(scope.coroutineContext) {
            try {
                val config = UnicodeConfig.getInstance()
                
                // Use existing character input method
                for ((index, char) in text.withIndex()) {
                    // This would integrate with the existing MaestroDriverService.setText
                    // For now, we'll use a simple approach
                    val success = sendSingleCharacter(char)
                    if (!success) {
                        logger.error("Failed to send character at position $index: $char")
                        return@withContext false
                    }
                    
                    if (index < text.length - 1) {
                        delay(config.delayBetweenCharacters)
                    }
                }
                
                return@withContext true
                
            } catch (e: Exception) {
                logger.error("Character-by-character input failed", e)
                return@withContext false
            }
        }
    }
    
    /**
     * Sends a single character using standard ADB input.
     */
    private suspend fun sendSingleCharacter(char: Char): Boolean {
        return withContext(scope.coroutineContext) {
            try {
                // Use standard ADB input for single characters
                // This works for ASCII and some Unicode characters
                val result = dadb.shell("input text '${char.toString().replace("'", "\\'")}'")
                delay(10) // Small delay between characters
                return@withContext result.exitCode == 0
            } catch (e: Exception) {
                logger.debug("Failed to send character '$char' via ADB input", e)
                return@withContext false
            }
        }
    }
    
    /**
     * Gets the Android version of the device.
     */
    private fun getAndroidVersion(): Int {
        return try {
            val response = dadb.shell("getprop ro.build.version.sdk")
            response.output.trim().toIntOrNull() ?: 21
        } catch (e: Exception) {
            logger.warn("Failed to get Android version", e)
            21 // Default to API 21
        }
    }
    
    /**
     * Gets performance metrics.
     */
    fun getPerformanceMetrics(): Map<String, Any> {
        return mapOf(
            "operationCount" to operationCounter.get(),
            "isInitialized" to isInitialized.get(),
            "averageProcessingTime" to if (performanceMetrics.isNotEmpty()) {
                performanceMetrics.values.average()
            } else 0.0,
            "keyboardDiagnostics" to keyboardManager.getDiagnosticInfo()
        )
    }
    
    /**
     * Clears all caches and resets state.
     */
    fun reset() {
        performanceMetrics.clear()
        keyboardManager.clearCache()
        isInitialized.set(false)
    }
    
    /**
     * Internal data class for input requests.
     */
    private data class InputRequest(
        val text: String,
        val operationId: Long
    )
    
    /**
     * Batch processor for performance optimization.
     */
    private inner class BatchProcessor {
        // Implementation for batch processing would go here
        // This is a placeholder for future performance optimization
    }
}

/**
 * Exception thrown when Unicode input fails.
 */
class UnicodeInputException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)