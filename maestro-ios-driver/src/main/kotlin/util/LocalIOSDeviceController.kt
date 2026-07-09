package util

import maestro.utils.TempFileHandler
import org.rauschig.jarchivelib.ArchiveFormat
import org.rauschig.jarchivelib.ArchiverFactory
import util.CommandLineUtils.runCommand
import java.io.File
import java.io.InputStream
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object LocalIOSDeviceController {

    private val dateFormatter by lazy { DateTimeFormatter.ofPattern(XCTEST_LOG_DATE_FORMAT) }
    private val date = dateFormatter.format(LocalDateTime.now())

    /**
     * Install an app from a zipped .app/.ipa stream. The bundle must be signed for the
     * target device; devicectl's own error is surfaced verbatim when it is not.
     */
    fun install(deviceId: String, stream: InputStream) {
        TempFileHandler().use { tempFileHandler ->
            installFromArchive(deviceId, stream, tempFileHandler)
        }
    }

    /**
     * Compatibility overload for callers that own a wider temporary-file lifecycle.
     * New call sites should use the two-argument overload so cleanup is automatic.
     */
    @Deprecated("Use install(deviceId, stream) unless the caller owns and closes tempFileHandler")
    fun install(
        deviceId: String,
        stream: InputStream,
        tempFileHandler: TempFileHandler = TempFileHandler(),
    ) {
        installFromArchive(deviceId, stream, tempFileHandler)
    }

    private fun installFromArchive(
        deviceId: String,
        stream: InputStream,
        tempFileHandler: TempFileHandler,
    ) {
        val extractDir = tempFileHandler.createTempDirectory()

        ArchiverFactory
            .createArchiver(ArchiveFormat.ZIP)
            .extract(stream, extractDir)

        val app = extractDir.walk()
            .firstOrNull { it.name.endsWith(".app") || it.extension == "ipa" }
            ?: throw IllegalArgumentException("No .app or .ipa found in the provided app archive")

        install(deviceId, app.toPath())
    }

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
