package xcuitest.installer

import xcuitest.XCTestClient

interface XCTestInstaller: AutoCloseable {
    fun start(): XCTestClient

    /**
     * Attempts to uninstall the XCTest Runner.
     *
     * @return true if the XCTest Runner was uninstalled, false otherwise.
     */
    fun uninstall(): Boolean

    fun isChannelAlive(): Boolean

    /**
     * Returns true if the currently running XCTest Runner matches the
     * expected version (e.g., same build product hash). Returns false
     * if the version cannot be determined or does not match.
     */
    fun isVersionMatch(): Boolean = true
}
