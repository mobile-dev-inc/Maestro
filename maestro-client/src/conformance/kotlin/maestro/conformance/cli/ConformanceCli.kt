package maestro.conformance.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option

class ConformanceCli : CliktCommand(name = "driver-conformance") {
    val api: String by option("--api", help = "API levels: list or range, e.g. 34 or 24..36").default("34")
    val framework: String by option("--framework", help = "Fixtures, e.g. native or all").default("native")
    val command: String? by option("--command", help = "Subset of commands; default all Tier A")
    val device: String? by option("--device", help = "BYO adb serial; skips provisioning")
    val record: String by option("--record", help = "all|on-failure|never").default("on-failure")
    val out: String by option("--out", help = "Report output dir").default("./report")

    override fun run() {
        echo("driver-conformance: api=$api framework=$framework record=$record out=$out")
    }
}

fun main(args: Array<String>) = ConformanceCli().main(args)
