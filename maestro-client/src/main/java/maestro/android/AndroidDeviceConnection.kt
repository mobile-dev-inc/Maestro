/*
 *
 *  Copyright (c) 2022 mobile.dev inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package maestro.android

import dadb.AdbAuthException
import dadb.AdbException
import dadb.AdbShellPacket
import dadb.AdbShellResponse
import dadb.AdbShellStream
import dadb.AdbStream
import dadb.Dadb
import dadb.InstallResult as DadbInstallResult
import dadb.SyncResult as DadbSyncResult
import dadb.UninstallResult as DadbUninstallResult
import dadb.adbserver.AdbServer
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.okhttp.OkHttpChannelBuilder
import maestro.DeviceDiagnostics
import maestro.DeviceUnreachableException
import maestro_android.MaestroDriverGrpc
import okio.Sink
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * The sole handle to a single Android device for the duration of a driver session.
 *
 * It is the only place a [Dadb] is created and the only owner of the gRPC channel
 * to the on-device server. Callers obtain one through the [companion][Companion]
 * factories and talk to the device exclusively through this type — they never see,
 * create, or close a raw [Dadb].
 *
 * Responsibilities:
 *  - own dadb + the gRPC transport,
 *  - model the four device failure modes (see [mapTransport]/[onDeath]),
 *  - expose a gRPC plane ([execute]/[stream]) and a dadb plane ([shell]/[install]/…).
 */
