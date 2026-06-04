package maestro.drivers

import dadb.AdbShellResponse
import dadb.AdbShellStream
import dadb.AdbStream
import dadb.Dadb
import maestro.DeviceUnreachableException
import okio.Sink
import java.io.File
import java.io.IOException

/**
 * A plain wrapper around one open [Dadb] that exposes ONLY the per-connection operations the device
 * layer uses. It deliberately does NOT implement [Dadb]: that keeps the transport type invisible to
 * consumers, so nothing holding a connection can name [Dadb] through it.
 *
 * Every data-plane call routes through [runDadbCall] so that any [IOException] out of a transport
 * call surfaces as a [DeviceUnreachableException] (infra, retryable) instead of a bare IOException
 * that upstream misclassifies as a customer-facing test failure.
 *
 * dadb signals command failures via a non-zero exitCode, never by throwing — so a thrown
 * IOException always means the channel itself is dead (broken pipe, reset, timeout, EOF, protocol
 * error). IOException is therefore the correct width: total over every transport-death mode without
 * enumerating socket subtypes, and narrow enough to let RuntimeException logic bugs surface.
 *
 * [open] and [close] are the only methods NOT translated. [open] hands a raw transport stream to
 * callers that own their own error handling — the gRPC socket factory (failures surface as
 * StatusRuntimeException via gRPC), the Chrome DevTools client, and the app-files run-as pull;
 * [close] is connection lifecycle teardown, not a data-plane op.
 */
internal class DadbConnection(private val dadb: Dadb) {

    fun shell(command: String): AdbShellResponse =
        runDadbCall("shell: $command") { dadb.shell(command) }

    fun openShell(command: String = ""): AdbShellStream =
        runDadbCall("openShell: $command") { dadb.openShell(command) }

    fun pull(dst: File, remotePath: String) =
        runDadbCall("pull: $remotePath") { dadb.pull(dst, remotePath) }

    fun pull(sink: Sink, remotePath: String) =
        runDadbCall("pull: $remotePath") { dadb.pull(sink, remotePath) }

    fun push(src: File, remotePath: String) =
        runDadbCall("push: $remotePath") { dadb.push(src, remotePath) }

    fun install(file: File, vararg options: String) =
        runDadbCall("install: ${file.name}") { dadb.install(file, *options) }

    fun uninstall(packageName: String) =
        runDadbCall("uninstall: $packageName") { dadb.uninstall(packageName) }

    fun open(destination: String): AdbStream = dadb.open(destination)

    // Lifecycle teardown — not a data-plane op, so not translated.
    fun close() = dadb.close()

    private inline fun <T> runDadbCall(callName: String, block: () -> T): T =
        try {
            block()
        } catch (e: IOException) {
            throw DeviceUnreachableException(callName, e)
        }
}
