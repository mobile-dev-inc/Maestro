package maestro.conformance.device

import java.util.concurrent.TimeUnit

data class CmdResult(val exit: Int, val stdout: String, val stderr: String) {
    val ok get() = exit == 0
}

object Cmd {
    fun run(vararg args: String, timeoutMs: Long = 120_000): CmdResult {
        val p = ProcessBuilder(*args).redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader()
        val err = p.errorStream.bufferedReader()
        val so = StringBuilder(); val se = StringBuilder()
        val tOut = Thread { out.forEachLine { so.appendLine(it) } }.apply { start() }
        val tErr = Thread { err.forEachLine { se.appendLine(it) } }.apply { start() }
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            return CmdResult(124, so.toString(), se.toString() + "\n[timeout]")
        }
        tOut.join(2000); tErr.join(2000)
        return CmdResult(p.exitValue(), so.toString(), se.toString())
    }
}
