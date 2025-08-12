package util

import util.CommandLineUtils.runCommand
import java.io.File
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object LocalIOSDeviceController {

    private const val LOG_DIR_DATE_FORMAT = "yyyy-MM-dd_HHmmss"
    private val dateFormatter by lazy { DateTimeFormatter.ofPattern(LOG_DIR_DATE_FORMAT) }
    private val date = dateFormatter.format(LocalDateTime.now())

    fun install(deviceId: String, path: Path) {
        runCommand(
            listOf(
                "xcrun",
                "devicectl",
                "device",
                "install",
                "app",
                "--device",
                deviceId,
                path.toAbsolutePath().toString(),
            )
        )
    }

    fun launchRunner(deviceId: String, port: Int, snapshotKeyHonorModalViews: Boolean?) {
        val outputFile = File(XCRunnerCLIUtils.logDirectory, "xctest_runner_$date.log")
        val params = mutableMapOf("SIMCTL_CHILD_PORT" to port.toString())
        if (snapshotKeyHonorModalViews != null) {
            params["SIMCTL_CHILD_snapshotKeyHonorModalViews"] = snapshotKeyHonorModalViews.toString()
        }
        runCommand(
            listOf(
                "xcrun",
                "devicectl",
                "device",
                "process",
                "launch",
                "--terminate-existing",
                "--device",
                deviceId,
                "dev.mobile.maestro-driver-iosUITests.xctrunner"
            ),
            params = params,
            waitForCompletion = false,
            outputFile = outputFile
        )
    }
}