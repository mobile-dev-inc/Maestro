package xcuitest.installer

import xcuitest.XCTestClient

interface XCTestInstaller: AutoCloseable {
    fun start(source: Source): XCTestClient?

    fun uninstall()

    fun isChannelAlive(): Boolean
}

enum class Source {
    RETRY,
    DRIVER_OPEN
}
