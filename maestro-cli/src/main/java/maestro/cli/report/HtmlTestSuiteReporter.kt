package maestro.cli.report

import kotlinx.html.*
import kotlinx.html.stream.appendHTML
import maestro.LogEntry
import maestro.cli.model.FlowStatus
import maestro.cli.model.TestExecutionSummary
import okio.Sink
import okio.buffer

class HtmlTestSuiteReporter : TestSuiteReporter {
    override fun report(summary: TestExecutionSummary, out: Sink) {
        val bufferedOut = out.buffer()
        val htmlContent = buildHtmlReport(summary)
        bufferedOut.writeUtf8(htmlContent)
        bufferedOut.close()
    }

    private fun buildHtmlReport(summary: TestExecutionSummary): String {

        return buildString {
            appendHTML().html {
                head {
                    title { +"Maestro Test Report" }
                    link(
                        rel = "stylesheet",
                        href = "https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css"
                    ) {}
                }
                body {
                    summary.suites.forEach { suite ->
                        val failedTests = suite.failures()
                        div(classes = "card mb-4") {
                            div(classes = "card-body") {
                                h1(classes = "mt-5 text-center") { +"Flow Execution Summary" }
                                br {}
                                +"Test Result: ${if (suite.passed) "PASSED" else "FAILED"}"
                                br {}
                                +"Duration: ${suite.duration}"
                                br {}
                                +"Start Time: ${suite.startTime?.let { millisToCurrentLocalDateTime(it) }}"
                                br {}
                                br {}
                                div(classes = "card-group mb-4") {
                                    div(classes = "card") {
                                        div(classes = "card-body") {
                                            h5(classes = "card-title text-center") { +"Total number of Flows" }
                                            h3(classes = "card-text text-center") { +"${suite.flows.size}" }
                                        }
                                    }
                                    div(classes = "card text-white bg-danger") {
                                        div(classes = "card-body") {
                                            h5(classes = "card-title text-center") { +"Failed Flows" }
                                            h3(classes = "card-text text-center") { +"${failedTests.size}" }
                                        }
                                    }
                                    div(classes = "card text-white bg-success") {
                                        div(classes = "card-body") {
                                            h5(classes = "card-title text-center") { +"Successful Flows" }
                                            h3(classes = "card-text text-center") { +"${suite.flows.size - failedTests.size}" }
                                        }
                                    }
                                }
                                if (failedTests.isNotEmpty()) {
                                    div(classes = "card border-danger mb-3") {
                                        div(classes = "card-body text-danger") {
                                            b { +"Failed Flow" }
                                            br {}
                                            p(classes = "card-text") {
                                                failedTests.forEach { test ->
                                                    +test.name
                                                    br {}
                                                }
                                            }
                                        }
                                    }
                                }
                                suite.flows.forEach { flow ->
                                    val buttonClass =
                                        if (flow.status.toString() == "ERROR") "btn btn-danger" else "btn btn-success"
                                    div(classes = "card mb-4") {
                                        div(classes = "card-header") {
                                            h5(classes = "mb-0") {
                                                button(classes = buttonClass) {
                                                    attributes["type"] = "button"
                                                    attributes["data-bs-toggle"] = "collapse"
                                                    attributes["data-bs-target"] = "#${flow.name}"
                                                    attributes["aria-expanded"] = "false"
                                                    attributes["aria-controls"] = flow.name
                                                    +"${flow.name} : ${flow.status}"
                                                }
                                            }
                                        }
                                        div(classes = "collapse") {
                                            id = flow.name
                                            div(classes = "card-body") {
                                                // Flow summary
                                                div(classes = "mb-3") {
                                                    p(classes = "card-text") {
                                                        +"Status: ${flow.status}"
                                                        br {}
                                                        +"Duration: ${flow.duration}"
                                                        br {}
                                                        +"Total Steps: ${flow.commandLogs.size}"
                                                        br {}
                                                        +"File Name: ${flow.fileName}"
                                                    }
                                                    if (flow.failure != null) {
                                                        p(classes = "card-text text-danger") {
                                                            +flow.failure.message
                                                        }
                                                    }
                                                }

                                                // Step list with logs
                                                if (flow.commandLogs.isNotEmpty()) {
                                                    h6(classes = "mt-2 mb-3") { +"Test Steps (${flow.commandLogs.size})" }

                                                    flow.commandLogs.entries.forEachIndexed { idx, (commandKey, logs) ->
                                                        val stepId = "${flow.name}-step-$idx"
                                                        // Parse command description, duration, and status from key
                                                        val parts = commandKey.split("|||")
                                                        val commandDesc = parts[0]
                                                        val duration = parts.getOrNull(1) ?: "unknown"
                                                        val status = parts.getOrNull(2) ?: "UNKNOWN"

                                                        val statusIcon = when (status) {
                                                            "COMPLETED" -> "✅"
                                                            "WARNED" -> "⚠️"
                                                            "FAILED" -> "❌"
                                                            "SKIPPED" -> "⏭️"
                                                            else -> "⚪"
                                                        }

                                                        div(classes = "step-item mb-2") {
                                                            // Step header (clickable)
                                                            button(classes = "btn btn-sm btn-outline-primary w-100 text-start step-header") {
                                                                attributes["type"] = "button"
                                                                attributes["data-bs-toggle"] = "collapse"
                                                                attributes["data-bs-target"] = "#$stepId"

                                                                +"$statusIcon "
                                                                span(classes = "step-name") { +commandDesc }
                                                                span(classes = "badge bg-light text-dark ms-2") {
                                                                    +duration
                                                                }
                                                            }

                                                            // Step details (collapse)
                                                            div(classes = "collapse") {
                                                                id = stepId
                                                                div(classes = "card card-body mt-1") {
                                                                    // Logs toggle button
                                                                    button(classes = "btn btn-sm btn-secondary mb-2") {
                                                                        attributes["onclick"] = "toggleLogs('$stepId-logs')"
                                                                        +"View Console Logs (${logs.size} entries)"
                                                                    }

                                                                    // Logs container (hidden by default)
                                                                    div(classes = "log-container") {
                                                                        id = "$stepId-logs"
                                                                        attributes["style"] = "display: none;"
                                                                        logs.forEach { log ->
                                                                            renderLogEntry(log)
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                } else if (flow.logs.isNotEmpty()) {
                                                    // Fallback: show all logs
                                                    h6(classes = "mt-3") { +"Console Logs (${flow.logs.size} entries)" }
                                                    div(classes = "log-container") {
                                                        flow.logs.take(100).forEach { log ->
                                                            renderLogEntry(log)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            // Add basic log styling
                            style {
                                unsafe {
                                    +"""
                                    .log-container {
                                        background: #1e1e1e;
                                        color: #d4d4d4;
                                        padding: 10px;
                                        border-radius: 4px;
                                        max-height: 400px;
                                        overflow-y: auto;
                                        font-family: 'Courier New', monospace;
                                        font-size: 12px;
                                    }
                                    .command-separator {
                                        margin-bottom: 20px;
                                        border-left: 4px solid #007bff;
                                        padding-left: 10px;
                                    }
                                    .command-title {
                                        color: #007bff;
                                        font-size: 14px;
                                        font-weight: 600;
                                        margin-bottom: 8px;
                                        padding: 8px;
                                        background: #2d2d30;
                                        border-radius: 4px;
                                    }
                                    .command-separator .log-container {
                                        margin-top: 0;
                                        max-height: 300px;
                                    }
                                    .log-entry {
                                        padding: 2px 0;
                                        white-space: pre-wrap;
                                        word-wrap: break-word;
                                    }
                                    .log-error { color: #f48771; }
                                    .log-warn { color: #dcdcaa; }
                                    .log-info { color: #4ec9b0; }
                                    .log-debug { color: #569cd6; }
                                    .log-verbose { color: #858585; }
                                    .step-item {
                                        border-left: 3px solid #dee2e6;
                                        padding-left: 8px;
                                    }
                                    .step-header {
                                        font-family: monospace;
                                    }
                                    .step-name {
                                        font-weight: 500;
                                    }
                                    """.trimIndent()
                                }
                            }
                            script(
                                src = "https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.min.js",
                                content = ""
                            )
                            script {
                                unsafe {
                                    +"""
                                    function toggleLogs(logsId) {
                                        const logsDiv = document.getElementById(logsId);
                                        const button = event.target;
                                        if (logsDiv.style.display === 'none') {
                                            logsDiv.style.display = 'block';
                                            button.textContent = button.textContent.replace('View', 'Hide');
                                        } else {
                                            logsDiv.style.display = 'none';
                                            button.textContent = button.textContent.replace('Hide', 'View');
                                        }
                                    }
                                    """.trimIndent()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun DIV.renderLogEntry(log: LogEntry) {
        div(classes = "log-entry log-${log.level.name.lowercase()}") {
            +"${log.timestamp} ${log.level.name.padEnd(7)} ${log.tag}: ${log.message}"
        }
    }
}
