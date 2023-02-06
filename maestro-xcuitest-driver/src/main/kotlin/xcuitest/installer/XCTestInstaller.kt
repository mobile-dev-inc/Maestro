package xcuitest.installer

interface XCTestInstaller: AutoCloseable {

    fun setup(): Boolean

    fun killAndUninstall()

    fun isChannelAlive(): Boolean
}