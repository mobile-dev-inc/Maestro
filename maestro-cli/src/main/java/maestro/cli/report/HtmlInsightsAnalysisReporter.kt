package maestro.cli.report

import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.h4
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.img
import kotlinx.html.lang
import kotlinx.html.li
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.pre
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.stream.appendHTML
import kotlinx.html.style
import kotlinx.html.svg
import kotlinx.html.title
import kotlinx.html.ul
import kotlinx.html.unsafe
import maestro.ai.FlowFiles
import maestro.ai.Insight
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

class HtmlInsightsAnalysisReporter {

    fun report(
        flowFiles: List<FlowFiles>,
        insights: List<Insight>,
        outputDestination: Path
    ): Path {
        if (!Files.isDirectory(outputDestination)) {
            throw IllegalArgumentException("Output destination must be a directory")
        }

        val htmlContent = buildInsightHtmlReport(flowFiles, insights)
        val fileName = "Test_Report.html"
        val filePath = outputDestination.resolve(fileName)

        Files.write(filePath, htmlContent.toByteArray())

        return filePath
    }

    private fun buildInsightHtmlReport(flowFiles: List<FlowFiles>, insights: List<Insight>): String {
        return buildString {
            appendLine("<!DOCTYPE html>")
            appendHTML().html {
                lang = "en"
                head {
                    meta { charset = "UTF-8" }
                    meta { name = "viewport"; content = "width=device-width, initial-scale=1.0" }
                    title { +"Maestro Insights Report" }
                    script { src = "https://cdn.tailwindcss.com" }
                    style {
                        unsafe {
                            +"""
                        @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap');
                        body {
                            font-family: 'Inter', sans-serif;
                        }
                        .image-carousel {
                            display: flex;
                            overflow-x: auto;
                            scroll-snap-type: x mandatory;
                            gap: 1rem;
                            padding: 1rem 0;
                        }
                        .image-carousel::-webkit-scrollbar {
                            display: none;
                        }
                        .carousel-item {
                            flex: 0 0 auto;
                            scroll-snap-align: start;
                        }
                        img {
                            max-height: 450px;
                            width: auto;
                            border-radius: 8px;
                        }
                        .file-link {
                            color: #2563eb;
                            text-decoration: underline;
                            margin-top: 0.5rem;
                            display: inline-block;
                        }
                        """
                        }
                    }
                }
                body(classes = "bg-gray-100 dark:bg-gray-900 text-gray-900 dark:text-gray-100") {
                    div(classes = "container mx-auto px-4 py-8") {
                        h1(classes = "text-4xl font-bold mb-8") { +"Maestro Insight Report" }
                        h2(classes = "text-2xl font-semibold mb-4") { +"Insights" }
                        div(classes = "grid grid-cols-1 md:grid-cols-2 gap-6") {
                            insights.groupBy { it.category }.forEach { (category, categoryInsights) ->
                                val emoji = when (category) {
                                    "visual" -> "🎨"
                                    "text" -> "📝"
                                    "maestro" -> "🛠️"
                                    else -> "🔍"
                                }
                                div(classes = "bg-white dark:bg-gray-800 rounded-lg shadow-lg overflow-hidden") {
                                    div(classes = "p-6 h-full flex flex-col") {
                                        div(classes = "flex items-center mb-4") {
                                            span(classes = "text-4xl mr-3") { +emoji }
                                            h3(classes = "text-2xl font-semibold") { +category.capitalize(Locale.ROOT) }
                                        }
                                        ul(classes = "space-y-4 flex-grow") {
                                            categoryInsights.forEach { insight ->
                                                li(classes = "flex items-start") {
                                                    svg(classes = "w-6 h-6 text-green-500 mr-2 flex-shrink-0") {
                                                        unsafe {
                                                            +"""
                                                        <path 
                                                            stroke-linecap="round" 
                                                            stroke-linejoin="round" 
                                                            stroke-width="2" 
                                                            d="M5 13l4 4L19 7" 
                                                        />
                                                        """
                                                        }
                                                    }
                                                    p(classes = "text-base") { +insight.reasoning }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        flowFiles.forEach { fileGroup ->
                            h2(classes = "text-2xl font-semibold my-8") { +"Debug Artifacts" }
                            div(classes = "image-carousel") {
                                fileGroup.imageFiles.forEachIndexed { index, imageFile ->
                                    div(classes = "carousel-item") {
                                        img {
                                            alt = "Image ${index + 1}"
                                            src = imageFile.second.toString()
                                        }
                                        a(classes = "file-link", href = imageFile.second.toString(), target = "_blank") { +"Open Image ${index + 1}" }
                                    }
                                }
                            }
                            div(classes = "grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6 my-4") {
                                fileGroup.jsonFiles.forEachIndexed { index, jsonFile ->
                                    div(classes = "bg-white dark:bg-gray-800 rounded-lg shadow-lg overflow-hidden") {
                                        div(classes = "p-4") {
                                            h4(classes = "font-semibold text-lg mb-2") { +"JSON File ${index + 1}" }
                                            pre(classes = "text-sm bg-gray-100 dark:bg-gray-700 p-2 rounded overflow-hidden") {
                                                style = "max-height: 100px"
                                                +String(jsonFile.first)
                                            }
                                            a(classes = "file-link", href = jsonFile.second.toString(), target = "_blank") { +"Open JSON File ${index + 1}" }
                                        }
                                    }
                                }
                                fileGroup.textFiles.forEachIndexed { index, textFile ->
                                    div(classes = "bg-white dark:bg-gray-800 rounded-lg shadow-lg overflow-hidden") {
                                        div(classes = "p-4") {
                                            h4(classes = "font-semibold text-lg mb-2") { +"Text File ${index + 1}" }
                                            pre(classes = "text-sm bg-gray-100 dark:bg-gray-700 p-2 rounded overflow-hidden") {
                                                style = "max-height: 100px"
                                                +String(textFile.first)
                                            }
                                            a(classes = "file-link", href = textFile.second.toString(), target = "_blank") { +"Open Text File ${index + 1}" }
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
}
