package maestro.cli.util

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import maestro.cli.api.ApiClient
import maestro.cli.cloud.CloudInteractor

data class FlowFiles(
    val imageFiles: List<Pair<ByteArray, Path>>,
    val textFiles: List<Pair<ByteArray, Path>>
)

class TestAnalysisReporter(private val apiUrl: String, private val apiKey: String?) {
    private val apiclient by lazy {
        ApiClient(apiUrl)
    }

    fun runAnalysis(debugOutputPath: Path): Int {
        val flowFiles = processFilesByFlowName(debugOutputPath)
        if (flowFiles.isEmpty()) {
            PrintUtils.warn("No screenshots or debug artifacts found for analysis.")
            return 0;
        }

        return CloudInteractor(apiclient).analyze(
            apiKey = apiKey,
            flowFiles = flowFiles,
            debugOutputPath = debugOutputPath
        )
    }

    private fun processFilesByFlowName(outputPath: Path): List<FlowFiles> {
        val files = Files.walk(outputPath)
            .filter(Files::isRegularFile)
            .collect(Collectors.toList())

        return if (files.isNotEmpty()) {
            val (imageFiles, textFiles) = getDebugFiles(files)
            listOf(
                FlowFiles(
                    imageFiles = imageFiles,
                    textFiles = textFiles
                )
            )
        } else {
            emptyList()
        }
    }

    private fun getDebugFiles(files: List<Path>): Pair<List<Pair<ByteArray, Path>>, List<Pair<ByteArray, Path>>> {
        val imageFiles = mutableListOf<Pair<ByteArray, Path>>()
        val textFiles = mutableListOf<Pair<ByteArray, Path>>()

        files.forEach { filePath ->
            val content = Files.readAllBytes(filePath)
            val fileName = filePath.fileName.toString().lowercase()

            when {
                fileName.endsWith(".png") || fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") -> {
                    imageFiles.add(content to filePath)
                }

                fileName.startsWith("commands") -> {
                    textFiles.add(content to filePath)
                }

                fileName == "maestro.log" -> {
                    textFiles.add(content to filePath)
                }
            }
        }

        return Pair(imageFiles, textFiles)
    }
}
