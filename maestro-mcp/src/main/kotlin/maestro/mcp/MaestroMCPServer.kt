package maestro.mcp

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.util.collections.*
import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.SSEServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.slf4j.LoggerFactory
import java.io.PrintStream

/**
 * MCP Server implementation for Maestro
 * Uses the official Model Context Protocol SDK
 */
class MaestroMCPServer {
    private val logger = LoggerFactory.getLogger(MaestroMCPServer::class.java)

    fun configureServer(): Server {
        val def = CompletableDeferred<Unit>()

        val server = Server(
            Implementation(
                name = "mcp-kotlin test server",
                version = "0.1.0"
            ),
            ServerOptions(
                capabilities = ServerCapabilities(
                    prompts = ServerCapabilities.Prompts(listChanged = true),
                    resources = ServerCapabilities.Resources(subscribe = true, listChanged = true),
                    tools = ServerCapabilities.Tools(listChanged = true),
                )
            ),
            onCloseCallback = {
                def.complete(Unit)
            }
        )

        server.addPrompt(
            name = "Kotlin Developer",
            description = "Develop small kotlin applications",
            arguments = listOf(
                PromptArgument(
                    name = "Project Name",
                    description = "Project name for the new project",
                    required = true
                )
            )
        ) { request ->
            GetPromptResult(
                "Description for ${request.name}",
                messages = listOf(
                    PromptMessage(
                        role = Role.user,
                        content = TextContent("Develop a kotlin project named <name>${request.arguments?.get("Project Name")}</name>")
                    )
                )
            )
        }

        // Add a tool
        server.addTool(
            name = "kotlin-sdk-tool",
            description = "A test tool",
            inputSchema = Tool.Input()
        ) { request ->
            CallToolResult(
                content = listOf(TextContent("Hello, world!"))
            )
        }

        // Add a resource
        server.addResource(
            uri = "https://search.com/",
            name = "Web Search",
            description = "Web search engine",
            mimeType = "text/html"
        ) { request ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents("Placeholder content for ${request.uri}", request.uri, "text/html")
                )
            )
        }

        return server
    }

    fun runMcpServerUsingStdio() {
        // Note: The server will handle listing prompts, tools, and resources automatically.
        // The handleListResourceTemplates will return empty as defined in the Server code.
        val server = configureServer()
        val transport = StdioServerTransport(
            inputStream = System.`in`.asSource().buffered(),
            outputStream = System.out.asSink().buffered()
        )

        runBlocking {
            server.connect(transport)
            val done = Job()
            server.onCloseCallback = {
                done.complete()
            }
            done.join()
            logger.info("Server closed")
        }
    }

    fun runSseMcpServerWithPlainConfiguration(port: Int): Unit = runBlocking {
        val servers = ConcurrentMap<String, Server>()
        logger.info("Starting sse server on port $port. ")
        logger.info("Use inspector to connect to the http://localhost:$port/sse")

        embeddedServer(CIO, host = "0.0.0.0", port = port) {
            install(SSE)
            routing {
                sse("/sse") {
                    val transport = SSEServerTransport("/message", this)
                    val server = configureServer()

                    // For SSE, you can also add prompts/tools/resources if needed:
                    // server.addTool(...), server.addPrompt(...), server.addResource(...)

                    servers[transport.sessionId] = server

                    server.onCloseCallback = {
                        logger.info("Server closed")
                        servers.remove(transport.sessionId)
                    }

                    server.connect(transport)
                }
                post("/message") {
                    logger.info("Received Message")
                    val sessionId: String = call.request.queryParameters["sessionId"]!!
                    val transport = servers[sessionId]?.transport as? SSEServerTransport
                    if (transport == null) {
                        call.respond(HttpStatusCode.NotFound, "Session not found")
                        return@post
                    }

                    transport.handlePostMessage(call)
                }
            }
        }.start(wait = true)
    }


    fun start(command: String, port: Int) = runBlocking {
        when (command) {
            "--stdio" -> runMcpServerUsingStdio()
            "--sse" -> runSseMcpServerWithPlainConfiguration(port)
            else -> {
                logger.error("Unknown command: $command")
            }
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            // Disable SLF4J warning messages by redirecting System.err temporarily
            val originalErr = System.err
            System.setErr(object : PrintStream(originalErr) {
                override fun println(message: String) {
                    if (!message.startsWith("SLF4J")) {
                        super.println(message)
                    }
                }
            })

            val command = args.firstOrNull() ?: "--sse"
            val port = args.getOrNull(1)?.toIntOrNull() ?: 13379
            MaestroMCPServer().start(command, port)
        }
    }
}
