package maestro.cli.report

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import maestro.DeviceInfo
import maestro.Maestro
import maestro.TreeNode
import maestro.ViewHierarchy
import maestro.cli.runner.EventStreamListener
import maestro.device.Platform
import maestro.orchestra.EvalScriptCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.RunFlowCommand
import maestro.orchestra.debug.CommandOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.io.Writer
import java.nio.file.Path

class EventStreamTest {

    @TempDir
    lateinit var tempDir: Path

    private val mapper = jacksonObjectMapper()

    private fun readEvents(file: File): List<JsonNode> = file.readLines().map { mapper.readTree(it) }

    private val evalCommand = MaestroCommand(evalScriptCommand = EvalScriptCommand("1"))

    @Test
    fun `listener writes flow and command lines with the expected keys`() {
        val file = tempDir.resolve("events.ndjson").toFile()

        EventStream(file).use { stream ->
            val listener = EventStreamListener(stream, flow = "login", shardIndex = 1, deviceId = "emulator-5554")
            listener.onFlowStart()
            listener.onCommandStart(evalCommand, sequenceNumber = 0, depth = 0)
            listener.onCommandFinished(evalCommand, CommandOutcome.Completed, startedAt = 100, finishedAt = 200)
        }

        val events = readEvents(file)
        assertThat(events).hasSize(3)

        assertThat(events[0].fieldNames().asSequence().toList())
            .containsExactly("type", "timestamp", "flow", "shardIndex", "deviceId").inOrder()
        assertThat(events[0]["type"].asText()).isEqualTo("flow.started")
        assertThat(events[0]["flow"].asText()).isEqualTo("login")
        assertThat(events[0]["shardIndex"].asInt()).isEqualTo(1)
        assertThat(events[0]["deviceId"].asText()).isEqualTo("emulator-5554")
        assertThat(events[0]["timestamp"].asLong()).isGreaterThan(0L)

        assertThat(events[1].fieldNames().asSequence().toList()).containsExactly(
            "type", "timestamp", "flow", "shardIndex", "deviceId", "sequenceNumber", "depth", "command",
        ).inOrder()
        assertThat(events[1]["type"].asText()).isEqualTo("command.started")
        assertThat(events[1]["sequenceNumber"].asInt()).isEqualTo(0)
        assertThat(events[1]["depth"].asInt()).isEqualTo(0)
        assertThat(events[1]["command"].asText()).isEqualTo(evalCommand.description())

        assertThat(events[2].fieldNames().asSequence().toList()).containsExactly(
            "type", "timestamp", "flow", "shardIndex", "deviceId", "sequenceNumber", "depth", "command",
            "status", "duration",
        ).inOrder()
        assertThat(events[2]["type"].asText()).isEqualTo("command.finished")
        assertThat(events[2]["status"].asText()).isEqualTo("COMPLETED")
        assertThat(events[2]["sequenceNumber"].asInt()).isEqualTo(0)
        assertThat(events[2]["duration"].asLong()).isEqualTo(100L)
    }

    @Test
    fun `failed command carries its error and completed command does not`() {
        val file = tempDir.resolve("events.ndjson").toFile()
        val failed = MaestroCommand(evalScriptCommand = EvalScriptCommand("2"))

        EventStream(file).use { stream ->
            val listener = EventStreamListener(stream, flow = "login", shardIndex = null, deviceId = null)
            listener.onCommandStart(evalCommand, sequenceNumber = 0, depth = 0)
            listener.onCommandFinished(evalCommand, CommandOutcome.Completed, startedAt = 0, finishedAt = 1)
            listener.onCommandStart(failed, sequenceNumber = 1, depth = 0)
            listener.onCommandFinished(failed, CommandOutcome.Failed(RuntimeException("boom")), startedAt = 1, finishedAt = 2)
        }

        val events = readEvents(file)
        assertThat(events[1].has("error")).isFalse()
        assertThat(events[2]["status"].asText()).isEqualTo("FAILED")
        assertThat(events[2]["error"].asText()).isEqualTo("boom")
        events.forEach { event ->
            assertThat(event.has("shardIndex")).isFalse()
            assertThat(event.has("deviceId")).isFalse()
        }
    }

    @Test
    fun `finished events carry the sequence and depth of their own start`() {
        val file = tempDir.resolve("events.ndjson").toFile()
        val outer = MaestroCommand(evalScriptCommand = EvalScriptCommand("outer"))
        val inner = MaestroCommand(evalScriptCommand = EvalScriptCommand("inner"))

        EventStream(file).use { stream ->
            val listener = EventStreamListener(stream, flow = "nested", shardIndex = null, deviceId = null)
            listener.onCommandStart(outer, sequenceNumber = 0, depth = 0)
            listener.onCommandStart(inner, sequenceNumber = 1, depth = 1)
            listener.onCommandFinished(inner, CommandOutcome.Completed, startedAt = 0, finishedAt = 1)
            listener.onCommandFinished(outer, CommandOutcome.Completed, startedAt = 0, finishedAt = 2)
        }

        val finished = readEvents(file).filter { it["type"].asText() == "command.finished" }
        assertThat(finished.map { it["sequenceNumber"].asInt() }).containsExactly(1, 0).inOrder()
        assertThat(finished.map { it["depth"].asInt() }).containsExactly(1, 0).inOrder()
    }

