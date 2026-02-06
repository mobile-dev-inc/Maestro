package maestro.cli.report

import kotlinx.html.*
import kotlinx.html.stream.appendHTML
import maestro.cli.model.TestExecutionSummary
import okio.Sink
import okio.buffer

class HtmlTestSuiteReporter(private val detailed: Boolean = false) : TestSuiteReporter {

    companion object {
        private fun loadPrettyCss(): String {
            return HtmlTestSuiteReporter::class.java
                .getResourceAsStream("/html-detailed.css")
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: ""
        }
    }

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
                                suite.flows.forEachIndexed { index, flow ->
                                    val buttonClass =
                                        if (flow.status.toString() == "ERROR") "btn btn-danger" else "btn btn-success"
                                    // Create a valid HTML ID by sanitizing the flow name
                                    val flowId = "flow-$index-${flow.name.replace(Regex("[^a-zA-Z0-9_-]"), "-")}"
                                    div(classes = "card mb-4") {
                                        div(classes = "card-header") {
                                            h5(classes = "mb-0") {
                                                button(classes = buttonClass) {
                                                    attributes["type"] = "button"
                                                    attributes["data-bs-toggle"] = "collapse"
                                                    attributes["data-bs-target"] = "#$flowId"
                                                    attributes["aria-expanded"] = "false"
                                                    attributes["aria-controls"] = flowId
                                                    +"${flow.name} : ${flow.status}"
                                                }
                                            }
                                        }
                                        div(classes = "collapse") {
                                            id = flowId
                                            div(classes = "card-body") {
                                                p(classes = "card-text") {
                                                    +"Status: ${flow.status}"
                                                    br {}
                                                    +"Duration: ${flow.duration}"
                                                    br {}
                                                    +"Start Time: ${
                                                        flow.startTime?.let {
                                                            millisToCurrentLocalDateTime(
                                                                it
                                                            )
                                                        }
                                                    }"
                                                    br {}
                                                    if (flow.fileName != null) {
                                                        +"File Name: ${flow.fileName}"
                                                        br {}
                                                    }
                                                    
                                                    // Display tags if present
                                                    if (!flow.tags.isNullOrEmpty()) {
                                                        +"Tags: "
                                                        flow.tags.forEach { tag ->
                                                            span(classes = "badge bg-primary me-1") {
                                                                +tag
                                                            }
                                                        }
                                                        br {}
                                                    }
                                                }
                                                
                                                // Display properties if present
                                                if (!flow.properties.isNullOrEmpty()) {
                                                    h6(classes = "mt-3 mb-2") { +"Properties" }
                                                    div(classes = "table-responsive") {
                                                        table(classes = "table table-sm table-bordered") {
                                                            thead {
                                                                tr {
                                                                    th { +"Property" }
                                                                    th { +"Value" }
                                                                }
                                                            }
                                                            tbody {
                                                                flow.properties.forEach { (key, value) ->
                                                                    tr {
                                                                        td { +key }
                                                                        td { +value }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                
                                                if (flow.failure != null) {
                                                    p(classes = "card-text text-danger") {
                                                        +flow.failure.message
                                                    }
                                                }

                                                // Show detailed steps when detailed mode is enabled
                                                if (detailed && flow.steps.isNotEmpty()) {
                                                    h6(classes = "mt-3 mb-3") { +"Test Steps (${flow.steps.size})" }

                                                    flow.steps.forEach { step ->
                                                        val statusIcon = when (step.status) {
                                                            "COMPLETED" -> "✅"
                                                            "WARNED" -> "⚠️"
                                                            "FAILED" -> "❌"
                                                            "SKIPPED" -> "⏭️"
                                                            else -> "⚪"
                                                        }

                                                        div(classes = "step-item mb-2") {
                                                            div(classes = "step-header d-flex justify-content-between align-items-center") {
                                                                span {
                                                                    +"$statusIcon "
                                                                    span(classes = "step-name") { +step.description }
                                                                }
                                                                span(classes = "badge bg-light text-dark") {
                                                                    +step.duration
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            // Add styling for step items when detailed mode is enabled
                            if (detailed) {
                                style {
                                    unsafe {
                                        +loadPrettyCss()
                                    }
                                }
                            }
                            script(
                                src = "https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.min.js",
                                content = ""
                            )
                        }
                    }
                }
            }
        }
    }
}
