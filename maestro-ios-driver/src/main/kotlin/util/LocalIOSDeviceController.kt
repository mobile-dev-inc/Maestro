package util

import util.CommandLineUtils.runCommand
import java.io.File
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object LocalIOSDeviceController {

    private val dateFormatter by lazy { DateTimeFormatter.ofPattern(XCTEST_LOG_DATE_FORMAT) }
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

    fun launchRunner(deviceId: String, port: Int, snapshotKeyHonorModalViews: Boolean?, logsDir: File) {
        val outputFile = xctestLogFile(logsDir, date)
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
