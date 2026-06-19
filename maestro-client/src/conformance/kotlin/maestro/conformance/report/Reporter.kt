package maestro.conformance.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import java.io.File

class Reporter(private val root: File) {
    private val mapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    private val cells = LinkedHashMap<String, List<CommandRecord>>()

    private fun verdictStr(p: Boolean) = if (p) "PASS" else "FAIL"

    fun commandDir(cell: String, command: String): File =
        File(root, "cells/$cell/$command").apply { mkdirs() }

    fun writeCommand(cell: String, record: CommandRecord) {
        val dir = commandDir(cell, record.command)
        val json = mapper.writeValueAsString(
            linkedMapOf(
                "command" to record.command,
                "coverage" to record.coverage,
                "args" to record.args,
                "oracle" to linkedMapOf(
                    "kind" to record.oracleKind.name,
                    "expected" to record.expected,
                    "actual" to record.actual,
                ),
                "verdict" to verdictStr(record.verdict),
                "failureReason" to record.failureReason,
                "timings" to mapOf("actMs" to record.actMs, "totalMs" to record.totalMs),
                "artifacts" to record.artifacts,
            )
        )
        File(dir, "command.json").writeText(json)
    }

    fun writeCell(cell: String, records: List<CommandRecord>) {
        cells[cell] = records
        records.forEach { writeCommand(cell, it) }
        val dir = File(root, "cells/$cell").apply { mkdirs() }
        File(dir, "cell.json").writeText(
            mapper.writeValueAsString(records.map {
                mapOf("command" to it.command, "verdict" to verdictStr(it.verdict))
            })
        )
    }

    fun writeSummary(banner: String) {
        val all = cells.values.flatten()
        val failed = all.count { !it.verdict }
        val summary = linkedMapOf(
            "banner" to banner,
            "total" to all.size,
            "passed" to all.count { it.verdict },
            "failed" to failed,
            "cells" to cells.mapValues { (_, recs) ->
                recs.associate { it.command to verdictStr(it.verdict) }
            },
        )
        val json = mapper.writeValueAsString(summary)
        File(root, "summary.json").writeText(json)
        File(root, "summary.js").writeText("window.SUMMARY = $json;")
        File(root, "index.html").writeText(buildHtml(banner))
    }

    private fun buildHtml(banner: String): String {
        val rows = cells.entries.joinToString("\n") { (cell, recs) ->
            val tds = recs.joinToString("") { r ->
                val color = if (r.verdict) "#1b5e20" else "#b71c1c"
                "<td style='background:$color;color:#fff'>${r.command}</td>"
            }
            "<tr><th>$cell</th>$tds</tr>"
        }
        return """
            <!doctype html><html><head><meta charset="utf-8">
            <title>Driver Conformance</title></head><body>
            <h1>Driver Conformance</h1><p>$banner</p>
            <table border="1" cellspacing="0" cellpadding="6">$rows</table>
            <script src="./summary.js"></script></body></html>
        """.trimIndent()
    }
}
