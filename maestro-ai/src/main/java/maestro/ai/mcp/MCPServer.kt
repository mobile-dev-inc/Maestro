package maestro.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

class MCPServer {

    suspend fun start() {
        val server = Server(
            serverInfo = Implementation(
                name = "maestro",
                version = "1.0.0"
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    resources = ServerCapabilities.Resources(
                        subscribe = true,
                        listChanged = true
                    )
                )
            )
        )

        // Add a resource
        server.addResource(
            uri = "file:///example.txt",
            name = "Example Resource",
            description = "An example text file",
            mimeType = "text/plain"
        ) { request ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        text = "This is the content of the example resource.",
                        uri = request.uri,
                        mimeType = "text/plain"
                    )
                )
            )
        }

        // Start server with stdio transport
        val transport = StdioServerTransport(
            inputStream = System.`in`.asSource().buffered(),
            outputStream = System.out.asSink().buffered()
        )
        server.connect(transport)
        val done = Job()
        server.onCloseCallback = {
            done.complete()
        }
        done.join()
    }

}

fun main() {
    runBlocking {
        MCPServer().start()
    }
}