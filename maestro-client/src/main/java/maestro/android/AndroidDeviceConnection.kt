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
import java.util.concurrent.TimeoutException

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
    fun isShutdown(): Boolean = channelHandle?.isShutdown ?: (state == ConnectionState.DEAD)

    // ── ancillary dadb ops — module-internal collaborators only ───────────────

    internal fun openShell(command: String): AdbShellStream =
        try {
            dadb.openShell(command)
        } catch (e: AdbException) {
            throw mapTransport("openShell: $command", e)
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

    override fun close() {
        state = ConnectionState.DEAD
        val channel = channelHandle
        if (channel != null) {
            channel.shutdown()
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                throw TimeoutException("Couldn't close Maestro Android driver due to gRPC timeout")
            }
        }
        runCatching { dadb.close() }
    }

    // ── failure classification ────────────────────────────────────────────────

    private fun mapTransport(operation: String, cause: Throwable): IOException = when (cause) {
        is AdbAuthException -> DeviceAuthException(serial, cause)
        is StatusRuntimeException -> onGrpcDeath(operation, cause)
        is AdbException -> onAdbDeath(operation, cause)
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
        private const val PROBE_MS = 1000

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

        /**
         * Adopt an already-connected [dadb] instead of opening a fresh one. For callers that own
         * their own dadb (e.g. maestro-worker, with custom socket timeouts) and want the connection
         * to share that single transport rather than open a second adbd socket. [endpoint] is the
         * adbd / adb-server address used by the liveness probe. The returned connection takes
         * ownership: [close] will close [dadb], so the caller must not also close it independently.
         */
        fun wrap(dadb: Dadb, endpoint: Endpoint, driverHostPort: Int): AndroidDeviceConnection =
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
