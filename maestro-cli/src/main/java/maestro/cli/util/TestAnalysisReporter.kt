package maestro.cli.util

import maestro.ai.AI

import maestro.ai.Prediction
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import kotlinx.coroutines.runBlocking
import maestro.ai.FlowFiles
import maestro.ai.Insight
import maestro.ai.openai.OpenAI
import maestro.cli.report.HtmlInsightsAnalysisReporter
import java.util.Locale

class TestAnalysisReporter {

    private fun initAI(): AI {
        val apiKey = "API-KEY"
        val modelName = "gpt-4o"

        return OpenAI(apiKey = apiKey, defaultModel = modelName)
    }

    fun runAnalysis(debugOutputPath: Path) {
        val filesByFlow = processFilesByFlowName(debugOutputPath)
        if (filesByFlow.isEmpty()) {
            PrintUtils.warn("No files found for analysis.")
            return
        }

        PrintUtils.info("\nAnalysing and generating insights...\n")

        val insights = generateInsights(filesByFlow)
        val outputFilePath = HtmlInsightsAnalysisReporter().report(filesByFlow, insights, debugOutputPath)
        val os = System.getProperty("os.name").lowercase(Locale.getDefault())

        PrintUtils.message(listOf(
            "To view the report, open the following link in your browser:",
            "file:${if (os.contains("win")) "///" else "//"}${outputFilePath}\n",
            "Analyze support is in Beta. We would appreciate your feedback!"
        ).joinToString("\n"))
    }

    private fun generateInsights(flowFiles: List<FlowFiles>): List<Insight> = runBlocking {
        val aiClient = initAI()

        Prediction.generateInsights(
            aiClient = aiClient,
            flowFiles = flowFiles,
        )
    }

    private fun processFilesByFlowName(outputPath: Path): List<FlowFiles> {
        val files = Files.walk(outputPath)
            .filter(Files::isRegularFile)
            .collect(Collectors.toList())

        return if (files.isNotEmpty()) {
            val (imageFiles, jsonFiles, textFiles) = getFilesByType(files)
            listOf(
                FlowFiles(
                    jsonFiles = jsonFiles,
                    imageFiles = imageFiles,
                    textFiles = textFiles
                )
            )
        } else {
            emptyList()
        }
    }

    private fun getFilesByType(files: List<Path>): Triple<List<Pair<ByteArray, Path>>, List<Pair<ByteArray, Path>>, List<Pair<ByteArray, Path>>> {
        val imageFiles = mutableListOf<Pair<ByteArray, Path>>()
        val jsonFiles = mutableListOf<Pair<ByteArray, Path>>()
        val textFiles = mutableListOf<Pair<ByteArray, Path>>()

        files.forEach { filePath ->
            val content = Files.readAllBytes(filePath)
            val fileName = filePath.fileName.toString()

            when {
                fileName.endsWith(".png", true) || fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> {
                    imageFiles.add(content to filePath)
                }
                fileName.endsWith(".json", true) -> {
                    jsonFiles.add(content to filePath)
                }
                else -> {
                    textFiles.add(content to filePath)
                }
            }
        }

        return Triple(imageFiles, jsonFiles, textFiles)
    }
}
