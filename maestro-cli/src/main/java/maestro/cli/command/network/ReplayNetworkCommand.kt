package maestro.cli.command.network

import maestro.cli.util.MaestroFactory
import maestro.cli.util.PrintUtils
import maestro.networkproxy.NetworkProxy
import maestro.networkproxy.yaml.YamlMappingRuleParser
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.io.File
import java.util.concurrent.Callable
import kotlin.concurrent.thread

@Command(
    name = "replay",
)
class ReplayNetworkCommand : Callable<Int> {

    @ParentCommand
    lateinit var parent: NetworkCommand

    @Parameters
    lateinit var rules: File

    @Option(
        names = ["--verbose"],
    )
    var verbose: Boolean = false

    override fun call(): Int {
        val proxy = NetworkProxy(port = 8080)
        if (verbose) {
            proxy.setRuleListener { matchResult ->
                when (matchResult.result) {
                    NetworkProxy.MatchResult.MatchType.EXACT_MATCH -> {
                        PrintUtils.success("${matchResult.url} - Match")
                    }
                    NetworkProxy.MatchResult.MatchType.NO_MATCH -> {
                        PrintUtils.err("${matchResult.url} - No Match")
                    }
                }
            }
        }
        proxy.start(
            YamlMappingRuleParser.readRules(rules.absoluteFile.toPath())
        )

        val (maestro, _) = MaestroFactory.createMaestro(parent.app.host, parent.app.port, parent.app.deviceId)

        Runtime.getRuntime().addShutdownHook(thread(start = false) {
            maestro.close()
        })

        maestro.use {
            maestro.setProxy(port = 8080)

            PrintUtils.message("Replaying network traffic from ${rules.absolutePath}")

            while (!Thread.interrupted()) {
                Thread.sleep(100)
            }
        }

        return 0
    }

}