package maestro.cli.report

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.Writer

/**
 * Live stream of run events for `maestro test --events`: one JSON object per line, flushed as each is written.
 *
 * Every line has `type`, `timestamp` (epoch millis when written) and `flow`, plus `shardIndex` (as in
 * `MAESTRO_SHARD_INDEX`, 0-based) and `deviceId` when known. Types:
 * - `flow.started`
 * - `command.started`: `sequenceNumber`, `depth`, `command`
 * - `command.finished`: `sequenceNumber`, `depth`, `command`, `status` (a [maestro.orchestra.debug.CommandStatus]),
 *   `duration`, and `error` if it failed
 * - `flow.finished`: `status` (`SUCCESS` or `ERROR`), `duration`, and `error` if it failed
 *
 * Shards share one stream, so [emit] is synchronized. A reporting failure must not fail the run,
 * so after the first write error the stream goes quiet instead of throwing.
 */
class EventStream internal constructor(private var writer: Writer?) : Closeable {

    constructor(file: File) : this(file.also { it.absoluteFile.parentFile?.mkdirs() }.bufferedWriter())

    private val logger = LoggerFactory.getLogger(EventStream::class.java)
    private val mapper = jacksonObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)

    @Synchronized
    fun emit(event: Map<String, Any?>) {
        val out = writer ?: return
        try {
            out.write(mapper.writeValueAsString(event))
            out.write("\n")
            out.flush()
        } catch (e: IOException) {
            logger.warn("Failed to write to the events file, no further events will be written", e)
            writer = null
        }
    }

    @Synchronized
    override fun close() {
        try {
            writer?.close()
        } catch (e: IOException) {
            logger.warn("Failed to close the events file", e)
        } finally {
            writer = null
        }
    }
}
