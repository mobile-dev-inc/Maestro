package maestro.cli.command

import maestro.cli.App
import maestro.cli.Dependencies
import maestro.cli.DisableAnsiMixin
import maestro.cli.ShowHelpMixin
import maestro.cli.mcp.tools.InspectScreenTool
import maestro.cli.mcp.tools.RunTool
import maestro.cli.mcp.tools.TakeScreenshotTool
import maestro.cli.stream.StreamServer
import maestro.cli.util.getFreePort
import picocli.CommandLine
import picocli.CommandLine.Option
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

@CommandLine.Command(
    name = "stream",
    description = ["Stream a device's screen and input to a browser"],
    hidden = true,
)
class StreamCommand : Callable<Int> {

    enum class StreamPlatform(val cliName: String) {
        IOS("ios"),
        ANDROID("android"),
        ANDROID_DEVICE("android_device"),
    }

    @CommandLine.Mixin
    var disableANSIMixin: DisableAnsiMixin? = null

    @CommandLine.Mixin
    var showHelpMixin: ShowHelpMixin? = null

    @CommandLine.ParentCommand
    private val parent: App? = null

    @Option(
        names = ["--platform"],
        required = true,
        description = ["Device platform: ios | android | android_device"],
    )
    lateinit var platform: StreamPlatform

    @Option(
        names = ["--id"],
        required = true,
        description = ["Simulator UDID, emulator id, or device serial"],
    )
    lateinit var deviceId: String

    @Option(
        names = ["--port"],
        description = ["Port to serve the browser UI on (default: random free port)"],
    )
    var port: Int? = null

    override fun call(): Int {
        println("Connecting to ${platform.cliName} device $deviceId")

        Dependencies.installSimulatorServer()

        val verbose = parent?.verbose ?: false
        val binary = Dependencies.simulatorServerBinary()
        runVerify(binary)

        val browserPort = port ?: getFreePort()
        val server = StreamServer.start(
            simulatorServerBinary = binary,
            platform = platform.cliName,
            deviceId = deviceId,
            browserPort = browserPort,
            verbose = verbose,
        )
        Runtime.getRuntime().addShutdownHook(Thread({ server.close() }, "stream-shutdown"))

        val url = "http://localhost:$browserPort"
        println()
        println("Streaming ${platform.cliName} $deviceId at $url")
        println()
        printMcpUsage()
        println()
        println("Ctrl-C to exit.")

        Thread.currentThread().join()
        return 0
    }

    /**
     * Instructions aimed at the MCP agent that invoked `maestro stream`:
     * the output goes straight back to the agent as tool stdout, so phrase
     * it as direct guidance. Tool names come from the Tool object constants
     * (e.g. `RunTool.NAME`) so the text stays honest if tools are renamed.
     */
    private fun printMcpUsage() {
        println("Stream is live.")
        println("Drive this device via the Maestro MCP tools (device id: $deviceId):")
        println()
        println("  • ${RunTool.NAME} — execute a Maestro flow (inline YAML or file path)")
        println("  • ${InspectScreenTool.NAME} — fetch the current UI hierarchy before targeting elements")
        println("  • ${TakeScreenshotTool.NAME} — capture a PNG of the current screen")
        println()
        println("The stream stays up until it is stopped. Don't re-invoke `maestro stream` for this device.")
    }

    /**
     * Runs `simulator-server verify` as a preflight check and logs each
     * `[ok|skip|fail] <category>: <detail>` line. Exit 0 = everything is fine
     * or benignly skipped; exit 1 = a dependency is present but broken.
     * We log the failure but still proceed — the user may be targeting a
     * platform whose check passed even if another one failed.
     */
    private fun runVerify(binary: File) {
        val process = try {
            ProcessBuilder(binary.absolutePath, "verify")
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            System.err.println("$VERIFY_LOG_PREFIX failed to launch: ${e.message}")
            return
        }

        BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
            lines.filter { it.isNotBlank() }.forEach { println("$VERIFY_LOG_PREFIX $it") }
        }

        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            System.err.println("$VERIFY_LOG_PREFIX timed out after 10s")
            return
        }
        val exit = process.exitValue()
        if (exit != 0) {
            System.err.println("$VERIFY_LOG_PREFIX exited with $exit — at least one dependency check failed")
        }
    }

    companion object {
        private const val VERIFY_LOG_PREFIX = "[simulator-server verify]"
    }
}
