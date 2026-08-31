package util

import java.io.File

object PhysicalDeviceXCTestRunConfiguration {
    fun configurePort(xcTestRunFile: File, port: Int) {
        CommandLineUtils.runCommand(
            listOf(
                "plutil",
                "-replace",
                "maestro-driver-iosUITests.EnvironmentVariables.PORT",
                "-string",
                port.toString(),
                xcTestRunFile.absolutePath,
            ),
        )
    }
}
