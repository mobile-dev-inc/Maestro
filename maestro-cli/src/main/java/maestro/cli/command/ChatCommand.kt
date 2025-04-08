package maestro.cli.command

import maestro.cli.api.ApiClient
import maestro.cli.auth.Auth
import maestro.cli.util.EnvUtils.BASE_API_URL
import org.fusesource.jansi.Ansi.ansi
import picocli.CommandLine
import java.util.*
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "chat",
    description = [
        "Use Maestro GPT to help you with Maestro documentation and code questions"
    ]
)
class ChatCommand : Callable<Int> {

    @CommandLine.Option(order = 0, names = ["--api-key", "--apiKey"], description = ["API key"])
    private var apiKey: String? = null

    @CommandLine.Option(order = 1, names = ["--api-url", "--apiUrl"], description = ["API base URL"])
    private var apiUrl: String = BASE_API_URL

    @CommandLine.Option(
        order = 2,
        names = ["--ask"],
        description = ["Gets a response and immediately exits the chat session"]
    )
    private var ask: String? = null

    private val auth by lazy {
        Auth(ApiClient("$apiUrl/v2"))
    }

    override fun call(): Int {
        if (apiKey == null) {
            apiKey = auth.getCachedAuthToken()
        }

        if (apiKey == null) {
            println("You must log in first in to use this command (maestro login).")
            return 1
        }

        val client = ApiClient(apiUrl)
        if (ask == null) {
            println(
                """
            Welcome to MaestroGPT!

            You can ask questions about Maestro documentation and code.
            To exit, type "quit" or "exit".
            
            """.trimIndent()
            )
        }
        val sessionId = "maestro_cli:" + UUID.randomUUID().toString()

        while (true) {
            if(ask == null) {
                print(ansi().fgBrightMagenta().a("> ").reset().toString())
            }
            val question = ask ?: readLine()

            if (question == null || question == "quit" || question == "exit") {
                println("Goodbye!")
                return 0
            }

            val messages = client.botMessage(question, sessionId, apiKey!!)
            println()
            messages.filter { it.role == "assistant" }.mapNotNull { message ->
                message.content.map { it.text }.joinToString("\n").takeIf { it.isNotBlank() }
            }.forEach { message ->
                if(ask != null) {
                    println(message)
                } else {
                    println(
                        ansi().fgBrightMagenta().a("MaestroGPT> ").reset().fgBrightCyan().a(message).reset().toString()
                    )
                }
                println()
            }

            if (ask != null) {
                return 0
            }
        }
    }

}
