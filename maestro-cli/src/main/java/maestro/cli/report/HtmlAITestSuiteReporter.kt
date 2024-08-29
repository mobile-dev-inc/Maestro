package maestro.cli.report

import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.img
import kotlinx.html.lang
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.stream.appendHTML
import kotlinx.html.style
import kotlinx.html.title
import kotlinx.html.unsafe
import readResourceAsText
import java.io.File

// TODO(bartekpacia): Ideally, AI output would be in the same HTML file as "normal test output". There is no inherent reason
//  to split those 2 streams of output ("normal" and "AI") into 2 separate HTML files.
//  See issue #1973
class HtmlAITestSuiteReporter {

    private val FlowAIOutput.htmlReportFilename
        get() = "ai-report-${flowName}.html"

    private val reportCss: String
        get() = readResourceAsText(this::class, "/ai_report.css")

    private val reportJs: String
        get() = readResourceAsText(this::class, "/tailwind.config.js")

    /**
     * Creates HTML files in [outputDestination] for each flow in [outputs].
     */
    fun report(outputs: List<FlowAIOutput>, outputDestination: File) {
        if (!outputDestination.isDirectory) throw IllegalArgumentException("Output destination must be a directory")

        outputs.forEachIndexed { index, output ->
            val htmlContent = buildHtmlReport(outputs, index)
            val file = File(outputDestination, output.htmlReportFilename)
            file.writeText(htmlContent)
        }
    }

    /**
     * Build HTML report for a single flow.
     *
     * Information about other flows is needed to generate links to them.
     */
    private fun buildHtmlReport(outputs: List<FlowAIOutput>, index: Int): String {
        val summary = outputs[index]

        return buildString {
            appendLine("<!DOCTYPE html>")
            appendHTML().html {
                lang = "en"

                head {
                    meta { charset = "UTF-8" }
                    meta { name = "viewport"; content = "width=device-width, initial-scale=1.0" }
                    title { +"Maestro Test Report" }
                    script { src = "https://cdn.tailwindcss.com/3.4.5" }

                    script {
                        unsafe { +reportJs }
                    }

                    style(type = "text/tailwindcss") { +reportCss }
                }

                body {
                    div(classes = "flex min-h-screen flex-col") {

                        // Header area
                        div(classes = "container mx-auto py-6 space-y-2") {
                            h1(classes = "text-3xl") {
                                +"AI suggestions for flow "
                                span(classes = "text-gray-medium") {
                                    +summary.flowName
                                }
                            }

                            // File chooser for different reports
                            div(classes = "group relative inline-block self-start") {
                                button(classes = "btn") { +"→ Open other report" }
                                div(classes = "absolute z-10 hidden min-w-32 group-hover:block") {
                                    outputs.forEachIndexed { outputIndex, outputFlow ->
                                        val selected = outputIndex == index

                                        a(classes = buildString {
                                          append("toggle-link")

                                            if (selected) append(" toggle-link-selected")
                                        } ) {
                                            href = "./" + outputs[outputIndex].htmlReportFilename
                                            val name = outputFlow.flowFile.nameWithoutExtension
                                            +"(${outputIndex + 1}) $name"
                                        }
                                    }
                                }
                            }

                            // Link to the flow file
                            // FIXME(bartekpacia): This path will be broken when moved across machines
                            p {
                                a(
                                    classes = "btn", href = summary.flowFile.absolutePath
                                ) {
                                    +"→ Open flow file ( ${summary.flowFile.name} )"
                                }
                            }
                        }

                        // Container for list of screenshots
                        main(classes = "container mx-auto flex flex-col gap-4") {
                            // Overall defect count for the flow
                            p(classes = "text-lg") {
                                val word = if (summary.defectCount == 1) "defect" else "defects"
                                +"${summary.defectCount} possible $word found"
                            }

                            // List of screenshots within flow with defects founds
                            summary.screenOutputs.forEachIndexed { screenIndex, screenSummary ->
                                div(classes = "screen-card") {
                                    img(classes = "screenshot-image") {
                                        alt = "Screenshot of the defect"
                                        // Use relative path, so when file is moved across machines, it still works
                                        src = screenSummary.screenshotPath.name.toString()
                                    }

                                    // defect-card-container
                                    div(classes = "flex flex-col gap-4 flex-grow") {
                                        // Defect count for the screen
                                        p(classes = "text-lg") {
                                            val word = if (screenSummary.defects.size == 1) "defect" else "defects"
                                            +"${screenSummary.defects.size} possible $word"
                                        }

                                        screenSummary.defects.forEachIndexed { i, defect ->
                                            div(classes = "defect-card") {
                                                p { +defect.reasoning }
                                                div(classes = "badge") { +defect.category }
                                            }
                                        }
                                    }
                                }

                                if (screenIndex != summary.screenOutputs.size - 1) {
                                    div(classes = "divider")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private val FlowAIOutput.defectCount: Int
        get() = screenOutputs.flatMap { it.defects }.size
}
