package maestro.unicode

import dadb.Dadb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the ADB Keyboard installation and operation for Unicode text input.
 * This class handles the installation, configuration, and usage of the ADB Keyboard
 * which enables Unicode text input on Android devices.
 */
class AdbKeyboardManager(
    private val dadb: Dadb,
    private val scope: CoroutineScope
) {
    
    companion object {
        const val PACKAGE_NAME = "com.android.adbkeyboard"
        const val IME_NAME = "$PACKAGE_NAME/.AdbIME"
        const val BROADCAST_ACTION = "ADB_INPUT_B64"
        const val BROADCAST_ACTION_CHARS = "ADB_INPUT_CHARS"
        const val BROADCAST_ACTION_CODE = "ADB_INPUT_CODE"
        const val BROADCAST_ACTION_CLEAR = "ADB_CLEAR_TEXT"
        
        private const val APK_DOWNLOAD_URL = "https://github.com/senzhk/ADBKeyBoard/raw/master/ADBKeyboard.apk"
        private const val INSTALLATION_TIMEOUT_MS = 30000L
        private const val IME_SWITCH_TIMEOUT_MS = 1000L
        private const val MAX_BROADCAST_LENGTH = 1500 // Conservative limit for broadcast messages
        
        private val logger = LoggerFactory.getLogger(AdbKeyboardManager::class.java)
    }
    
    private var originalIme: String? = null
    private val imeCache = ConcurrentHashMap<String, Boolean>()
    private val devicePropertiesCache = ConcurrentHashMap<String, String>()
    private var isKeyboardInstalled: Boolean? = null
    private var isKeyboardEnabled: Boolean? = null
    
    /**
     * Ensures the ADB Keyboard is installed and ready for use.
     */
    suspend fun ensureInstalled(): Boolean {
        return withContext(scope.coroutineContext) {
            try {
                // Check cache first
                if (isKeyboardInstalled == true && isKeyboardEnabled == true) {
                    return@withContext true
                }
                
                logger.info("Checking ADB Keyboard installation status...")
                
                if (isInstalled()) {
                    logger.info("ADB Keyboard is already installed")
                    if (isEnabled()) {
                        logger.info("ADB Keyboard is already enabled")
                        isKeyboardInstalled = true
                        isKeyboardEnabled = true
                        return@withContext true
                    } else {
                        logger.info("Enabling ADB Keyboard...")
                        return@withContext enableKeyboard()
                    }
                }
                
                logger.info("Installing ADB Keyboard for Unicode support...")
                
                // Download and install the APK
                val apkFile = downloadAdbKeyboard()
                try {
                    // Install the APK
                    dadb.install(apkFile)
                    
                    // Wait for installation to complete
                    delay(1000)
                    
                    // Enable the keyboard
                    val enabled = enableKeyboard()
                    
                    if (enabled) {
                        isKeyboardInstalled = true
                        isKeyboardEnabled = true
                        logger.info("ADB Keyboard successfully installed and enabled")
                        return@withContext true
                    } else {
                        logger.error("Failed to enable ADB Keyboard after installation")
                        return@withContext false
                    }
                    
                } finally {
                    apkFile.delete()
                }
                
            } catch (e: Exception) {
                logger.error("Failed to install ADB Keyboard", e)
                isKeyboardInstalled = false
                isKeyboardEnabled = false
                return@withContext false
            }
        }
    }
    
    /**
     * Switches the input method to ADB Keyboard.
     */
    suspend fun switchToAdbKeyboard(): Boolean {
        return withContext(scope.coroutineContext) {
            try {
                // Save current IME if not already saved
                if (originalIme == null) {
                    originalIme = getCurrentIme()
                    logger.debug("Saved original IME: $originalIme")
                }
                
                // Switch to ADB Keyboard
                dadb.shell("ime set $IME_NAME")
                delay(100) // Brief pause for IME switch
                
                // Verify the switch was successful
                val currentIme = getCurrentIme()
                val success = currentIme == IME_NAME
                
                if (success) {
                    logger.debug("Successfully switched to ADB Keyboard")
                } else {
                    logger.warn("Failed to switch to ADB Keyboard. Current IME: $currentIme")
                }
                
                return@withContext success
                
            } catch (e: Exception) {
                logger.error("Error switching to ADB Keyboard", e)
                return@withContext false
            }
        }
    }
    
    /**
     * Restores the original keyboard.
     */
    suspend fun restoreOriginalKeyboard(): Boolean {
        return withContext(scope.coroutineContext) {
            try {
                originalIme?.let { ime ->
                    logger.debug("Restoring original IME: $ime")
                    dadb.shell("ime set $ime")
                    delay(100) // Brief pause for IME switch
                    
                    // Verify the switch was successful
                    val currentIme = getCurrentIme()
                    val success = currentIme == ime
                    
                    if (success) {
                        logger.debug("Successfully restored original keyboard")
                        originalIme = null
                    } else {
                        logger.warn("Failed to restore original keyboard. Current IME: $currentIme")
                    }
                    
                    return@withContext success
                } ?: run {
                    logger.debug("No original IME to restore")
                    return@withContext true
                }
                
            } catch (e: Exception) {
                logger.error("Error restoring original keyboard", e)
                return@withContext false
            }
        }
    }
    
    /**
     * Sends Unicode text using the ADB Keyboard.
     */
    suspend fun sendUnicodeText(text: String): Boolean {
        return withContext(scope.coroutineContext) {
            try {
                if (text.isEmpty()) {
                    return@withContext true
                }
                
                // For very long text, split into chunks
                if (text.length > MAX_BROADCAST_LENGTH) {
                    return@withContext sendLongText(text)
                }
                
                // Encode text as Base64
                val base64Text = Base64.getEncoder().encodeToString(
                    text.toByteArray(Charsets.UTF_8)
                )
                
                // Send via broadcast
                val command = "am broadcast -a $BROADCAST_ACTION --es msg '$base64Text'"
                val response = dadb.shell(command)
                
                if (response.exitCode == 0) {
                    logger.debug("Successfully sent Unicode text (${text.length} chars)")
                    return@withContext true
                } else {
                    logger.error("Failed to send Unicode text: ${response.allOutput}")
                    return@withContext false
                }
                
            } catch (e: Exception) {
                logger.error("Error sending Unicode text", e)
                return@withContext false
            }
        }
    }
    
    /**
     * Sends individual characters using the ADB Keyboard.
     */
    suspend fun sendCharacters(text: String): Boolean {
        return withContext(scope.coroutineContext) {
            try {
                val command = "am broadcast -a $BROADCAST_ACTION_CHARS --es chars '$text'"
                val response = dadb.shell(command)
                
                if (response.exitCode == 0) {
                    logger.debug("Successfully sent characters: $text")
                    return@withContext true
                } else {
                    logger.error("Failed to send characters: ${response.allOutput}")
                    return@withContext false
                }
                
            } catch (e: Exception) {
                logger.error("Error sending characters", e)
                return@withContext false
            }
        }
    }
    
    /**
     * Sends a key code using the ADB Keyboard.
     */
    suspend fun sendKeyCode(keyCode: Int): Boolean {
        return withContext(scope.coroutineContext) {
            try {
                val command = "am broadcast -a $BROADCAST_ACTION_CODE --ei code $keyCode"
                val response = dadb.shell(command)
                
                if (response.exitCode == 0) {
                    logger.debug("Successfully sent key code: $keyCode")
                    return@withContext true
                } else {
                    logger.error("Failed to send key code: ${response.allOutput}")
                    return@withContext false
                }
                
            } catch (e: Exception) {
                logger.error("Error sending key code", e)
                return@withContext false
            }
        }
    }
    
    /**
     * Clears the current text input.
     */
    suspend fun clearText(): Boolean {
        return withContext(scope.coroutineContext) {
            try {
                val command = "am broadcast -a $BROADCAST_ACTION_CLEAR"
                val response = dadb.shell(command)
                
                if (response.exitCode == 0) {
                    logger.debug("Successfully cleared text")
                    return@withContext true
                } else {
                    logger.error("Failed to clear text: ${response.allOutput}")
                    return@withContext false
                }
                
            } catch (e: Exception) {
                logger.error("Error clearing text", e)
                return@withContext false
            }
        }
    }
    
    /**
     * Gets the current input method.
     */
    private fun getCurrentIme(): String {
        return try {
            dadb.shell("settings get secure default_input_method").output.trim()
        } catch (e: Exception) {
            logger.warn("Failed to get current IME", e)
            ""
        }
    }
    
    /**
     * Checks if the ADB Keyboard is installed.
     */
    private fun isInstalled(): Boolean {
        return imeCache.getOrPut(PACKAGE_NAME) {
            try {
                val output = dadb.shell("pm list packages | grep $PACKAGE_NAME").output
                output.isNotEmpty()
            } catch (e: Exception) {
                logger.warn("Failed to check if ADB Keyboard is installed", e)
                false
            }
        }
    }
    
    /**
     * Checks if the ADB Keyboard is enabled.
     */
    private fun isEnabled(): Boolean {
        return try {
            val output = dadb.shell("ime list -s | grep $IME_NAME").output
            output.isNotEmpty()
        } catch (e: Exception) {
            logger.warn("Failed to check if ADB Keyboard is enabled", e)
            false
        }
    }
    
    /**
     * Enables the ADB Keyboard.
     */
    private suspend fun enableKeyboard(): Boolean {
        return withContext(scope.coroutineContext) {
            try {
                // Enable the IME
                val enableResponse = dadb.shell("ime enable $IME_NAME")
                if (enableResponse.exitCode != 0) {
                    logger.error("Failed to enable ADB Keyboard: ${enableResponse.allOutput}")
                    return@withContext false
                }
                
                // Wait for the IME to be enabled
                delay(500)
                
                // Verify it's enabled
                val enabled = isEnabled()
                if (enabled) {
                    logger.info("ADB Keyboard enabled successfully")
                    isKeyboardEnabled = true
                } else {
                    logger.error("ADB Keyboard was not enabled after enable command")
                    isKeyboardEnabled = false
                }
                
                return@withContext enabled
                
            } catch (e: Exception) {
                logger.error("Error enabling ADB Keyboard", e)
                isKeyboardEnabled = false
                return@withContext false
            }
        }
    }
    
    /**
     * Sends long text by splitting it into chunks.
     */
    private suspend fun sendLongText(text: String): Boolean {
        return withContext(scope.coroutineContext) {
            try {
                val chunks = text.chunked(MAX_BROADCAST_LENGTH)
                logger.debug("Splitting long text into ${chunks.size} chunks")
                
                for ((index, chunk) in chunks.withIndex()) {
                    val success = sendUnicodeText(chunk)
                    if (!success) {
                        logger.error("Failed to send chunk ${index + 1}/${chunks.size}")
                        return@withContext false
                    }
                    
                    // Small delay between chunks to prevent overwhelming the system
                    if (index < chunks.size - 1) {
                        delay(20)
                    }
                }
                
                logger.debug("Successfully sent all ${chunks.size} chunks")
                return@withContext true
                
            } catch (e: Exception) {
                logger.error("Error sending long text", e)
                return@withContext false
            }
        }
    }
    
    /**
     * Downloads the ADB Keyboard APK from GitHub.
     */
    private suspend fun downloadAdbKeyboard(): File {
        return withContext(Dispatchers.IO) {
            try {
                logger.info("Downloading ADB Keyboard APK from GitHub...")
                
                val url = URL(APK_DOWNLOAD_URL)
                val tempFile = Files.createTempFile("ADBKeyboard", ".apk")
                
                url.openStream().use { input ->
                    Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING)
                }
                
                val file = tempFile.toFile()
                logger.info("ADB Keyboard APK downloaded successfully: ${file.absolutePath}")
                
                // Verify the file was downloaded and has content
                if (!file.exists() || file.length() == 0L) {
                    throw IOException("Downloaded APK file is empty or doesn't exist")
                }
                
                return@withContext file
                
            } catch (e: Exception) {
                logger.error("Failed to download ADB Keyboard APK from $APK_DOWNLOAD_URL", e)
                throw IOException("Failed to download ADB Keyboard APK: ${e.message}", e)
            }
        }
    }
    
    /**
     * Clears all cached state.
     */
    fun clearCache() {
        imeCache.clear()
        devicePropertiesCache.clear()
        isKeyboardInstalled = null
        isKeyboardEnabled = null
        originalIme = null
    }
    
    /**
     * Gets diagnostic information about the ADB Keyboard.
     */
    fun getDiagnosticInfo(): Map<String, Any> {
        return mapOf(
            "isInstalled" to (isKeyboardInstalled ?: false),
            "isEnabled" to (isKeyboardEnabled ?: false),
            "originalIme" to (originalIme ?: "none"),
            "currentIme" to getCurrentIme(),
            "packageName" to PACKAGE_NAME,
            "imeName" to IME_NAME
        )
    }
}