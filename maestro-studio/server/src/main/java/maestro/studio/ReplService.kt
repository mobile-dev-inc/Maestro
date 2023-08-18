package maestro.studio

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.*
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.callbackFlow
import maestro.Maestro
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.yaml.YamlCommandReader
import maestro.orchestra.yaml.YamlFluentCommand
import java.nio.file.Paths
import java.util.UUID
import kotlin.concurrent.thread

private data class RunCommandRequest(
    val ids: List<UUID>?,
    val yaml: String?,
)

private data class DeleteCommandsRequest(
    val ids: List<UUID>,
)

private data class ReorderCommandsRequest(
    val ids: List<UUID>,
)

private data class FormatCommandsRequest(
    val ids: List<UUID>,
)

private data class ReplEntry(
    val id: UUID,
    val yaml: String,
    val commands: List<MaestroCommand>,
    var status: ReplCommandStatus,
)

private data class FormattedFlow(
    val config: String,
    val commands: String,
)

private class ReplState {

    private val entries = mutableListOf<ReplEntry>()
    private val listeners = mutableSetOf<(Repl) -> Any>()

    @Synchronized
    fun toModel(): Repl {
        val commands = entries.map { entry ->
            ReplCommand(entry.id, entry.yaml, entry.status)
        }
        return Repl(commands)
    }

    @Synchronized
    fun getEntriesById(ids: List<UUID>): List<ReplEntry> {
        return entries.filter { it.id in ids }
    }

    @Synchronized
    fun addEntries(newEntries: List<ReplEntry>) {
        entries.addAll(newEntries)
        notifyChange()
    }

    @Synchronized
    fun deleteEntries(ids: List<UUID>) {
        entries.removeIf { it.id in ids }
        notifyChange()
    }

    @Synchronized
    fun reorderEntries(ids: List<UUID>) {
        entries.sortWith { a, b ->
            val aIdx = ids.indexOfFirst { it == a.id }.takeIf { it != -1 } ?: Int.MAX_VALUE
            val bIdx = ids.indexOfFirst { it == b.id }.takeIf { it != -1 } ?: Int.MAX_VALUE
            aIdx - bIdx
        }
        notifyChange()
    }

    @Synchronized
    fun setEntryStatus(id: UUID, status: ReplCommandStatus) {
        entries.filter { it.id == id }.forEach { it.status = status }
        notifyChange()
    }

    fun startListening(listener: (Repl) -> Unit) {
        synchronized(listeners) {
            val repl = toModel()
            listeners.add(listener)
            listener(repl)
        }
    }

    fun stopListening(listener: (Repl) -> Unit) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    private fun notifyChange() {
        synchronized(listeners) {
            val repl = toModel()
            listeners.forEach { it(repl) }
        }
    }
}

object ReplService {

    private val executionLock = Object()
    private val state = ReplState()

