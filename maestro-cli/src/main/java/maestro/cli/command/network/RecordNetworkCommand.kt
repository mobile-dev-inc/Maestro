package maestro.cli.command.network

import com.github.tomakehurst.wiremock.http.Request
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.http.Response
import maestro.cli.session.MaestroSessionManager
import maestro.cli.util.PrintUtils
import maestro.cli.view.red
import maestro.networkproxy.NetworkProxy
import maestro.networkproxy.NetworkProxyUtils.decodedBodyString
import maestro.networkproxy.NetworkProxyUtils.isJsonContent
import maestro.networkproxy.NetworkProxyUtils.toMap
import maestro.networkproxy.yaml.YamlMappingRule
import maestro.networkproxy.yaml.YamlMappingRuleParser
import maestro.networkproxy.yaml.YamlResponse
import org.fusesource.jansi.Ansi
import org.fusesource.jansi.Ansi.ansi
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.File
import java.net.URL
import java.util.concurrent.Callable

@Command(
    name = "record",
)
class RecordNetworkCommand : Callable<Int> {

    @CommandLine.ParentCommand
    lateinit var parent: NetworkCommand

    @Parameters
    lateinit var output: File

    @Option(
        names = ["--recordHeaders"]
    )
    var recordHeaders: Boolean = false

    override fun call(): Int {
        println()
        println("\uD83D\uDEA7 THIS COMMAND IS A WIP \uD83D\uDEA7".red())
        println()

        val proxy = NetworkProxy(port = 8080)
        proxy.start(emptyList())
        proxy.setListener { request, response ->
            if (request.method.value().isEmpty()) {
                return@setListener
            }

            handleRequest(request, response)
        }

        MaestroSessionManager.newSession(parent.app.host, parent.app.port, parent.app.deviceId) { session ->
            session.maestro.setProxy(port = 8080)

            PrintUtils.message("Recording network traffic to ${output.absolutePath}")

            while (!Thread.interrupted()) {
                Thread.sleep(100)
            }
        }

        return 0
    }

    private fun handleRequest(request: Request, response: Response) {
        val url = URL(request.absoluteUrl)

        val outputDirectory = File(
            output,
            "${url.authority}/${url.path}"
        )
        outputDirectory.mkdirs()

        val outputFile = File(outputDirectory, "${request.method.value()}.yaml")

        val isJsonContent = response.isJsonContent()

        val bodyFile = File(
            outputDirectory, generateBodyFileName(
                directory = outputDirectory,
                method = request.method.value(),
                isJson = isJsonContent,
            )
        )

        printRequest(request, response, url)

        bodyFile.writeText(
            response.decodedBodyString()
        )

        val mappingRule = YamlMappingRule(
            path = request.absoluteUrl,
            method = request.method.value(),
            headers = if (recordHeaders) {
                request.headers.toMap()
                    .filter {
                        it.key.lowercase() !in EXCLUDED_HEADERS
                    }
            } else null,
            response = YamlResponse(
                status = response.status,
                headers = response.headers.toMap()
                    .filter {
                        it.key.lowercase() !in EXCLUDED_RESPONSE_HEADERS
                    },
                bodyFile = bodyFile.name,
            )
        )

        val existingRules = if (outputFile.exists()) {
            YamlMappingRuleParser.readRules(outputFile.toPath())
        } else {
            emptyList()
        }

        YamlMappingRuleParser.saveRules(
            (existingRules + mappingRule).distinct(),
            outputFile.toPath()
        )
    }

    private fun printRequest(request: Request, response: Response, url: URL) {
        print(
            ansi()
                .fg(methodColor(request.method))
                .render(request.method.value())
                .fgDefault()
                .render(" (${response.status})")
                .render(" $url")
        )
        println()
    }

    private fun generateBodyFileName(
        directory: File,
        method: String,
        isJson: Boolean
    ): String {
        val prefix = "${method}_body"

        val prefixCount = directory
            .listFiles { _, name -> name.startsWith(prefix) }
            ?.count() ?: 0

        return "${prefix}_${prefixCount}.${if (isJson) "json" else "txt"}"
    }

    private fun methodColor(method: RequestMethod): Ansi.Color {
        return when (method.value()) {
            "GET" -> Ansi.Color.MAGENTA
            "POST" -> Ansi.Color.GREEN
            "PUT" -> Ansi.Color.CYAN
            "DELETE" -> Ansi.Color.YELLOW
            else -> Ansi.Color.DEFAULT
        }
    }

    companion object {

        private val EXCLUDED_HEADERS = setOf(
            "date",
            "last-modified",
        )

        private val EXCLUDED_RESPONSE_HEADERS = EXCLUDED_HEADERS + setOf(
            "content-encoding",
            "content-length",
        )

    }

}
