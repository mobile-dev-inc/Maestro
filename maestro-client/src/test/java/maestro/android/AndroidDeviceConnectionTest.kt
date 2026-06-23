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

import com.google.common.truth.Truth.assertThat
import dadb.AdbAuthException
import dadb.AdbConnectException
import dadb.AdbConnectionClosedException
import dadb.AdbProtocolException
import dadb.AdbShellPacket
import dadb.AdbShellResponse
import dadb.AdbShellStream
import dadb.AdbStream
import dadb.AdbStreamOpenException
import okio.BufferedSink
import okio.BufferedSource
import dadb.AdbTimeoutException
import dadb.Dadb
import dadb.InstallResult as DadbInstallResult
import dadb.SyncResult as DadbSyncResult
import dadb.UninstallResult as DadbUninstallResult
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.mockk.mockk
import maestro.DeviceUnreachableException
import maestro_android.MaestroDriverGrpc
import okio.Buffer
import okio.Sink
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.io.IOException

/**
 * Pins the contract translation that PR #3372 is about: dadb 2.0.0 RETURNS an operation outcome
 * (`*Result` / shell exit code) and only THROWS an `AdbException` for a transport death. The
 * connection must keep those two axes separate — an operation failure becomes a `*Result.Failure`,
 * a transport death throws a typed `Device*Exception`. Conflating them is the misclassification bug.
 */
class AndroidDeviceConnectionTest {

    // ── Fake dadb: each method delegates to a lambda the test sets ───────────────
    private class FakeDadb(
        var onShell: (String) -> AdbShellResponse = { error("shell not stubbed") },
        var onInstall: (File) -> DadbInstallResult = { error("install not stubbed") },
        var onUninstall: (String) -> DadbUninstallResult = { error("uninstall not stubbed") },
        var onPullFile: (File, String) -> DadbSyncResult = { _, _ -> error("pull(File) not stubbed") },
        var onPullSink: (Sink, String) -> DadbSyncResult = { _, _ -> error("pull(Sink) not stubbed") },
        var onPush: (File, String) -> DadbSyncResult = { _, _ -> error("push not stubbed") },
        var onOpen: (String) -> AdbStream = { error("open not stubbed") },
    ) : Dadb {
        override fun open(destination: String): AdbStream = onOpen(destination)
        override fun supportsFeature(feature: String): Boolean = true
        override fun shell(command: String): AdbShellResponse = onShell(command)
        override fun install(file: File, vararg options: String): DadbInstallResult = onInstall(file)
        override fun uninstall(packageName: String): DadbUninstallResult = onUninstall(packageName)
        override fun pull(dst: File, remotePath: String): DadbSyncResult = onPullFile(dst, remotePath)
        override fun pull(sink: Sink, remotePath: String): DadbSyncResult = onPullSink(sink, remotePath)
        override fun push(src: File, remotePath: String, mode: Int, lastModifiedMs: Long): DadbSyncResult =
            onPush(src, remotePath)
        override fun close() {}
        override fun toString() = "fake-serial"
    }

    private fun conn(dadb: Dadb, alive: Boolean = false): AndroidDeviceConnection =
        AndroidDeviceConnection.forTest(dadb = dadb, transportProbe = { alive })

    private fun apk(): File = File.createTempFile("test", ".apk").apply { deleteOnExit() }

    // ── install: operation outcome is returned, transport death throws ──────────

    @Test
    fun `install returns Success when dadb installs successfully`() {
        val c = conn(FakeDadb(onInstall = { DadbInstallResult.Success }))
        assertThat(c.install(apk())).isEqualTo(InstallResult.Success)
        assertThat(c.state).isEqualTo(ConnectionState.CONNECTED)
    }

    @Test
    fun `install returns Failure with reason when dadb rejects the install (NOT Success)`() {
        // The regression guard: a real install rejection must not be reported as Success.
        val c = conn(FakeDadb(onInstall = { DadbInstallResult.Failure("INSTALL_FAILED_INSUFFICIENT_STORAGE") }))
        val result = c.install(apk())
        assertThat(result).isInstanceOf(InstallResult.Failure::class.java)
        assertThat((result as InstallResult.Failure).message).contains("INSUFFICIENT_STORAGE")
        assertThat(c.state).isEqualTo(ConnectionState.CONNECTED) // an operation failure is not a death
    }

