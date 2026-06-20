package maestro.conformance.report

import com.google.common.truth.Truth.assertThat
import maestro.conformance.behavior.OracleKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ReporterTest {
    @TempDir lateinit var tmp: File

    private fun rec(cmd: String, pass: Boolean) = CommandRecord(
        command = cmd, coverage = "framework-sensitive",
        args = mapOf("point" to listOf(10, 20)),
        oracleKind = OracleKind.APP_EVENT,
        expected = mapOf("event" to "TAP"), actual = mapOf("event" to "TAP"),
        verdict = pass, failureReason = if (pass) null else "no event", actMs = 12, totalMs = 30,
    )

    @Test fun `writes command json with verdict and fields`() {
        val r = Reporter(tmp)
        r.writeCommand("api34-native", rec("tap", true))
        val f = File(tmp, "cells/api34-native/tap/command.json")
        assertThat(f.exists()).isTrue()
        val text = f.readText()
        assertThat(text).contains("\"command\" : \"tap\"")
        assertThat(text).contains("\"verdict\" : \"PASS\"")
    }

    @Test fun `summary html lists cells and a fail count`() {
        val r = Reporter(tmp)
        r.writeCell("api34-native", listOf(rec("tap", true), rec("swipe", false)))
        r.writeSummary("device: emulator-5554")
        val dataJs = File(tmp, "data.js").readText()
        assertThat(dataJs).contains("api34-native")
        assertThat(dataJs).startsWith("window.DATA = {")
        val index = File(tmp, "index.html").readText()
        assertThat(index).contains("data.js")
        assertThat(File(tmp, "summary.json").readText()).contains("\"failed\" : 1")
    }
}
