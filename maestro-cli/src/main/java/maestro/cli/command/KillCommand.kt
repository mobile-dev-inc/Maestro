package maestro.cli.command

import maestro.cli.DisableAnsiMixin
import maestro.cli.ShowHelpMixin
import maestro.cli.util.PrintUtils.message
import picocli.CommandLine
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "kill",
    description = ["Kill all running Maestro processes"]
)
class KillCommand : Callable<Int> {

    @CommandLine.Mixin
    var disableANSIMixin: DisableAnsiMixin? = null

    @CommandLine.Mixin
    var showHelpMixin: ShowHelpMixin? = null

    override fun call(): Int {
        killOtherMaestroProcesses()
        return 0
    }

    companion object {
        fun killOtherMaestroProcesses() {
            val myPid = ProcessHandle.current().pid()

            val maestroProcesses = ProcessHandle.allProcesses()
                .filter { it.isAlive }
                .filter { it.pid() != myPid }
                .filter { handle ->
                    val cmdLine = handle.info().commandLine().orElse("")
                    cmdLine.contains("maestro.cli.AppKt")
                }
                .toList()

            if (maestroProcesses.isEmpty()) {
                message("No other Maestro processes found.")
                return
            }

            var killed = 0
            for (handle in maestroProcesses) {
                val pid = handle.pid()
                val destroyed = handle.destroyForcibly()
                if (destroyed) {
                    message("Killed Maestro process (PID $pid)")
                    killed++
                } else {
                    message("Failed to kill process (PID $pid)")
                }
            }

            message("Killed $killed Maestro process(es).")
        }
    }
}