    @Test
    fun `install throws DeviceUnreachable on dadb transport death even when adbd is alive (never ServerDied)`() {
        // dadb plane: no on-device server sits in the path, so a transport death is always Unreachable —
        // the adbd liveness probe is irrelevant here and must not promote the death to DeviceServerDied.
        val c = conn(FakeDadb(onInstall = { throw AdbConnectionClosedException("stream died") }), alive = true)
        assertThrows<DeviceUnreachableException> { c.install(apk()) }
        assertThat(c.state).isEqualTo(ConnectionState.DEAD)
    }

    @Test
    fun `install throws DeviceUnreachable when transport dies mid-install and adbd is gone`() {
        val c = conn(FakeDadb(onInstall = { throw AdbConnectionClosedException("stream died") }), alive = false)
        assertThrows<DeviceUnreachableException> { c.install(apk()) }
        assertThat(c.state).isEqualTo(ConnectionState.DEAD)
    }

    @Test
    fun `install throws DeviceAuth on AdbAuthException regardless of probe, connection stays alive`() {
        val c = conn(FakeDadb(onInstall = { throw AdbAuthException("A_AUTH rejected") }), alive = true)
        assertThrows<DeviceAuthException> { c.install(apk()) }
        assertThat(c.state).isEqualTo(ConnectionState.CONNECTED) // auth is CONFIG, not a death
    }

    @Test
    fun `install classifies every transport subtype as DeviceUnreachable on the dadb plane (never ServerDied)`() {
        listOf(
            AdbConnectException("refused"),
            AdbStreamOpenException("tcp:7001", "open refused"),
            AdbConnectionClosedException("eof"),
            AdbTimeoutException("timed out"),
            AdbProtocolException("desync"),
        ).forEach { fault ->
            // alive = true would have meant ServerDied under the old shared probe; on the dadb plane it must not.
            val c = conn(FakeDadb(onInstall = { throw fault }), alive = true)
            assertThrows<DeviceUnreachableException>("subtype ${fault::class.simpleName}") { c.install(apk()) }
        }
    }

    // ── uninstall ───────────────────────────────────────────────────────────────

    @Test
    fun `uninstall returns Success`() {
        val c = conn(FakeDadb(onUninstall = { DadbUninstallResult.Success }))
        assertThat(c.uninstall("com.x")).isEqualTo(UninstallResult.Success)
    }

    @Test
    fun `uninstall returns Failure preserving reason and exit code (NOT Success)`() {
        val c = conn(FakeDadb(onUninstall = { DadbUninstallResult.Failure("DELETE_FAILED_INTERNAL_ERROR", exitCode = 1) }))
        val result = c.uninstall("com.x")
        assertThat(result).isInstanceOf(UninstallResult.Failure::class.java)
        val failure = result as UninstallResult.Failure
        assertThat(failure.message).contains("DELETE_FAILED_INTERNAL_ERROR")
        assertThat(failure.message).contains("exit 1")
    }

    @Test
    fun `uninstall throws DeviceUnreachable on transport death with adbd gone`() {
        val c = conn(FakeDadb(onUninstall = { throw AdbTimeoutException("unresponsive") }), alive = false)
        assertThrows<DeviceUnreachableException> { c.uninstall("com.x") }
    }

    // ── pull (both overloads) ─────────────────────────────────────────────────────

    @Test
    fun `pull(File) returns Failure on sync FAIL (NOT Success)`() {
        val c = conn(FakeDadb(onPullFile = { _, _ -> DadbSyncResult.Failure("remote object does not exist") }))
        val result = c.pull(apk(), "/sdcard/missing")
        assertThat(result).isInstanceOf(SyncResult.Failure::class.java)
        assertThat((result as SyncResult.Failure).message).contains("does not exist")
    }

