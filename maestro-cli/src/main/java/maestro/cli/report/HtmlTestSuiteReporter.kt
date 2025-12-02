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

                                                    var previousDepth = -1
                                                    flow.steps.forEach { step ->
                                                        val statusIcon = when (step.status) {
                                                            "COMPLETED" -> "✅"
                                                            "WARNED" -> "⚠️"
                                                            "FAILED" -> "❌"
                                                            "SKIPPED" -> "⏭️"
                                                            else -> "⚪"
                                                        }

                                                        val iterationLabel = step.iteration?.let { " (iteration ${it + 1})" } ?: ""

                                                        // Determine CSS classes
                                                        val cssClasses = buildString {
                                                            append("step-item mb-2")
                                                            if (step.depth > 0) {
                                                                append(" nested")
                                                            }
                                                            if (step.depth > previousDepth) {
                                                                append(" first-at-depth")
                                                            }
                                                        }

                                                        div(classes = cssClasses) {
                                                            style = "--depth: ${step.depth}"
                                                            div(classes = "step-header d-flex justify-content-between align-items-center") {
                                                                span {
                                                                    +"$statusIcon "
                                                                    span(classes = "step-name") {
                                                                        +step.description
                                                                        +iterationLabel
                                                                    }
                                                                }
                                                                span(classes = "badge bg-light text-dark") {
                                                                    +step.duration
                                                                }
                                                            }
                                                        }

                                                        previousDepth = step.depth
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
