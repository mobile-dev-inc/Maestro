package xcuitest.installer

interface XCTestInstaller: AutoCloseable {

    fun start(): Boolean

    fun uninstall()

    fun isChannelAlive(): Boolean
}