    @Test
    fun `flowFinished reports success without an error`() {
        val file = tempDir.resolve("events.ndjson").toFile()

        EventStream(file).use { stream ->
            EventStreamListener(stream, flow = "login", shardIndex = null, deviceId = null)
                .flowFinished(success = true, duration = 1234, error = null)
        }

        val event = readEvents(file).single()
        assertThat(event.fieldNames().asSequence().toList()).containsExactly(
            "type", "timestamp", "flow", "status", "duration",
        ).inOrder()
        assertThat(event["type"].asText()).isEqualTo("flow.finished")
        assertThat(event["status"].asText()).isEqualTo("SUCCESS")
        assertThat(event["duration"].asLong()).isEqualTo(1234L)
    }

    @Test
    fun `flowFinished reports an error with its message`() {
        val file = tempDir.resolve("events.ndjson").toFile()

        EventStream(file).use { stream ->
            EventStreamListener(stream, flow = "login", shardIndex = null, deviceId = null)
                .flowFinished(success = false, duration = 99, error = "Assertion failed")
        }

        val event = readEvents(file).single()
        assertThat(event["status"].asText()).isEqualTo("ERROR")
        assertThat(event["error"].asText()).isEqualTo("Assertion failed")
    }

    @Test
    fun `concurrent emits from shards produce whole lines`() {
        val file = tempDir.resolve("events.ndjson").toFile()

        EventStream(file).use { stream ->
            val threads = (1..8).map {
                Thread {
                    repeat(500) {
                        stream.emit(linkedMapOf("type" to "flow.started", "flow" to "concurrent"))
                    }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
        }

        val lines = file.readLines()
        assertThat(lines).hasSize(4000)
        lines.forEach { mapper.readTree(it) }
    }

    @Test
    fun `emit after close is a no-op`() {
        val file = tempDir.resolve("events.ndjson").toFile()
        val stream = EventStream(file)
        stream.close()
        stream.close()

        stream.emit(linkedMapOf("type" to "flow.started"))

        assertThat(file.readLines()).isEmpty()
    }

    @Test
    fun `a write error is logged once and later events are dropped`() {
        var writes = 0
        val failing = object : Writer() {
            override fun write(cbuf: CharArray, off: Int, len: Int) {
                writes++
                throw IOException("No space left on device")
            }

            override fun flush() {}

            override fun close() {}
        }

        EventStream(failing).use { stream ->
            repeat(3) { stream.emit(linkedMapOf("type" to "flow.started")) }
        }

        assertThat(writes).isEqualTo(1)
    }

    @Test
    fun `flow run through Orchestra emits nested command events in order`() {
        val file = tempDir.resolve("events.ndjson").toFile()
        val inner = MaestroCommand(evalScriptCommand = EvalScriptCommand("2"))
        val outer = MaestroCommand(runFlowCommand = RunFlowCommand(commands = listOf(inner), config = null))
        val maestro: Maestro = mockk(relaxed = true) {
            coEvery { viewHierarchy(any()) } returns ViewHierarchy(TreeNode(attributes = mutableMapOf()))
            coEvery { cachedDeviceInfo } returns DeviceInfo(
                platform = Platform.ANDROID,
                widthPixels = 100,
                heightPixels = 200,
                widthGrid = 100,
                heightGrid = 200,
            )
        }

        EventStream(file).use { stream ->
            val listener = EventStreamListener(stream, flow = "nested", shardIndex = null, deviceId = "emulator-5554")
            val result = runBlocking {
                Orchestra(
                    maestro = maestro,
                    listeners = listOf(listener),
                    onCommandFailed = { _, _, _ -> Orchestra.ErrorResolution.FAIL },
                ).runFlow(listOf(outer))
            }
            listener.flowFinished(success = result.success, duration = 1, error = null)
        }

        val events = readEvents(file)
        assertThat(events.map { it["type"].asText() }).containsExactly(
            "flow.started",
            "command.started",
            "command.started",
            "command.finished",
            "command.finished",
            "flow.finished",
        ).inOrder()
        assertThat(events[1]["depth"].asInt()).isEqualTo(0)
        assertThat(events[2]["depth"].asInt()).isEqualTo(1)
        assertThat(events[2]["command"].asText()).isEqualTo(inner.description())
        assertThat(events[3]["depth"].asInt()).isEqualTo(1)
        assertThat(events[3].has("duration")).isTrue()
        assertThat(events[4]["depth"].asInt()).isEqualTo(0)
        assertThat(events[4]["status"].asText()).isEqualTo("COMPLETED")
        assertThat(events[5]["status"].asText()).isEqualTo("SUCCESS")
    }
}
