package util

import maestro.utils.TempFileHandler
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

class GoIosNotFoundException(message: String) : RuntimeException(message)
class GoIosForwardException(message: String) : RuntimeException(message)

/**
 * Wrapper around the go-ios CLI (https://github.com/danielpaulus/go-ios, binary name `ios`).
 *
 * go-ios covers physical-device operations Apple's own tooling cannot perform headlessly:
 * location simulation, syslog capture, crash-report export, and terminate-by-bundle-id.
 */
class GoIosHelper(
    private val binary: Path = resolveBinary(),
    private val pairRecordPath: Path = defaultPairRecordPath(),
    private val tunnelInfoPort: Int = findAvailablePort(),
) : AutoCloseable {

    /** Retains the constructor descriptor published before tunnel configuration was added. */
    constructor(binary: Path) : this(binary, defaultPairRecordPath(), findAvailablePort())

    private val logger = LoggerFactory.getLogger(GoIosHelper::class.java)
    private val tempFileHandler = TempFileHandler()

    private var tunnelProcess: Process? = null
    private var tunnelUdid: String? = null
    private var locationProcess: Process? = null

    init {
        preparePairRecordPath(pairRecordPath)
    }

    /**
     * Start a userspace developer-services tunnel (needed by location and some log paths on
     * iOS 17+). Idempotent per helper instance; torn down by [close].
     */
    fun ensureTunnel(udid: String) {
        if (tunnelProcess?.isAlive == true && tunnelUdid == udid) return
        tunnelProcess?.terminate()

        val command = buildTunnelCommand(binary, udid, pairRecordPath, tunnelInfoPort)
        val output = tempFileHandler.createTempFile("go-ios-tunnel", ".log")
        logger.info("[Start] go-ios userspace tunnel for $udid")
        tunnelProcess = ProcessBuilder(command)
            .redirectOutput(output)
            .redirectErrorStream(true)
            .start()

        try {
            val deadline = System.currentTimeMillis() + TUNNEL_STARTUP_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (tunnelProcess?.isAlive != true) {
                    tunnelUdid = null
                    throw GoIosForwardException(
                        "go-ios tunnel exited before it was ready; location and log capture need it on iOS 17+. " +
                                "$TUNNEL_HINT Output: ${output.readText().takeLast(1000)}"
                    )
                }
                if (isTunnelReady(udid)) {
                    tunnelUdid = udid
                    return
                }
                Thread.sleep(TUNNEL_POLL_INTERVAL_MS)
            }
        } catch (e: InterruptedException) {
            tunnelProcess?.terminate()
            tunnelProcess = null
            tunnelUdid = null
            Thread.currentThread().interrupt()
            throw GoIosForwardException("Interrupted while starting go-ios tunnel for $udid").apply {
                initCause(e)
            }
        }

        tunnelProcess?.terminate()
        tunnelProcess = null
        tunnelUdid = null
        throw GoIosForwardException(
            "go-ios tunnel did not become ready within ${TUNNEL_STARTUP_TIMEOUT_MS / 1000}s. " +
                    "$TUNNEL_HINT Output: ${output.readText().takeLast(1000)}"
        )
    }

    private fun isTunnelReady(udid: String): Boolean = try {
        val connection = URI("http://127.0.0.1:$tunnelInfoPort/tunnel/$udid")
            .toURL()
            .openConnection() as HttpURLConnection
        connection.connectTimeout = TUNNEL_HTTP_TIMEOUT_MS
        connection.readTimeout = TUNNEL_HTTP_TIMEOUT_MS
        connection.requestMethod = "GET"
        try {
            connection.responseCode in 200..299
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Simulate device location. Verified on iOS 26: `ios setlocation` holds the simulated
     * location for as long as the process runs (it does not exit on success), so it is
     * managed as a tracked long-lived process — replaced on the next call, killed by [close].
     * The legacy pre-iOS-17 path exits zero after applying the location; a quick non-zero
     * exit triggers a userspace-tunnel retry for iOS 17+.
     */
    fun setLocation(latitude: Double, longitude: Double, udid: String) {
        locationProcess?.terminate()
        val output = tempFileHandler.createTempFile("go-ios-setlocation", ".log")
        locationProcess = try {
            startLocationProcess(latitude, longitude, udid, output)
        } catch (directFailure: GoIosForwardException) {
            ensureTunnel(udid)
            try {
                startLocationProcess(latitude, longitude, udid, output)
            } catch (tunnelFailure: GoIosForwardException) {
                tunnelFailure.addSuppressed(directFailure)
                throw tunnelFailure
            }
        }

        locationProcess?.let { process ->
            logger.info("Simulating location $latitude,$longitude (held by go-ios pid=${process.pid()})")
        } ?: logger.info("Simulated location $latitude,$longitude")
    }

    private fun startLocationProcess(latitude: Double, longitude: Double, udid: String, output: File): Process? {
        val process = ProcessBuilder(
            buildSetLocationCommand(binary, latitude, longitude, udid, tunnelInfoPort)
        )
            .redirectOutput(output)
            .redirectErrorStream(true)
            .start()
        var ownershipTransferred = false
        try {
            if (!process.waitFor(SETLOCATION_FAILURE_WINDOW_MS, TimeUnit.MILLISECONDS)) {
                ownershipTransferred = true
                return process
            }

            if (process.exitValue() == 0) {
                return null
            }

            throw GoIosForwardException(
                "go-ios setlocation exited with code ${process.exitValue()}: ${output.readText().takeLast(500)}"
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw GoIosForwardException("Interrupted while starting go-ios location simulation").apply {
                initCause(e)
            }
        } finally {
            if (!ownershipTransferred && process.isAlive) process.terminate()
        }
    }

    /** Legacy pre-iOS-17 reset; on tunnel-based devices, killing the setlocation process resets. */
    fun resetLocation(udid: String) {
        locationProcess?.terminate()
        locationProcess = null
        runCatching { CommandLineUtils.runCommand(buildResetLocationCommand(binary, udid, tunnelInfoPort)) }
    }

    /** Stream device syslog into [outputFile] until the returned process is destroyed. */
    fun startSyslog(udid: String, outputFile: File): Process {
        val directProcess = startSyslogProcess(udid, outputFile)
        try {
            if (!directProcess.waitFor(COMMAND_FAILURE_WINDOW_MS, TimeUnit.MILLISECONDS)) {
                return directProcess
            }
        } catch (e: InterruptedException) {
            directProcess.terminate()
            Thread.currentThread().interrupt()
            throw GoIosForwardException("Interrupted while starting go-ios syslog").apply { initCause(e) }
        }

        ensureTunnel(udid)
        val tunneledProcess = startSyslogProcess(udid, outputFile)
        try {
            if (tunneledProcess.waitFor(COMMAND_FAILURE_WINDOW_MS, TimeUnit.MILLISECONDS)) {
                throw GoIosForwardException(
                    "go-ios syslog exited with code ${tunneledProcess.exitValue()}: " +
                            outputFile.readText().takeLast(500)
                )
            }
            return tunneledProcess
        } catch (e: InterruptedException) {
            tunneledProcess.terminate()
            Thread.currentThread().interrupt()
            throw GoIosForwardException("Interrupted while starting tunneled go-ios syslog").apply { initCause(e) }
        }
    }

    private fun startSyslogProcess(udid: String, outputFile: File): Process =
        ProcessBuilder(buildSyslogCommand(binary, udid, tunnelInfoPort))
            .redirectOutput(outputFile)
            .redirectErrorStream(true)
            .start()

    /** Export all crash reports from the device into [targetDir]. */
    fun exportCrashes(udid: String, targetDir: File) {
        targetDir.mkdirs()
        CommandLineUtils.runCommand(buildCrashExportCommand(binary, targetDir, udid))
    }

    /** Terminate an app by bundle id (fallback for when no pid is known). */
    fun kill(bundleId: String, udid: String) {
        try {
            CommandLineUtils.runCommand(buildKillCommand(binary, bundleId, udid, tunnelInfoPort))
        } catch (directFailure: Exception) {
            ensureTunnel(udid)
            try {
                CommandLineUtils.runCommand(buildKillCommand(binary, bundleId, udid, tunnelInfoPort))
            } catch (tunnelFailure: Exception) {
                tunnelFailure.addSuppressed(directFailure)
                throw tunnelFailure
            }
        }
    }

    override fun close() {
        try {
            locationProcess?.terminate()
        } finally {
            locationProcess = null
            try {
                tunnelProcess?.terminate()
            } finally {
                tunnelProcess = null
                tunnelUdid = null
                tempFileHandler.close()
            }
        }
    }

    companion object {
        private const val TUNNEL_STARTUP_TIMEOUT_MS = 15_000L
        private const val TUNNEL_POLL_INTERVAL_MS = 100L
        private const val TUNNEL_HTTP_TIMEOUT_MS = 250
        private const val SETLOCATION_FAILURE_WINDOW_MS = 3_000L
        private const val COMMAND_FAILURE_WINDOW_MS = 1_000L

        const val INSTALL_HINT = "Install go-ios (https://github.com/danielpaulus/go-ios): " +
                "`npm install -g go-ios`, or download a release binary and either put `ios` on PATH, " +
                "place it under ~/.maestro/deps/go-ios/, or point MAESTRO_GO_IOS_PATH at it."

        private const val TUNNEL_HINT = "go-ios userspace tunnels are unavailable on iOS 17.0-17.4.0; " +
                "those releases require a separately started privileged/CoreDevice tunnel."

        internal fun buildTunnelCommand(
            binary: Path,
            udid: String,
            pairRecordPath: Path,
            tunnelInfoPort: Int,
        ): List<String> = listOf(
            binary.toString(),
            "tunnel", "start", "--userspace",
            "--pair-record-path=${pairRecordPath.toAbsolutePath()}",
            "--tunnel-info-port=$tunnelInfoPort",
            "--udid=$udid",
        )

        internal fun buildSetLocationCommand(
            binary: Path,
            lat: Double,
            lon: Double,
            udid: String,
            tunnelInfoPort: Int,
        ): List<String> = listOf(
            binary.toString(), "setlocation", "--lat=$lat", "--lon=$lon",
            "--tunnel-info-port=$tunnelInfoPort", "--udid=$udid",
        )

        internal fun buildResetLocationCommand(binary: Path, udid: String, tunnelInfoPort: Int): List<String> =
            listOf(binary.toString(), "resetlocation", "--tunnel-info-port=$tunnelInfoPort", "--udid=$udid")

        internal fun buildSyslogCommand(binary: Path, udid: String, tunnelInfoPort: Int): List<String> =
            // --nojson: plain "Jul 6 02:40:29 host proc[pid] <Notice>: ..." lines for the artifact
            listOf(
                binary.toString(), "syslog", "--nojson",
                "--tunnel-info-port=$tunnelInfoPort", "--udid=$udid",
            )

        internal fun buildCrashExportCommand(binary: Path, targetDir: File, udid: String): List<String> =
            listOf(binary.toString(), "crash", "cp", "*", targetDir.absolutePath, "--udid=$udid")

        internal fun buildKillCommand(binary: Path, bundleId: String, udid: String, tunnelInfoPort: Int): List<String> =
            listOf(
                binary.toString(), "kill", bundleId,
                "--tunnel-info-port=$tunnelInfoPort", "--udid=$udid",
            )

        internal fun defaultPairRecordPath(
            home: Path = Paths.get(System.getProperty("user.home")),
        ): Path = home.resolve(".maestro").resolve("deps").resolve("go-ios").resolve("pair-records")

        private fun preparePairRecordPath(path: Path) {
            Files.createDirectories(path)
            runCatching {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
            }
        }

        private fun findAvailablePort(): Int =
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

        fun isAvailable(): Boolean = try {
            resolveBinary()
            true
        } catch (e: GoIosNotFoundException) {
            false
        }

        /**
         * Resolution order: MAESTRO_GO_IOS_PATH env var, ~/.maestro/deps/go-ios/{ios,go-ios},
         * then `ios`/`go-ios` on PATH.
         */
        fun resolveBinary(
            env: Map<String, String> = System.getenv(),
            home: Path = Paths.get(System.getProperty("user.home")),
        ): Path {
            env["MAESTRO_GO_IOS_PATH"]?.let {
                val explicit = Paths.get(it)
                if (isExecutable(explicit)) return explicit
                throw GoIosNotFoundException("MAESTRO_GO_IOS_PATH is set to $it but that is not an executable file")
            }

            val depsDir = home.resolve(".maestro").resolve("deps").resolve("go-ios")
            for (name in BINARY_NAMES) {
                val candidate = depsDir.resolve(name)
                if (isExecutable(candidate)) return candidate
            }

            val pathEntries = (env["PATH"] ?: "").split(File.pathSeparator).filter { it.isNotBlank() }
            for (dir in pathEntries) {
                for (name in BINARY_NAMES) {
                    val candidate = Paths.get(dir).resolve(name)
                    if (isExecutable(candidate)) return candidate
                }
            }

            throw GoIosNotFoundException("go-ios binary not found. $INSTALL_HINT")
        }

        private val BINARY_NAMES = listOf("ios", "go-ios")

        private fun isExecutable(path: Path): Boolean =
            Files.isRegularFile(path) && Files.isExecutable(path)
    }
}

private fun Process.terminate() {
    var interrupted = false
    try {
        if (!isAlive) return
        runCatching { destroy() }
        val stopped = try {
            waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            interrupted = true
            false
        }
        if (!stopped && isAlive) {
            runCatching { destroyForcibly() }
            try {
                waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
}