    @Test
    fun `pull(File) throws DeviceUnreachable on dadb transport death even when adbd is alive`() {
        val c = conn(FakeDadb(onPullFile = { _, _ -> throw AdbConnectionClosedException("eof") }), alive = true)
        assertThrows<DeviceUnreachableException> { c.pull(apk(), "/sdcard/x") }
    }

    @Test
    fun `pull(Sink) returns Success`() {
        val c = conn(FakeDadb(onPullSink = { _, _ -> DadbSyncResult.Success }))
        assertThat(c.pull(Buffer(), "/sdcard/x")).isEqualTo(SyncResult.Success)
    }

    @Test
    fun `pull(Sink) throws DeviceUnreachable on transport death with adbd gone`() {
        val c = conn(FakeDadb(onPullSink = { _, _ -> throw AdbConnectException("refused") }), alive = false)
        assertThrows<DeviceUnreachableException> { c.pull(Buffer(), "/sdcard/x") }
    }

    // ── push (now honors its SyncResult too) ──────────────────────────────────────

    @Test
    fun `push returns Failure on sync FAIL (NOT swallowed)`() {
        val c = conn(FakeDadb(onPush = { _, _ -> DadbSyncResult.Failure("permission denied") }))
        val result = c.push(apk(), "/data/local/tmp/x")
        assertThat(result).isInstanceOf(SyncResult.Failure::class.java)
    }

    @Test
    fun `push throws DeviceUnreachable on dadb transport death even when adbd is alive`() {
        val c = conn(FakeDadb(onPush = { _, _ -> throw AdbTimeoutException("timed out") }), alive = true)
        assertThrows<DeviceUnreachableException> { c.push(apk(), "/data/local/tmp/x") }
    }

    // ── shell: exit code rides home; only transport throws ────────────────────────

    @Test
    fun `shell exit 0 rides home, no throw`() {
        val c = conn(FakeDadb(onShell = { AdbShellResponse(output = "ok\n", errorOutput = "", exitCode = 0) }))
        val response = c.shell("echo ok")
        assertThat(response.exitCode).isEqualTo(0)
        assertThat(response.output).isEqualTo("ok\n")
    }

    @Test
    fun `shell NON-ZERO exit rides home inside the response, NOT thrown`() {
        val c = conn(FakeDadb(onShell = { AdbShellResponse(output = "", errorOutput = "No such file", exitCode = 1) }))
        val response = c.shell("cat /nope")
        assertThat(response.exitCode).isEqualTo(1)
        assertThat(c.state).isEqualTo(ConnectionState.CONNECTED)
    }

    @Test
    fun `shell throws DeviceUnreachable on dadb transport death even when adbd is alive, diagnostics labelled`() {
        val c = conn(FakeDadb(onShell = { throw AdbConnectionClosedException("socket reset") }), alive = true)
        val thrown = assertThrows<DeviceUnreachableException> { c.shell("input tap 1 2") }
        assertThat(thrown.operation).isEqualTo("shell: input tap 1 2")
        assertThat(thrown.diagnostics!!.rootCause).contains("AdbConnectionClosedException")
        assertThat(thrown.diagnostics!!.serial).isEqualTo("test-serial") // the serial forTest assigns
    }

    @Test
    fun `shell throws DeviceAuth on AdbAuthException with a non-keyword message (typed, not string-matched)`() {
        // The M4 guard: the old string-matcher missed an auth message like this one.
        val c = conn(FakeDadb(onShell = { throw AdbAuthException("A_AUTH packet rejected by device") }), alive = true)
        assertThrows<DeviceAuthException> { c.shell("ls") }
    }

    @Test
    fun `uninstall throws DeviceAuth on AdbAuthException`() {
        val c = conn(FakeDadb(onUninstall = { throw AdbAuthException("A_AUTH rejected") }), alive = true)
        assertThrows<DeviceAuthException> { c.uninstall("com.x") }
        assertThat(c.state).isEqualTo(ConnectionState.CONNECTED)
    }

