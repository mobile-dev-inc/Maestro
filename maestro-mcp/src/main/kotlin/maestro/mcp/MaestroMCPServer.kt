package maestro.mcp

import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.Role
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.Tool
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


    fun start() = runBlocking {
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

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            MaestroMCPServer().start()
            System.exit(0)
        }
    }
}
