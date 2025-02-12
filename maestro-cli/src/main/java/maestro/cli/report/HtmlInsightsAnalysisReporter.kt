package maestro.cli.report

import java.nio.file.Files
import java.nio.file.Path

class HtmlInsightsAnalysisReporter {

    fun report(
        html: String,
        outputDestination: Path
    ): Path {
        if (!Files.isDirectory(outputDestination)) {
            throw IllegalArgumentException("Output destination must be a directory")
        }

        val fileName = "insights-report.html"
        val filePath = outputDestination.resolve(fileName)

        Files.write(filePath, html.toByteArray())

        return filePath
    }
}