    fun routes(routing: Routing, maestro: Maestro) {
        routing.get("/api/repl") {
            call.respondRepl()
        }
        // Ktor SSE sample project: https://github.com/ktorio/ktor-samples/blob/main/sse/src/main/kotlin/io/ktor/samples/sse/SseApplication.kt
        routing.get("/api/repl/sse") {
            call.response.cacheControl(CacheControl.NoCache(null))
            call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                val replFlow = callbackFlow {
                    val listener: (Repl) -> Unit = { trySendBlocking(it) }
                    state.startListening(listener)
                    awaitClose {
                        state.stopListening(listener)
                    }
                }
                replFlow.collect { repl ->
                    val response = jacksonObjectMapper().writeValueAsString(repl)
                    writeStringUtf8("data: $response\n")
                    writeStringUtf8("\n")
                    flush()
                }
            }
        }
        routing.post("/api/repl/command") {
            val request = call.parseBody<RunCommandRequest>()
            when {
                request.ids != null -> {
                    thread {
                        runEntries(maestro, request.ids)
                    }
                }
                request.yaml != null -> {
                    val newEntries = createEntries(request.yaml)
                    thread {
                        runEntries(maestro, newEntries.map { it.id })
                    }
                }
                else -> {
                    call.respond(HttpStatusCode.BadRequest, "Must specify key \"ids\" or \"yaml\"")
                }
            }
            call.respondRepl()
        }
        routing.delete("/api/repl/command") {
            val request = call.parseBody<DeleteCommandsRequest>()
            state.deleteEntries(request.ids)
            call.respondRepl()
        }
        routing.post("/api/repl/command/reorder") {
            val request = call.parseBody<ReorderCommandsRequest>()
            state.reorderEntries(request.ids)
            call.respondRepl()
        }
        routing.post("/api/repl/command/format") {
            val request = call.parseBody<FormatCommandsRequest>()
            val entries = state.getEntriesById(request.ids)
            val inferredAppId = entries.firstNotNullOfOrNull { e -> e.commands.firstNotNullOfOrNull { c -> c.launchAppCommand?.appId } } ?: "<your-app-id>"
            val commandsString = entries.joinToString("") { if (it.yaml.endsWith("\n")) it.yaml else "${it.yaml}\n" }
            val formattedFlow = FormattedFlow("appId: $inferredAppId", commandsString)
            val response = jacksonObjectMapper().writeValueAsString(formattedFlow)
            call.respondText(response)
        }
    }

    private suspend fun ApplicationCall.respondRepl() {
        val repl = state.toModel()
        val response = jacksonObjectMapper().writeValueAsString(repl)
        respondText(response)
    }

    private fun createEntries(yaml: String): List<ReplEntry> {
        val newEntries = try {
            readNodes(yaml).map { node ->
                val yamlCommand = YamlCommandReader.MAPPER.convertValue(node, YamlFluentCommand::class.java)
                val yamlString = YamlCommandReader.MAPPER.writeValueAsString(listOf(node))
                val commands = yamlCommand.toCommands(Paths.get(""), "")
                ReplEntry(UUID.randomUUID(), yamlString, commands, ReplCommandStatus.PENDING)
            }
        } catch (e: Exception) {
            throw HttpException(HttpStatusCode.BadRequest, "Failed to parse yaml: ${e.message}")
        }
        state.addEntries(newEntries)
        return newEntries
    }

    private fun runEntries(maestro: Maestro, ids: List<UUID>) {
        synchronized(executionLock) {
            val entriesToRun = state.getEntriesById(ids)
            try {
                entriesToRun.forEach { state.setEntryStatus(it.id, ReplCommandStatus.PENDING) }
                for (entry in entriesToRun) {
                    state.setEntryStatus(entry.id, ReplCommandStatus.RUNNING)
                    val failure = executeCommands(maestro, entry.commands)
                    if (failure == null) {
                        state.setEntryStatus(entry.id, ReplCommandStatus.SUCCESS)
                    } else {
                        state.setEntryStatus(entry.id, ReplCommandStatus.ERROR)
                        return
                    }
                }
            } finally {
                entriesToRun.filter { it.status == ReplCommandStatus.PENDING }.forEach {
                    state.setEntryStatus(it.id, ReplCommandStatus.CANCELED)
                }
            }
        }
    }

    private fun executeCommands(maestro: Maestro, commands: List<MaestroCommand>): Throwable? {
        var failure: Throwable? = null
        val result = Orchestra(maestro, onCommandFailed = { _, _, throwable ->
            failure = throwable
            Orchestra.ErrorResolution.FAIL
        }).executeCommands(commands)
        return if (result) null else (failure ?: RuntimeException("Command execution failed"))
    }

    private fun readNodes(yaml: String): List<JsonNode> {
        return when (val tree = YamlCommandReader.MAPPER.readTree(yaml)) {
            is ArrayNode -> tree.toList()
            else -> listOf(tree)
        }
    }

}