    @Test
    fun `pull(File) throws DeviceAuth on AdbAuthException`() {
        val c = conn(FakeDadb(onPullFile = { _, _ -> throw AdbAuthException("A_AUTH rejected") }), alive = true)
        assertThrows<DeviceAuthException> { c.pull(apk(), "/sdcard/x") }
    }

    // ── ancillary transport ops: open / openShell carry no operation outcome, only deaths ──

    @Test
    fun `open throws DeviceUnreachable on transport death with adbd gone`() {
        val c = conn(FakeDadb(onOpen = { throw AdbConnectException("refused") }), alive = false)
        assertThrows<DeviceUnreachableException> { c.open("tcp:7001") }
        assertThat(c.state).isEqualTo(ConnectionState.DEAD)
    }

    @Test
    fun `openShell throws DeviceUnreachable on dadb transport death even when adbd is alive`() {
        // openShell routes through dadb.open under the hood — still the dadb plane, so a death there is
        // an unreachable transport, never DeviceServerDied.
        val c = conn(FakeDadb(onOpen = { throw AdbConnectionClosedException("eof") }), alive = true)
        assertThrows<DeviceUnreachableException> { c.openShell("ls") }
    }

    // ── device-server lifecycle: instrumentation, driver-reachability probe, detached shell ──

    private fun fakeAdbStream(): AdbStream = object : AdbStream {
        override val source: BufferedSource get() = Buffer()
        override val sink: BufferedSink get() = Buffer()
        override fun close() {}
    }

    @Test
    fun `startInstrumentation maps a transport death to DeviceUnreachable, labelled instrumentation`() {
        // The driver builds the `am instrument` command; the connection owns the stream and the
        // failure classification. A transport death here is the dadb plane → DeviceUnreachable, and
        // the diagnostics operation is the semantic label, not the raw shell command.
        val c = conn(FakeDadb(onOpen = { throw AdbConnectionClosedException("eof") }), alive = true)
        val thrown = assertThrows<DeviceUnreachableException> { c.startInstrumentation("am instrument -w dev.mobile…") }
        assertThat(thrown.operation).isEqualTo("instrumentation")
    }

    @Test
    fun `isDriverReachable returns true when the port accepts a stream, without mutating state`() {
        val c = conn(FakeDadb(onOpen = { fakeAdbStream() }))
        assertThat(c.isDriverReachable(7001)).isTrue()
        assertThat(c.state).isEqualTo(ConnectionState.CONNECTED) // pure probe — not a death
    }

    @Test
    fun `isDriverReachable returns false on a refused port WITHOUT marking the connection dead`() {
        // Regression guard: the startup probe must not flip state to DEAD just because the on-device
        // server has not bound the port yet. (The old connection.open(tcp) probe routed through mapTransport.)
        val c = conn(FakeDadb(onOpen = { throw AdbConnectException("refused") }))
        assertThat(c.isDriverReachable(7001)).isFalse()
        assertThat(c.state).isEqualTo(ConnectionState.CONNECTED)
    }

    @Test
    fun `execDetached maps a transport death to DeviceUnreachable`() {
        val c = conn(FakeDadb(onOpen = { throw AdbConnectionClosedException("eof") }), alive = true)
        assertThrows<DeviceUnreachableException> { c.execDetached("nohup /data/local/tmp/screenrecord out.mp4 &") }
    }

    @Test
    fun `instrumentationStartedCleanly is false for stderr and FAILED or UNABLE, true otherwise`() {
        assertThat(
            AndroidDeviceConnection.instrumentationStartedCleanly(AdbShellPacket.StdOut("INSTRUMENTATION_STATUS: started".toByteArray()))
        ).isTrue()
        assertThat(
            AndroidDeviceConnection.instrumentationStartedCleanly(AdbShellPacket.StdError("boom".toByteArray()))
        ).isFalse()
        assertThat(
            AndroidDeviceConnection.instrumentationStartedCleanly(AdbShellPacket.StdOut("INSTRUMENTATION_FAILED: ...".toByteArray()))
        ).isFalse()
        assertThat(
            AndroidDeviceConnection.instrumentationStartedCleanly(AdbShellPacket.StdOut("UNABLE to find instrumentation".toByteArray()))
        ).isFalse()
    }

