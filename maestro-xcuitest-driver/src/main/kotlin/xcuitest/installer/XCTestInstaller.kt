package xcuitest.installer

interface XCTestInstaller: AutoCloseable {

    fun setup()

    fun killAndUninstall()

}