class AndroidDeviceConnection private constructor(
    private val dadb: Dadb,
    val serial: String,
    private val endpoint: Endpoint,
    val driverHostPort: Int,
    private val connectStartNanos: Long,
    // Test seams (null = production behaviour: real socket probe + real gRPC stubs over the lazy channel).
    private val transportProbe: (() -> Boolean)? = null,
    private val blockingStubProvider: (() -> MaestroDriverGrpc.MaestroDriverBlockingStub)? = null,
    private val asyncStubProvider: (() -> MaestroDriverGrpc.MaestroDriverStub)? = null,
) : AutoCloseable {

    /** adbd / adb-server endpoint used by the liveness probe. */
    data class Endpoint(val host: String, val port: Int)

    @Volatile
    var state: ConnectionState = ConnectionState.CONNECTED
        private set

    @Volatile
    private var lastByteNanos: Long = connectStartNanos

    // The gRPC channel is built lazily: enumeration/boot-probe callers that only
    // need the dadb plane never pay for standing one up.
    @Volatile
    private var channelHandle: ManagedChannel? = null

    private fun channel(): ManagedChannel =
        channelHandle ?: synchronized(this) {
            channelHandle ?: buildChannel().also { channelHandle = it }
        }

    private fun buildChannel(): ManagedChannel =
        OkHttpChannelBuilder.forAddress("localhost", driverHostPort)
            .usePlaintext()
            .socketFactory(AdbSocketFactory { _, port -> dadb.open("tcp:$port") })
            .keepAliveTime(2, TimeUnit.MINUTES)
            .keepAliveTimeout(20, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .build()

    private fun blockingStub() =
        blockingStubProvider?.invoke()
            ?: MaestroDriverGrpc.newBlockingStub(channel()).withDeadlineAfter(120, TimeUnit.SECONDS)

    private fun asyncStub() = asyncStubProvider?.invoke() ?: MaestroDriverGrpc.newStub(channel())

    fun name(): String = "Android Device ($serial)"

    // ── gRPC plane ──────────────────────────────────────────────────────────

    /**
     * Run a blocking device-server call. Returns the modelled [DeviceResponse];
     * only transport deaths throw.
     */
    fun <R> execute(operation: String, call: (MaestroDriverGrpc.MaestroDriverBlockingStub) -> R): DeviceResponse<R> {
        val response = try {
            DeviceResponse.Ok(call(blockingStub()))
        } catch (e: StatusRuntimeException) {
            if (e.status.code in TRANSPORT_DEATH_CODES) throw mapTransport(operation, e)
            DeviceResponse.Failure(operation, e.status.code, e.status.description.orEmpty(), e.errorDetails())
        }
        lastByteNanos = nanoNow() // answered (Ok or Failure) → transport is alive (only a death skips this).
        return response
    }

    /**
     * Run a streaming device-server call against the async stub. The whole exchange
     * happens inside [call]; transport deaths throw, everything else propagates.
     */
    fun <R> stream(operation: String, call: (MaestroDriverGrpc.MaestroDriverStub) -> R): R {
        val result = try {
            call(asyncStub())
        } catch (e: StatusRuntimeException) {
            if (e.status.code in TRANSPORT_DEATH_CODES) throw mapTransport(operation, e)
            throw e
        }
        lastByteNanos = nanoNow()
        return result
    }

    // ── dadb plane ──────────────────────────────────────────────────────────

    /**
     * Run an adb shell command. A non-zero exit code rides home inside the
     * [AdbShellResponse]; only a broken transport throws.
     */
    fun shell(command: String): AdbShellResponse =
        try {
            // A non-zero exit code rides home inside AdbShellResponse; only a transport death throws.
            dadb.shell(command).also { lastByteNanos = nanoNow() }
        } catch (e: AdbException) {
            throw mapTransport("shell: $command", e)
        }

    fun install(apk: File): InstallResult =
        try {
            // Operation outcome is the RETURNED result; only a transport AdbException is a device death.
            when (val r = dadb.install(apk)) {
                is DadbInstallResult.Success -> InstallResult.Success
                is DadbInstallResult.Failure -> InstallResult.Failure(r.reason)
            }.also { lastByteNanos = nanoNow() }
        } catch (e: AdbException) {
            throw mapTransport("install: ${apk.name}", e)
        }

    fun uninstall(packageName: String): UninstallResult =
        try {
            when (val r = dadb.uninstall(packageName)) {
                is DadbUninstallResult.Success -> UninstallResult.Success
                is DadbUninstallResult.Failure -> UninstallResult.Failure("${r.reason} (exit ${r.exitCode})")
            }.also { lastByteNanos = nanoNow() }
        } catch (e: AdbException) {
            throw mapTransport("uninstall: $packageName", e)
        }

    fun pull(local: File, remote: String): SyncResult =
        try {
            when (val r = dadb.pull(local, remote)) {
                is DadbSyncResult.Success -> SyncResult.Success
                is DadbSyncResult.Failure -> SyncResult.Failure(r.reason)
            }.also { lastByteNanos = nanoNow() }
        } catch (e: AdbException) {
            throw mapTransport("pull: $remote", e)
        }

    fun pull(sink: Sink, remote: String): SyncResult =
        try {
            when (val r = dadb.pull(sink, remote)) {
                is DadbSyncResult.Success -> SyncResult.Success
                is DadbSyncResult.Failure -> SyncResult.Failure(r.reason)
            }.also { lastByteNanos = nanoNow() }
        } catch (e: AdbException) {
            throw mapTransport("pull: $remote", e)
        }

    /** True once this connection has been closed (or its gRPC channel shut down). */
    /**
     * True once this connection is finished — explicitly [close]d, or any transport death recorded by
     * [markDead] (a gRPC-plane DeviceServerDied/Unreachable or a dadb-plane Unreachable). The sole
     * consumer (TestRunner) uses it to decide whether a caught exception is a real flow error or just a
     * consequence of the connection dying. The gRPC channel only ever shuts down inside [close] — which
     * already sets DEAD — so [state] is the single source of truth.
     */
    fun isShutdown(): Boolean = state == ConnectionState.DEAD

    // ── ancillary dadb ops — `internal`, so raw adb streams never leave the module. The sanctioned
    //    in-module collaborators are AndroidAppFiles (exec:run-as file transfer) and
    //    DadbChromeDevToolsClient (localabstract CDP socket); external consumers use the semantic ops. ──

    internal fun openShell(command: String, operation: String = "openShell: $command"): AdbShellStream =
        try {
            dadb.openShell(command)
        } catch (e: AdbException) {
            throw mapTransport(operation, e)
        }

    internal fun open(destination: String): AdbStream =
        try {
            dadb.open(destination)
        } catch (e: AdbException) {
            throw mapTransport("open: $destination", e)
        }

    internal fun push(local: File, remote: String): SyncResult =
        try {
            when (val r = dadb.push(local, remote)) {
                is DadbSyncResult.Success -> SyncResult.Success
                is DadbSyncResult.Failure -> SyncResult.Failure(r.reason)
            }.also { lastByteNanos = nanoNow() }
        } catch (e: AdbException) {
            throw mapTransport("push: $remote", e)
        }

    // ── device-server lifecycle — semantic ops; the raw adb stream never leaves the connection ──

    /**
     * Launch [command] as an instrumentation shell session (the on-device `am instrument …` that
     * hosts the gRPC server) and return a managed handle. The driver builds the command; the
     * connection owns the underlying adb shell stream and never hands it out.
     */
    fun startInstrumentation(command: String): InstrumentationSession =
        DadbInstrumentationSession(openShell(command, operation = "instrumentation"))

    /**
     * True if the on-device driver gRPC server is accepting connections on [port]. A pure liveness
     * probe used while waiting for the server to come up: it swallows failure and never mutates
     * connection [state] — a not-yet-open port during startup is expected, not a device death.
     */
    fun isDriverReachable(port: Int): Boolean =
        runCatching { dadb.open("tcp:$port").close(); true }.getOrDefault(false)

    /** Start a detached background shell command (e.g. `nohup … &`); does not wait for or expose the stream. */
    fun execDetached(command: String) {
        openShell(command)
    }

    /**
     * A handle to a running instrumentation shell session. Owns the underlying adb shell stream;
     * [close] tears the instrumentation down. Callers never see the raw stream.
     */
    interface InstrumentationSession : AutoCloseable {
        /** Reads the instrumentation's first output and reports whether it came up cleanly (no stderr/FAILED/UNABLE). */
        fun startedSuccessfully(): Boolean
    }

    private class DadbInstrumentationSession(private val stream: AdbShellStream) : InstrumentationSession {
        override fun startedSuccessfully(): Boolean = instrumentationStartedCleanly(stream.read())

        override fun close() {
            runCatching { stream.close() }
        }
    }

    override fun close() {
        state = ConnectionState.DEAD
        channelHandle?.let { channel ->
            channel.shutdown()
            // Don't let a slow channel shutdown throw: close() runs in AndroidDriver.close()'s finally, so a
            // TimeoutException here would mask an in-flight transport death (Java drops the original when a
            // finally throws). Log and move on — same best-effort spirit as the dadb.close() below.
            runCatching {
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOGGER.warn("gRPC channel did not terminate within 5s during close()")
                }
            }
        }
        runCatching { dadb.close() }
    }

    // ── failure classification ────────────────────────────────────────────────

    private fun mapTransport(operation: String, cause: Throwable): IOException = when (cause) {
        is AdbAuthException -> DeviceAuthException(serial, cause)          // CONFIG, either plane
        is StatusRuntimeException -> onGrpcDeath(operation, cause)         // gRPC plane: probe → ServerDied vs Unreachable
        // dadb-plane AdbException (and any unexpected cause) — the adb transport is gone, never ServerDied.
        else -> onAdbDeath(operation, cause)
    }

    /**
     * gRPC-plane death (MODE 1 or 2). Probe adbd to disambiguate: reachable ⇒ the on-device server
     * died while the transport is fine ([DeviceServerDiedException]); gone ⇒ the whole device is
     * unreachable ([DeviceUnreachableException]).
     */
    private fun onGrpcDeath(operation: String, cause: Throwable): IOException {
        val diagnostics = markDead(operation, cause)
        return if (transportAlive()) {
            DeviceServerDiedException(diagnostics, cause)
        } else {
            DeviceUnreachableException(diagnostics.operation, cause, diagnostics)
        }
    }

    /**
     * dadb-plane death. An [AdbException] means the adb transport itself broke; there is no on-device
     * server in this path, so the device's transport is simply gone — always [DeviceUnreachableException],
     * never [DeviceServerDiedException]. No probe: a fresh socket to adbd happening to connect wouldn't
     * change the outcome, so it would only waste a round-trip.
     */
    private fun onAdbDeath(operation: String, cause: Throwable): IOException {
        val diagnostics = markDead(operation, cause)
        return DeviceUnreachableException(diagnostics.operation, cause, diagnostics)
    }

    private fun markDead(operation: String, cause: Throwable): DeviceDiagnostics {
        state = ConnectionState.DEAD
        return DeviceDiagnostics(
            operation = operation,
            rootCause = "${cause::class.simpleName}: ${cause.message}",
            serial = serial,
            msSinceLastByte = (nanoNow() - lastByteNanos).ms(),
            connectionAgeMs = (nanoNow() - connectStartNanos).ms(),
        )
    }

    /** Can we still reach adbd? Bounded, never throws — failure IS the answer. Injectable for tests. */
    private fun transportAlive(): Boolean = transportProbe?.invoke() ?: probeEndpoint(endpoint)

    companion object {
        private val LOGGER = LoggerFactory.getLogger(AndroidDeviceConnection::class.java)

        const val DEFAULT_DRIVER_HOST_PORT = 7001
        private const val DEFAULT_ADB_SERVER_PORT = 5037
        // 1s: a liveness probe must stay quick — better to occasionally misjudge a momentarily-busy
        // adbd as unreachable than to block the failure path. Bounds the connect in probeEndpoint().
        private const val PROBE_MS = 1000

        /** An instrumentation came up cleanly if its first output isn't stderr and reports no FAILED/UNABLE. */
        internal fun instrumentationStartedCleanly(firstOutput: AdbShellPacket): Boolean =
            !(firstOutput is AdbShellPacket.StdError ||
                firstOutput.toString().contains("FAILED", true) ||
                firstOutput.toString().contains("UNABLE", true))

        // gRPC status codes that mean the transport died (not a status the server answered with).
        // DEADLINE_EXCEEDED is the gRPC analogue of dadb's AdbTimeoutException; both are device deaths.
        private val TRANSPORT_DEATH_CODES = setOf(Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED)

        private val ERROR_TYPE_KEY: Metadata.Key<String> =
            Metadata.Key.of("error-type", Metadata.ASCII_STRING_MARSHALLER)
        private val ERROR_MSG_KEY: Metadata.Key<String> =
            Metadata.Key.of("error-message", Metadata.ASCII_STRING_MARSHALLER)
        private val ERROR_CAUSE_KEY: Metadata.Key<String> =
            Metadata.Key.of("error-cause", Metadata.ASCII_STRING_MARSHALLER)

        // ── the only Dadb.create / discover / list in the codebase ──────────────

        /** Connect directly to an adbd at [host]:[port]. */
        fun open(host: String, port: Int, driverHostPort: Int = DEFAULT_DRIVER_HOST_PORT): AndroidDeviceConnection {
            val dadb = Dadb.create(host, port)
            return wrap(dadb, Endpoint(host, port), driverHostPort)
        }

        /**
         * Connect directly to an adbd at [host]:[port] with explicit dadb socket settings, for callers
         * that need non-default timeouts / keepAlive (e.g. maestro-worker). The connection still creates
         * and solely owns the dadb — only the *inputs* are injected, never a pre-built [Dadb].
         */
        fun open(
            host: String,
            port: Int,
            driverHostPort: Int,
            connectTimeoutMs: Int,
            socketTimeoutMs: Int,
            keepAlive: Boolean,
        ): AndroidDeviceConnection {
            val dadb = Dadb.create(
                host,
                port,
                connectTimeout = connectTimeoutMs,
                socketTimeout = socketTimeoutMs,
                keepAlive = keepAlive,
            )
            return wrap(dadb, Endpoint(host, port), driverHostPort)
        }

        /** Discover a single device reachable through [host]. */
        fun discover(host: String, driverHostPort: Int = DEFAULT_DRIVER_HOST_PORT): AndroidDeviceConnection? {
            val dadb = Dadb.discover(host) ?: return null
            return wrap(dadb, Endpoint(host, DEFAULT_ADB_SERVER_PORT), driverHostPort)
        }

        /** Find the device whose serial equals [deviceId] among those reachable through [host]. */
        fun byId(
            deviceId: String,
            host: String = "localhost",
            driverHostPort: Int = DEFAULT_DRIVER_HOST_PORT,
        ): AndroidDeviceConnection? {
            val dadb = Dadb.list(host).find { it.toString() == deviceId } ?: return null
            return wrap(dadb, Endpoint(host, DEFAULT_ADB_SERVER_PORT), driverHostPort)
        }

        /** Connect through a running adb server on [adbServerPort]. */
        fun adbServer(adbServerPort: Int, driverHostPort: Int = DEFAULT_DRIVER_HOST_PORT): AndroidDeviceConnection? =
            try {
                val dadb = AdbServer.createDadb(adbServerPort = adbServerPort)
                wrap(dadb, Endpoint("localhost", adbServerPort), driverHostPort)
            } catch (e: Exception) {
                LOGGER.debug("No adb server reachable on port $adbServerPort: ${e.message}")
                null
            }

        /**
         * The newest device not already represented in [connectedSerials] — used while
         * booting freshly launched emulators.
         */
        fun newestNotIn(
            connectedSerials: Collection<String>,
            host: String = "localhost",
            driverHostPort: Int = DEFAULT_DRIVER_HOST_PORT,
        ): AndroidDeviceConnection? {
            val dadb = Dadb.list(host).lastOrNull { it.toString() !in connectedSerials } ?: return null
            return wrap(dadb, Endpoint(host, DEFAULT_ADB_SERVER_PORT), driverHostPort)
        }

        /** Enumerate every device reachable through [host]. Each connection must be closed by the caller. */
        fun list(host: String = "localhost", driverHostPort: Int = DEFAULT_DRIVER_HOST_PORT): List<AndroidDeviceConnection> =
            Dadb.list(host).map { wrap(it, Endpoint(host, DEFAULT_ADB_SERVER_PORT), driverHostPort) }

        /** Enumerate every device reachable through the adb server on [adbServerPort]. */
        fun listFromAdbServer(
            adbServerPort: Int,
            driverHostPort: Int = DEFAULT_DRIVER_HOST_PORT,
        ): List<AndroidDeviceConnection> =
            AdbServer.listDadbs(adbServerPort = adbServerPort)
                .map { wrap(it, Endpoint("localhost", adbServerPort), driverHostPort) }

        /** Wrap a freshly-created [dadb] (the sole birthplace is the factories above; never injected). */
        private fun wrap(dadb: Dadb, endpoint: Endpoint, driverHostPort: Int): AndroidDeviceConnection =
            AndroidDeviceConnection(
                dadb = dadb,
                serial = dadb.toString(),
                endpoint = endpoint,
                driverHostPort = driverHostPort,
                connectStartNanos = nanoNow(),
            )

        private fun nanoNow(): Long = System.nanoTime()

        private fun Long.ms(): Long = TimeUnit.NANOSECONDS.toMillis(this)

        private fun StatusRuntimeException.errorDetails(): List<ErrorDetail> {
            val trailers = Status.trailersFromThrowable(this) ?: return emptyList()
            return listOfNotNull(
                trailers.get(ERROR_TYPE_KEY)?.let { ErrorDetail("error-type", it) },
                trailers.get(ERROR_MSG_KEY)?.let { ErrorDetail("error-message", it) },
                trailers.get(ERROR_CAUSE_KEY)?.let { ErrorDetail("error-cause", it) },
            )
        }

        /** The real liveness probe: a bounded TCP connect to the endpoint. Never throws — failure IS the answer. */
        private fun probeEndpoint(endpoint: Endpoint): Boolean =
            try {
                Socket().use { it.connect(InetSocketAddress(endpoint.host, endpoint.port), PROBE_MS) }
                true
            } catch (_: Throwable) {
                false
            }

        /**
         * Test seam: construct a connection over a fake [dadb] with a controllable liveness [transportProbe]
         * and injectable gRPC stubs. Production code goes through the factories above, which use the real
         * socket probe and the lazy gRPC channel.
         */
        internal fun forTest(
            dadb: Dadb,
            serial: String = "test-serial",
            endpoint: Endpoint = Endpoint("localhost", 0),
            driverHostPort: Int = DEFAULT_DRIVER_HOST_PORT,
            transportProbe: () -> Boolean = { false },
            blockingStubProvider: () -> MaestroDriverGrpc.MaestroDriverBlockingStub = { error("blocking stub not provided") },
            asyncStubProvider: () -> MaestroDriverGrpc.MaestroDriverStub = { error("async stub not provided") },
        ): AndroidDeviceConnection =
            AndroidDeviceConnection(
                dadb = dadb,
                serial = serial,
                endpoint = endpoint,
                driverHostPort = driverHostPort,
                connectStartNanos = nanoNow(),
                transportProbe = transportProbe,
                blockingStubProvider = blockingStubProvider,
                asyncStubProvider = asyncStubProvider,
            )
    }
}
