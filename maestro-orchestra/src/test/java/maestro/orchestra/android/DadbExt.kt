package maestro.orchestra.android

import dadb.AdbShellResponse
import dadb.Dadb
import java.io.IOException

fun Dadb.fileExists(filePath: String): Boolean {
    return tryShell("[ -f $filePath ] && echo 1 || echo 0").contains("1")
}

private fun Dadb.tryShell(command: String): String {
    val response: AdbShellResponse = try {
        shell(command)
    } catch (e: IOException) {
        throw ShellException(e.message, e)
    }
    if (response.exitCode != 0) {
        throw ShellException(response.allOutput)
    }
    return response.output
}

class ShellException: Throwable {
    internal constructor(message: String?) : super(message)
    internal constructor(message: String?, e: Exception?) : super(message, e)
}