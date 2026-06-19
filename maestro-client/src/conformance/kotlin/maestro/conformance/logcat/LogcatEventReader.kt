package maestro.conformance.logcat

import com.fasterxml.jackson.databind.ObjectMapper
import maestro.conformance.device.Cmd

class LogcatEventReader {
    private val mapper = ObjectMapper()
    private val seen = HashSet<Pair<String, Int>>()
    private val events = ArrayList<FixtureEvent>()
    private var tailProc: Process? = null
    private var tailThread: Thread? = null

    /** Parse a single logcat line. The fixture writes: `MAESTRO_FIXTURE: {json}`. */
    @Synchronized
    fun ingest(rawLine: String) {
        val marker = "MAESTRO_FIXTURE: "
        val idx = rawLine.indexOf(marker)
        if (idx < 0) return
        val json = rawLine.substring(idx + marker.length).trim()
        if (!json.startsWith("{")) return
        val node = try { mapper.readTree(json) } catch (e: Exception) { return }
        val epoch = node.get("epoch")?.asText() ?: return
        val seq = node.get("seq")?.takeIf { it.isInt }?.asInt() ?: return
        val type = node.get("event")?.asText() ?: return
        val key = epoch to seq
        if (!seen.add(key)) return
        val payload: Map<String, Any?> =
            mapper.convertValue(node, Map::class.java) as Map<String, Any?>
        events += FixtureEvent(epoch, seq, type, payload)
    }

    @Synchronized
    fun latestWatermark(): Watermark? =
        events.maxByOrNull { it.seq }?.let { Watermark(it.epoch, it.seq) }

    @Synchronized
    fun eventsAfter(w: Watermark, type: String): List<FixtureEvent> =
        events.filter { it.epoch == w.epoch && it.seq > w.seq && it.type == type }
            .sortedBy { it.seq }

    fun startTailing(serial: String) {
        // Clear backlog so we only see events from this run.
        Cmd.run("adb", "-s", serial, "logcat", "-c")
        val p = ProcessBuilder("adb", "-s", serial, "logcat", "-v", "threadtime", "-s", "MAESTRO_FIXTURE")
            .redirectErrorStream(true).start()
        tailProc = p
        tailThread = Thread { p.inputStream.bufferedReader().forEachLine { ingest(it) } }.apply {
            isDaemon = true; start()
        }
    }

    fun close() {
        tailProc?.destroyForcibly()
        tailThread?.join(2000)
    }
}
