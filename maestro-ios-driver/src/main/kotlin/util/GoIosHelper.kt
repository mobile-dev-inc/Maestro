package util

import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class GoIosNotFoundException(message: String) : RuntimeException(message)
class GoIosForwardException(message: String) : RuntimeException(message)

/**
 * Wrapper around the go-ios CLI (https://github.com/danielpaulus/go-ios, binary name `ios`).
 *
 * go-ios covers the operations Apple's own tooling cannot perform headlessly against a
 * physical device: USB port forwarding to the on-device XCTest HTTP server, location
 * simulation, syslog capture and crash-report export.
 */
class GoIosHelper(
    private val binary: Path = resolveBinary(),
) : AutoCloseable {

    private val logger = LoggerFactory.getLogger(GoIosHelper::class.java)

    private var tunnelProcess: Process? = null

    /**
     * A running `ios forward` process. The forwarder must outlive every HTTP call to the
     * on-device XCTest server; callers should check [isAlive] when a connection fails so a
     * dead forwarder surfaces as a clear error instead of a generic timeout.
     */
    class ForwardSession internal constructor(
        private val process: Process,
        val hostPort: Int,
    ) : AutoCloseable {
        val isAlive: Boolean get() = process.isAlive

        override fun close() {
            if (process.isAlive) {
                process.destroy()
                process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }

    /**
     * Forward [hostPort] on localhost to [devicePort] on the device over USB.
     *
     * Fails loudly: throws if the process cannot start or exits before the local port is
     * bound, and polls the port instead of sleeping a fixed interval.
     */
    fun forward(hostPort: Int, devicePort: Int, udid: String): ForwardSession {
        val command = buildForwardCommand(binary, hostPort, devicePort, udid)
        logger.info("[Start] go-ios port forwarding $hostPort -> device:$devicePort")
        val output = File.createTempFile("go-ios-forward", ".log").apply { deleteOnExit() }
        val process = try {
            ProcessBuilder(command)
                .redirectOutput(output)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            throw GoIosForwardException(
                "Failed to start go-ios port forwarding (${command.joinToString(" ")}): ${e.message}. " +
                        INSTALL_HINT
            )
        }

        val deadline = System.currentTimeMillis() + FORWARD_BIND_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                throw GoIosForwardException(
                    "go-ios forward exited with code ${process.exitValue()} before binding port $hostPort. " +
                            "Output: ${output.readText().takeLast(1000)}"
                )
            }
            if (isPortBound(hostPort)) {
                logger.info("[Done] go-ios forwarding $hostPort -> device:$devicePort (pid=${process.pid()})")
                return ForwardSession(process, hostPort)
            }
            Thread.sleep(100)
        }
        process.destroy()
        throw GoIosForwardException(
            "go-ios forward did not bind local port $hostPort within ${FORWARD_BIND_TIMEOUT_MS / 1000}s. " +
                    "Output: ${output.readText().takeLast(1000)}"
        )
    }

    private fun isPortBound(port: Int): Boolean {
        return try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 250) }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Start a userspace developer-services tunnel (needed by location and some log paths on
     * iOS 17+). Idempotent per helper instance; torn down by [close].
     */
    fun ensureTunnel(udid: String) {
        if (tunnelProcess?.isAlive == true) return
        val command = buildTunnelCommand(binary, udid)
        logger.info("[Start] go-ios userspace tunnel for $udid")
        tunnelProcess = ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        // The tunnel needs a moment to establish before dependent commands can use it.
        Thread.sleep(TUNNEL_STARTUP_WAIT_MS)
        if (tunnelProcess?.isAlive != true) {
            throw GoIosForwardException("go-ios tunnel exited immediately; location and log capture need it on iOS 17+")
        }
    }

    fun setLocation(latitude: Double, longitude: Double, udid: String) {
        ensureTunnel(udid)
        CommandLineUtils.runCommand(buildSetLocationCommand(binary, latitude, longitude, udid))
    }

    fun resetLocation(udid: String) {
        CommandLineUtils.runCommand(buildResetLocationCommand(binary, udid))
    }

    /** Stream device syslog into [outputFile] until the returned process is destroyed. */
    fun startSyslog(udid: String, outputFile: File): Process {
        return ProcessBuilder(buildSyslogCommand(binary, udid))
            .redirectOutput(outputFile)
            .redirectErrorStream(true)
            .start()
    }

    /** Export all crash reports from the device into [targetDir]. */
    fun exportCrashes(udid: String, targetDir: File) {
        targetDir.mkdirs()
        CommandLineUtils.runCommand(buildCrashExportCommand(binary, targetDir, udid))
    }

    /** Terminate an app by bundle id (fallback for when no pid is known). */
    fun kill(bundleId: String, udid: String) {
        CommandLineUtils.runCommand(buildKillCommand(binary, bundleId, udid))
    }

    override fun close() {
        tunnelProcess?.let {
            if (it.isAlive) it.destroy()
        }
        tunnelProcess = null
    }

    companion object {
        private const val FORWARD_BIND_TIMEOUT_MS = 15_000L
        private const val TUNNEL_STARTUP_WAIT_MS = 2_000L

        const val INSTALL_HINT = "Install go-ios (https://github.com/danielpaulus/go-ios): " +
                "`npm install -g go-ios`, or download a release binary and either put `ios` on PATH, " +
                "place it under ~/.maestro/deps/go-ios/, or point MAESTRO_GO_IOS_PATH at it."

        internal fun buildForwardCommand(binary: Path, hostPort: Int, devicePort: Int, udid: String): List<String> =
            listOf(binary.toString(), "forward", hostPort.toString(), devicePort.toString(), "--udid=$udid")

        internal fun buildTunnelCommand(binary: Path, udid: String): List<String> =
            listOf(binary.toString(), "tunnel", "start", "--userspace", "--udid=$udid")

        internal fun buildSetLocationCommand(binary: Path, lat: Double, lon: Double, udid: String): List<String> =
            listOf(binary.toString(), "setlocation", "--lat=$lat", "--lon=$lon", "--udid=$udid")

        internal fun buildResetLocationCommand(binary: Path, udid: String): List<String> =
            listOf(binary.toString(), "resetlocation", "--udid=$udid")

        internal fun buildSyslogCommand(binary: Path, udid: String): List<String> =
            listOf(binary.toString(), "syslog", "--udid=$udid")

        internal fun buildCrashExportCommand(binary: Path, targetDir: File, udid: String): List<String> =
            listOf(binary.toString(), "crash", "cp", "*", targetDir.absolutePath, "--udid=$udid")

        internal fun buildKillCommand(binary: Path, bundleId: String, udid: String): List<String> =
            listOf(binary.toString(), "kill", bundleId, "--udid=$udid")

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
