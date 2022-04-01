package ios

import com.github.michaelbull.result.Result
import ios.device.AccessibilityNode
import ios.device.DeviceInfo
import ios.grpc.LogStreamListener
import ios.idb.InstrumentsTemplate
import java.io.InputStream
import java.nio.file.Path
import java.time.Duration

interface IOSDevice {
    val host: String

    fun deviceInfo(): Result<DeviceInfo, Throwable>

    fun contentDescriptor(): Result<List<AccessibilityNode>, Throwable>

    fun tap(x: Int, y: Int): Result<Unit, Throwable>

    /**
     * Installs application on the device.
     *
     * @param stream - input stream of zipped .app bundle
     */
    fun install(stream: InputStream): Result<Unit, Throwable>

    /**
     * Uninstalls the app.
     *
     * Idempotent. Operation succeeds if app is not installed.
     *
     * @param id = bundle id of the app to uninstall
     */
    fun uninstall(id: String): Result<Unit, Throwable>

    /**
     * Launches the app.
     *
     * @param id - bundle id of the app to launch
     * @param isWarmup - in case it is warmup and we're not waiting for the logs, the app can be launched from foreground
     */
    fun launch(id: String, isWarmup: Boolean): Result<Unit, Throwable>

    /**
     * Terminates the app.
     *
     * @param id - bundle id of the app to terminate
     */
    fun stop(id: String): Result<Unit, Throwable>

    /**
     * Creates a stream to listen to simulator system logs.
     *
     * @param deadlineDuration - duration in seconds after which the stream will be closed automatically
     * @param listener - func that listens to log stream Result<String> events, return true to force close the stream
     */
    fun log(deadlineDuration: Duration, listener: LogStreamListener)

    /**
     * Disposes of grpc managed channel to cancel any new calls, but continues previous calls
     */
    fun clearChannel()

    /**
     * Executes Xcode Instruments profiling operation with a given template
     *
     * @param id bundle id of the app
     * @param template Instruments template to be executed
     * @param operationDurationSeconds Parameter passed by to Instruments cli to stop recording
     * @param stopDelaySeconds Parameter for idb, when to stop the execution of the process
     */
    fun instruments(
        id: String,
        template: InstrumentsTemplate,
        operationDurationSeconds: Double,
        stopDelaySeconds: Double,
        listener: (Result<Path, Throwable>) -> Unit,
    )

    /**
     * Clears Library, Documents, tmp for provided app
     *
     * @param appBundleId i.e com.mobile.dev
     */
    fun clearAppData(appBundleId: String): Result<Unit, Throwable>

    /**
     * Pushes files into the app dir i.e com.reddit.app/...
     * @param appBundleId com.reddit.app
     * @param stream a zip file containing the files to push
     */
    fun pushAppFiles(appBundleId: String, stream: InputStream): Result<Unit, Throwable>
}