    // ── connection lifecycle: close / isShutdown ──

    @Test
    fun `close marks the connection shutdown and dead`() {
        val c = conn(FakeDadb())
        c.close()
        assertThat(c.isShutdown()).isTrue()
        assertThat(c.state).isEqualTo(ConnectionState.DEAD)
    }

    @Test
    fun `isShutdown is false when alive and true after a dadb-plane death (no gRPC channel built)`() {
        val c = conn(FakeDadb(onShell = { throw AdbConnectionClosedException("eof") }), alive = false)
        assertThat(c.isShutdown()).isFalse()
        assertThrows<DeviceUnreachableException> { c.shell("ls") }
        assertThat(c.isShutdown()).isTrue() // state == DEAD even though the gRPC channel was never built
    }

    // ── orThrowOnFailure: operation failure is a RuntimeException, NOT an IOException ──

    @Test
    fun `orThrowOnFailure throws AndroidOperationFailedException and is not an IOException`() {
        val ex = assertThrows<AndroidOperationFailedException> {
            (InstallResult.Failure("rejected") as InstallResult).orThrowOnFailure()
        }
        assertThat(ex).isNotInstanceOf(IOException::class.java)
    }

    @Test
    fun `orThrowOnFailure does nothing on Success`() {
        (InstallResult.Success as InstallResult).orThrowOnFailure()
        (SyncResult.Success as SyncResult).orThrowOnFailure()
        (UninstallResult.Success as UninstallResult).orThrowOnFailure()
    }

    // ── gRPC plane: execute / stream ──────────────────────────────────────────────

    private fun grpcConn(alive: Boolean = false): AndroidDeviceConnection =
        AndroidDeviceConnection.forTest(
            dadb = FakeDadb(),
            transportProbe = { alive },
            blockingStubProvider = { mockk(relaxed = true) },
            asyncStubProvider = { mockk(relaxed = true) },
        )

    @Test
    fun `execute returns Ok with the value when the server answers`() {
        val c = grpcConn()
        val response = c.execute("deviceInfo") { "result" }
        assertThat(response).isEqualTo(DeviceResponse.Ok("result"))
    }

    @Test
    fun `execute models a server-answered failure status as a Failure value, does not throw`() {
        val c = grpcConn()
        val response = c.execute<String>("tap") {
            throw Status.INVALID_ARGUMENT.withDescription("bad coords").asRuntimeException()
        }
        assertThat(response).isInstanceOf(DeviceResponse.Failure::class.java)
        val failure = response as DeviceResponse.Failure
        assertThat(failure.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
        assertThat(failure.description).isEqualTo("bad coords")
    }

    @Test
    fun `execute throws on UNAVAILABLE (transport death), probe picks the type`() {
        assertThrows<DeviceServerDiedException> {
            grpcConn(alive = true).execute<String>("tap") { throw Status.UNAVAILABLE.asRuntimeException() }
        }
        assertThrows<DeviceUnreachableException> {
            grpcConn(alive = false).execute<String>("tap") { throw Status.UNAVAILABLE.asRuntimeException() }
        }
    }

    @Test
    fun `execute throws on DEADLINE_EXCEEDED instead of returning a benign Failure (regression guard)`() {
        // The PR had dropped DEADLINE handling; a hung device server must be a transport death, not a Failure value.
        val thrown = assertThrows<DeviceServerDiedException> {
            grpcConn(alive = true).execute<String>("tap") { throw Status.DEADLINE_EXCEEDED.asRuntimeException() }
        }
        assertThat(thrown.diagnostics.operation).isEqualTo("tap")
    }

    @Test
    fun `stream throws on DEADLINE_EXCEEDED transport death`() {
        assertThrows<DeviceUnreachableException> {
            grpcConn(alive = false).stream<Unit>("addMedia") { throw Status.DEADLINE_EXCEEDED.asRuntimeException() }
        }
    }
}
