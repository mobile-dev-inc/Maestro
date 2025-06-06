package maestro.cli.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream

class Transport(
    stdin: InputStream,
    private val stdout: OutputStream,
) {
    private val reader = BufferedReader(InputStreamReader(stdin))
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        classDiscriminator = "method"
    }

    suspend fun read(): Request? {
        return withContext(Dispatchers.IO) {
            try {
                System.err.println("Transport: Waiting for message line...")
                val line = reader.readLine() ?: return@withContext null
                System.err.println("Transport: Read line: $line")
                json.decodeFromString(Request.serializer(), line)
            } catch (e: Exception) {
                System.err.println("Transport: Exception during read/parse: ${e.stackTraceToString()}")
                null
            }
        }
    }

    suspend fun writeInitializeResponse(response: InitializeResponse) {
        withContext(Dispatchers.IO) {
            val jsonMessage = json.encodeToString(response)
            stdout.write((jsonMessage + "\n").toByteArray())
            stdout.flush()
        }
    }

    suspend fun writeToolsListResponse(response: ToolsListResponse) {
        withContext(Dispatchers.IO) {
            val jsonMessage = json.encodeToString(response)
            stdout.write((jsonMessage + "\n").toByteArray())
            stdout.flush()
        }
    }

    suspend fun writeToolCallResponse(response: ToolCallResponse) {
        withContext(Dispatchers.IO) {
            val jsonMessage = json.encodeToString(response)
            stdout.write((jsonMessage + "\n").toByteArray())
            stdout.flush()
        }
    }

    fun close() {
        reader.close()
        stdout.close()
    }
} 