package maestro.conformance.logcat

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class LogcatEventReaderTest {
    // A typical logcat line under `-s MAESTRO_FIXTURE` with threadtime format:
    private fun line(json: String) =
        "06-19 10:00:00.123  1234  1234 D MAESTRO_FIXTURE: $json"

    @Test fun `parses epoch seq type and payload`() {
        val r = LogcatEventReader()
        r.ingest(line("""{"epoch":"a1","seq":5,"event":"TAP","target":"tap_target","x":10,"y":20}"""))
        val w = r.latestWatermark()!!
        assertThat(w.epoch).isEqualTo("a1")
        assertThat(w.seq).isEqualTo(5)
        val ev = r.eventsAfter(Watermark("a1", 4), "TAP")
        assertThat(ev).hasSize(1)
        assertThat(ev[0].payload["target"]).isEqualTo("tap_target")
    }

    @Test fun `dedupes by epoch and seq`() {
        val r = LogcatEventReader()
        val l = line("""{"epoch":"a1","seq":5,"event":"TAP"}""")
        r.ingest(l); r.ingest(l)
        assertThat(r.eventsAfter(Watermark("a1", 4), "TAP")).hasSize(1)
    }

    @Test fun `eventsAfter ignores other epoch and lower seq and other type`() {
        val r = LogcatEventReader()
        r.ingest(line("""{"epoch":"a1","seq":4,"event":"TAP"}"""))   // below watermark
        r.ingest(line("""{"epoch":"b2","seq":9,"event":"TAP"}"""))   // other epoch
        r.ingest(line("""{"epoch":"a1","seq":6,"event":"SWIPE"}""")) // other type
        r.ingest(line("""{"epoch":"a1","seq":7,"event":"TAP"}"""))   // match
        val ev = r.eventsAfter(Watermark("a1", 5), "TAP")
        assertThat(ev.map { it.seq }).containsExactly(7)
    }

    @Test fun `ignores non-fixture and malformed lines`() {
        val r = LogcatEventReader()
        r.ingest("06-19 10:00:00.123 1234 1234 D OtherTag: hello")
        r.ingest(line("not-json"))
        assertThat(r.latestWatermark()).isNull()
    }
}
