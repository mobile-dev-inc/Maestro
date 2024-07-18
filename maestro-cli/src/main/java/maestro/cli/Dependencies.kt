package maestro.cli

import maestro.cli.util.Unpacker.binaryDependency
import maestro.cli.util.Unpacker.unpack

object Dependencies {
    private val appleSimUtils = binaryDependency("applesimutils")

    fun install() {
        unpack(
            jarPath = "deps/applesimutils",
            target = appleSimUtils,
        )
    }

}
