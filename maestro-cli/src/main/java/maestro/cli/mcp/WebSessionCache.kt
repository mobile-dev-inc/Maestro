/*
 * Copyright (c) 2024 mobile.dev inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package maestro.cli.mcp

import maestro.Maestro
import maestro.cli.session.MaestroSessionManager
import maestro.cli.session.MaestroSessionManager.MaestroSession
import org.slf4j.LoggerFactory

/**
 * Singleton cache for web browser sessions in MCP.
 *
 * This cache maintains a single browser instance across multiple MCP tool calls,
 * preventing the issue where each tool invocation spawns a new browser window.
 *
 * The cached session is reused as long as the browser is still responsive.
 * If the browser crashes or becomes unresponsive, a new session is created.
 */
object WebSessionCache {
    private val logger = LoggerFactory.getLogger(WebSessionCache::class.java)

    private var cachedSession: MaestroSession? = null
    private var isHeadless: Boolean = false

    /**
     * Helper function to execute a block with a session, using the web cache for web devices
     * and falling back to the session manager for other platforms.
     *
     * @param sessionManager The session manager for non-web platforms
     * @param deviceId The device ID (if "chromium" or web platform, uses cache)
     * @param platform Optional platform override
     * @param isHeadless Whether to run headless (only applies to web)
     * @param block The block to execute with the session
     * @return The result of the block
     */
    fun <T> withSession(
        sessionManager: MaestroSessionManager,
        deviceId: String?,
        platform: String? = null,
        isHeadless: Boolean = false,
        block: (MaestroSession) -> T
    ): T {
        // Check if this is a web device
        val isWebDevice = deviceId == "chromium" || platform == "web" || platform == "WEB"

        return if (isWebDevice) {
            // Use cached web session
            val session = getOrCreateSession(isHeadless)
            block(session)
        } else {
            // Use standard session manager for Android/iOS
            sessionManager.newSession(
                host = null,
                port = null,
                driverHostPort = null,
                deviceId = deviceId,
                platform = platform
            ) { session ->
                block(session)
            }
        }
    }

    /**
     * Gets an existing web session or creates a new one if none exists or the existing one is dead.
     *
     * @param isHeadless Whether to run the browser in headless mode
     * @return A MaestroSession with an active web browser
     */
    @Synchronized
    fun getOrCreateSession(isHeadless: Boolean = false): MaestroSession {
        val existing = cachedSession

        // Check if we have an existing session and it's still alive
        if (existing != null && isSessionAlive(existing)) {
            logger.debug("Reusing existing web session")
            return existing
        }

        // Clean up dead session if exists
        if (existing != null) {
            logger.debug("Existing web session is dead, cleaning up")
            safeClose(existing)
        }

        // Create new session
        logger.debug("Creating new web session (headless={})", isHeadless)
        this.isHeadless = isHeadless
        val newSession = createWebSession(isHeadless)
        cachedSession = newSession
        return newSession
    }

    /**
     * Checks if the given session is still alive and responsive.
     */
    private fun isSessionAlive(session: MaestroSession): Boolean {
        return try {
            // Try to get device info - this will fail if browser is dead
            session.maestro.deviceInfo()
            true
        } catch (e: Exception) {
            logger.debug("Session health check failed: {}", e.message)
            false
        }
    }

    /**
     * Creates a new web Maestro session.
     */
    private fun createWebSession(isHeadless: Boolean): MaestroSession {
        val maestro = Maestro.web(
            isStudio = false,
            isHeadless = isHeadless
        )
        return MaestroSession(
            maestro = maestro,
            device = null
        )
    }

    /**
     * Safely closes a session, catching any exceptions.
     */
    private fun safeClose(session: MaestroSession) {
        try {
            session.close()
        } catch (e: Exception) {
            logger.warn("Error closing web session: {}", e.message)
        }
    }

    /**
     * Shuts down the cached web session.
     * Should be called when the MCP server is shutting down.
     */
    @Synchronized
    fun shutdown() {
        val session = cachedSession
        if (session != null) {
            logger.info("Shutting down cached web session")
            safeClose(session)
            cachedSession = null
        }
    }

    /**
     * Checks if there's an active cached session.
     */
    @Synchronized
    fun hasActiveSession(): Boolean {
        val session = cachedSession ?: return false
        return isSessionAlive(session)
    }
}
