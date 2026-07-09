package util

import maestro.utils.TempFileHandler
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant
import java.util.Properties
import java.util.concurrent.TimeUnit

class IProxyNotFoundException(message: String) : RuntimeException(message)
class IProxyForwardException(message: String) : RuntimeException(message)

/**
 * Loopback-only USB port forwarding via libusbmuxd's `iproxy`.
 *
 * go-ios's `forward` command binds 0.0.0.0 and would expose the unauthenticated XCTest
 * control API to the local network. iproxy supports an explicit source address, so every
 * forward created here is restricted to 127.0.0.1.
 */
class IProxyHelper(
    private val binary: Path = resolveBinary(),
    private val home: Path = Paths.get(System.getProperty("user.home")),
) : AutoCloseable {

    private val logger = LoggerFactory.getLogger(IProxyHelper::class.java)
    private val tempFileHandler = TempFileHandler()
    private val sessions = mutableListOf<ForwardSession>()

    class ForwardSession internal constructor(
        private val processHandle: ProcessHandle,
        private val registrationFile: Path,
        private val outputFile: File?,
        val hostPort: Int,
    ) : AutoCloseable {
        val isAlive: Boolean get() = processHandle.isAlive

        fun failureDescription(): String {
            val output = outputFile
                ?.takeIf { it.exists() }
                ?.runCatching { readText().takeLast(1000) }
                ?.getOrNull()
            return buildString {
                append("iproxy process ${processHandle.pid()} is no longer running")
                if (!output.isNullOrBlank()) append(". Output: $output")
            }
        }

        override fun close() {
            terminate(processHandle)
            deleteRegistrationIfOwned(registrationFile, processHandle.pid())
        }
    }

    fun forward(hostPort: Int, devicePort: Int, udid: String): ForwardSession {
        val registrationFile = registrationFile(home, udid, hostPort, devicePort)
        registeredForward(registrationFile, udid, hostPort, devicePort)?.let { existing ->
            if (existing.ownerIsAlive) {
                throw IProxyForwardException(
                    "Cannot forward XCTest port $hostPort because another active Maestro session " +
                            "owns the iproxy forward on 127.0.0.1:$hostPort."
                )
            }

            // The Maestro process that created this forward is gone, but iproxy survived it.
            // Reclaim only the exact child recorded in the registration file; PID start-time
            // and executable checks in registeredForward protect against PID reuse.
            terminate(existing.process)
            deleteRegistrationIfOwned(registrationFile, existing.process.pid())
        }
        Files.deleteIfExists(registrationFile)

        if (isPortBound(hostPort)) {
            throw IProxyForwardException(
                "Cannot forward XCTest port $hostPort because 127.0.0.1:$hostPort is already in use by another process."
            )
        }

        val command = buildForwardCommand(binary, hostPort, devicePort, udid)
        val output = tempFileHandler.createTempFile("iproxy-forward", ".log")
        logger.info("[Start] iproxy port forwarding 127.0.0.1:$hostPort -> device:$devicePort")
        val process = try {
            ProcessBuilder(command)
                .redirectOutput(output)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            throw IProxyForwardException(
                "Failed to start iproxy (${command.joinToString(" ")}): ${e.message}. $INSTALL_HINT"
            )
        }

        var ownershipTransferred = false
        try {
            val deadline = System.currentTimeMillis() + FORWARD_BIND_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (!process.isAlive) {
                    throw IProxyForwardException(
                        "iproxy exited with code ${process.exitValue()} before binding 127.0.0.1:$hostPort. " +
                                "Output: ${output.readText().takeLast(1000)}"
                    )
                }
                if (isPortBound(hostPort)) {
                    // Avoid mistaking a competing forward that won the bind race for this process.
                    Thread.sleep(BIND_STABILITY_WAIT_MS)
                    if (!process.isAlive) continue

                    try {
                        writeRegistration(
                            registrationFile,
                            process.toHandle(),
                            ProcessHandle.current(),
                            binary,
                            udid,
                            hostPort,
                            devicePort,
                        )
                    } catch (e: Exception) {
                        throw IProxyForwardException("Failed to register iproxy forward ownership: ${e.message}")
                    }
                    val session = ForwardSession(process.toHandle(), registrationFile, output, hostPort)
                    sessions.add(session)
                    ownershipTransferred = true
                    logger.info("[Done] iproxy forwarding 127.0.0.1:$hostPort -> device:$devicePort (pid=${process.pid()})")
                    return session
                }
                Thread.sleep(PORT_POLL_INTERVAL_MS)
            }

            throw IProxyForwardException(
                "iproxy did not bind 127.0.0.1:$hostPort within ${FORWARD_BIND_TIMEOUT_MS / 1000}s. " +
                        "Output: ${output.readText().takeLast(1000)}"
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IProxyForwardException("Interrupted while starting iproxy for 127.0.0.1:$hostPort").apply {
                initCause(e)
            }
        } finally {
            // Until ForwardSession is returned, this method is the sole owner of the child.
            if (!ownershipTransferred) {
                terminate(process.toHandle())
                deleteRegistrationIfOwned(registrationFile, process.pid())
            }
        }
    }

    override fun close() {
        try {
            sessions.toList().forEach { session ->
                runCatching { session.close() }
                    .onFailure { logger.warn("Failed to close iproxy forward on port ${session.hostPort}", it) }
            }
        } finally {
            sessions.clear()
            tempFileHandler.close()
        }
    }

    companion object {
        private const val FORWARD_BIND_TIMEOUT_MS = 15_000L
        private const val PORT_POLL_INTERVAL_MS = 100L
        private const val BIND_STABILITY_WAIT_MS = 150L
        private const val PROCESS_TERMINATION_TIMEOUT_MS = 3_000L

        const val INSTALL_HINT = "Install iproxy with `brew install libusbmuxd`, or put the iproxy binary on PATH, " +
                "place it at ~/.maestro/deps/iproxy, or point MAESTRO_IPROXY_PATH at it."

        internal fun buildForwardCommand(binary: Path, hostPort: Int, devicePort: Int, udid: String): List<String> =
            listOf(
                binary.toString(),
                "--udid", udid,
                "--local",
                "--source", "127.0.0.1",
                "$hostPort:$devicePort",
            )

        fun isAvailable(): Boolean = try {
            resolveBinary()
            true
        } catch (_: IProxyNotFoundException) {
            false
        }

        fun resolveBinary(
            env: Map<String, String> = System.getenv(),
            home: Path = Paths.get(System.getProperty("user.home")),
        ): Path {
            env["MAESTRO_IPROXY_PATH"]?.let {
                val explicit = Paths.get(it)
                if (isExecutable(explicit)) return explicit
                throw IProxyNotFoundException("MAESTRO_IPROXY_PATH is set to $it but that is not an executable file")
            }

            val bundled = home.resolve(".maestro").resolve("deps").resolve("iproxy")
            if (isExecutable(bundled)) return bundled

            (env["PATH"] ?: "")
                .split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .map { Paths.get(it).resolve("iproxy") }
                .firstOrNull(::isExecutable)
                ?.let { return it }

            throw IProxyNotFoundException("iproxy binary not found. $INSTALL_HINT")
        }

        private fun isExecutable(path: Path): Boolean = Files.isRegularFile(path) && Files.isExecutable(path)

        private fun isPortBound(port: Int): Boolean = try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 250) }
            true
        } catch (_: Exception) {
            false
        }

        private fun registrationFile(home: Path, udid: String, hostPort: Int, devicePort: Int): Path {
            val directory = home.resolve(".maestro").resolve("iproxy")
            Files.createDirectories(directory)
            runCatching {
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
            }
            val key = "$udid:$hostPort:$devicePort"
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
                .joinToString("") { "%02x".format(it) }
            return directory.resolve("$digest.properties")
        }

        private fun writeRegistration(
            target: Path,
            process: ProcessHandle,
            owner: ProcessHandle,
            binary: Path,
            udid: String,
            hostPort: Int,
            devicePort: Int,
        ) {
            val pending = target.resolveSibling("${target.fileName}.pending-${ProcessHandle.current().pid()}")
            try {
                Files.newBufferedWriter(pending).use { writer ->
                    Properties().apply {
                        setProperty("pid", process.pid().toString())
                        setProperty("startedAt", process.info().startInstant().orElse(Instant.EPOCH).toEpochMilli().toString())
                        setProperty("ownerPid", owner.pid().toString())
                        setProperty("ownerStartedAt", owner.info().startInstant().orElse(Instant.EPOCH).toEpochMilli().toString())
                        setProperty("binary", canonicalPath(binary))
                        setProperty("udid", udid)
                        setProperty("hostPort", hostPort.toString())
                        setProperty("devicePort", devicePort.toString())
                    }.store(writer, null)
                }
                Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(pending)
            }
        }

        private data class RegisteredForward(
            val process: ProcessHandle,
            val ownerIsAlive: Boolean,
        )

        private fun registeredForward(
            file: Path,
            udid: String,
            hostPort: Int,
            devicePort: Int,
        ): RegisteredForward? {
            if (!Files.isRegularFile(file)) return null
            val properties = runCatching {
                Properties().apply { Files.newBufferedReader(file).use(::load) }
            }.getOrNull() ?: return null
            if (properties.getProperty("udid") != udid ||
                properties.getProperty("hostPort") != hostPort.toString() ||
                properties.getProperty("devicePort") != devicePort.toString()
            ) return null

            val pid = properties.getProperty("pid")?.toLongOrNull() ?: return null
            val expectedStart = properties.getProperty("startedAt")?.toLongOrNull() ?: return null
            val process = ProcessHandle.of(pid).orElse(null) ?: return null
            val actualStart = process.info().startInstant().orElse(Instant.EPOCH).toEpochMilli()
            if (!process.isAlive || actualStart != expectedStart) return null

            val command = process.info().command().orElse("")
            val expectedBinary = properties.getProperty("binary")
            if (command.isNotBlank() && canonicalPath(Paths.get(command)) != expectedBinary) return null

            val ownerPid = properties.getProperty("ownerPid")?.toLongOrNull()
            val expectedOwnerStart = properties.getProperty("ownerStartedAt")?.toLongOrNull()
            val ownerIsAlive = if (ownerPid != null && expectedOwnerStart != null) {
                ProcessHandle.of(ownerPid).orElse(null)?.let { owner ->
                    owner.isAlive &&
                            owner.info().startInstant().orElse(Instant.EPOCH).toEpochMilli() == expectedOwnerStart
                } == true
            } else {
                // Registrations from older builds have no safe way to prove that their owner
                // is gone, so never kill the referenced process automatically.
                true
            }
            return RegisteredForward(process, ownerIsAlive)
        }

        private fun canonicalPath(path: Path): String = runCatching { path.toRealPath().toString() }
            .getOrDefault(path.toAbsolutePath().normalize().toString())

        private fun deleteRegistrationIfOwned(file: Path, expectedPid: Long?) {
            if (expectedPid == null) {
                Files.deleteIfExists(file)
                return
            }
            val registeredPid = runCatching {
                Properties().apply { Files.newBufferedReader(file).use(::load) }
                    .getProperty("pid")?.toLongOrNull()
            }.getOrNull()
            if (registeredPid == expectedPid) Files.deleteIfExists(file)
        }

        private fun terminate(process: ProcessHandle) {
            if (!process.isAlive) return
            var interrupted = false
            try {
                runCatching { process.destroy() }
                val deadline = System.currentTimeMillis() + PROCESS_TERMINATION_TIMEOUT_MS
                while (process.isAlive && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(50)
                    } catch (_: InterruptedException) {
                        interrupted = true
                        break
                    }
                }
                if (process.isAlive) {
                    runCatching { process.destroyForcibly() }
                    try {
                        process.onExit().get(PROCESS_TERMINATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        interrupted = true
                    } catch (_: Exception) {
                        // Best effort: the OS will finish reaping a forcibly terminated child.
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }
}
