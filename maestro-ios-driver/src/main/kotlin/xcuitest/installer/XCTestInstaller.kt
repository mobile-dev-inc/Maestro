package xcuitest.installer

import xcuitest.XCTestClient

interface XCTestInstaller: AutoCloseable {
    fun start(): XCTestClient?

    fun uninstall()

    fun isChannelAlive(): Boolean
}
