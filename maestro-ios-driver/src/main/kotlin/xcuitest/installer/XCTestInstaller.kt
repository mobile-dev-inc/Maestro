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
}
