package maestro.cli

import maestro.cli.util.Unpacker.binaryDependency
import maestro.cli.util.Unpacker.unpack

object Dependencies {

    val APPLESIMUTILS = binaryDependency("applesimutils")

    fun install() {
        unpack(
            "deps/applesimutils",
            APPLESIMUTILS
        )
    }

}