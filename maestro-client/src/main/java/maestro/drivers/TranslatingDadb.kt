package maestro.drivers

import dadb.AdbShellResponse
import dadb.AdbShellStream
import dadb.Dadb
import maestro.DeviceUnreachableException
import java.io.File
import java.io.IOException

/**
 * Wraps a [Dadb] so that any [IOException] out of a transport call surfaces as a
 * [DeviceUnreachableException] (infra, retryable) instead of a bare IOException that upstream
 * misclassifies as a customer-facing test failure.
 *
 * dadb signals command failures via a non-zero exitCode, never by throwing — so a thrown
 * IOException always means the channel itself is dead (broken pipe, reset, timeout, EOF, protocol
 * error). IOException is therefore the correct width: total over every transport-death mode without
 * enumerating socket subtypes, and narrow enough to let RuntimeException logic bugs surface.
 *
 * Only the data-plane methods AndroidDriver calls are overridden; everything else delegates raw via
 * `Dadb by delegate`. open() is intentionally NOT translated — it backs the gRPC socket factory,
 * whose failures surface as StatusRuntimeException through gRPC's own error path.
 */
internal class TranslatingDadb(private val delegate: Dadb) : Dadb by delegate {

    override fun shell(command: String): AdbShellResponse =
        runDadbCall("shell: $command") { delegate.shell(command) }

    override fun openShell(command: String): AdbShellStream =
        runDadbCall("openShell: $command") { delegate.openShell(command) }

    override fun pull(dst: File, remotePath: String) =
        runDadbCall("pull: $remotePath") { delegate.pull(dst, remotePath) }

    override fun install(file: File, vararg options: String) =
        runDadbCall("install: ${file.name}") { delegate.install(file, *options) }

    override fun uninstall(packageName: String) =
        runDadbCall("uninstall: $packageName") { delegate.uninstall(packageName) }

    private inline fun <T> runDadbCall(callName: String, block: () -> T): T =
        try {
            block()
        } catch (e: IOException) {
            throw DeviceUnreachableException(callName, e)
        }
}
