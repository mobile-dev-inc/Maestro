package maestro.cli.insights

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import maestro.cli.api.ApiClient
import maestro.cli.cloud.CloudInteractor
import maestro.cli.util.EnvUtils
import maestro.cli.util.PrintUtils
import maestro.cli.view.box
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

data class AnalysisScreenshot (
    val data: ByteArray,
    val path: Path,
)

data class AnalysisLog (
    val data: ByteArray,
    val path: Path,
)

data class AnalysisDebugFiles(
    val screenshots: List<AnalysisScreenshot>,
    val logs: List<AnalysisLog>,
    val commands: List<AnalysisLog>,
)

class TestAnalysisManager(private val apiUrl: String, private val apiKey: String?) {
    private val apiclient by lazy {
        ApiClient(apiUrl)
    }

    fun runAnalysis(debugOutputPath: Path): Int {
        val debugFiles = processDebugFiles(debugOutputPath)
        if (debugFiles == null) {
            PrintUtils.warn("No screenshots or debug artifacts found for analysis.")
            return 0;
        }

        return CloudInteractor(apiclient).analyze(
            apiKey = apiKey,
            debugFiles = debugFiles,
            debugOutputPath = debugOutputPath
        )
    }

    private fun processDebugFiles(outputPath: Path): AnalysisDebugFiles? {
        val files = Files.walk(outputPath)
            .filter(Files::isRegularFile)
            .collect(Collectors.toList())

        if (files.isEmpty()) {
            return null
        }

        return getDebugFiles(files)
    }

    private fun getDebugFiles(files: List<Path>): AnalysisDebugFiles {
        val logs = mutableListOf<AnalysisLog>()
        val commands = mutableListOf<AnalysisLog>()
        val screenshots = mutableListOf<AnalysisScreenshot>()

        files.forEach { path ->
            val data = Files.readAllBytes(path)
            val fileName = path.fileName.toString().lowercase()

            when {
                fileName.endsWith(".png") || fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") -> {
                    screenshots.add(AnalysisScreenshot(data = data, path = path))
                }

                fileName.startsWith("commands") -> {
                    commands.add(AnalysisLog(data = data, path = path))
                }

                fileName == "maestro.log" -> {
                    logs.add(AnalysisLog(data = data, path = path))
                }
            }
        }

        return AnalysisDebugFiles(
            logs = logs,
            commands = commands,
            screenshots = screenshots,
        )
    }

    /**
     * The analytics system for Test Analysis.
     *  - Uses configuration from $XDG_CONFIG_HOME/maestro/analyze-analytics.json.
     */
    companion object Analytics {
        private const val DISABLE_INSIGHTS_ENV_VAR = "MAESTRO_CLI_INSIGHTS_NOTIFICATION_DISABLED"
        private val disabled: Boolean
            get() = System.getenv(DISABLE_INSIGHTS_ENV_VAR) == "true"

        private val analyticsStatePath: Path = EnvUtils.xdgStateHome().resolve("analyze-analytics.json")

        private val JSON = jacksonObjectMapper().apply {
            registerModule(JavaTimeModule())
            enable(SerializationFeature.INDENT_OUTPUT)
        }

        private val shouldNotNotify: Boolean
            get() = disabled || analyticsStatePath.exists() && analyticsState.acknowledged

        private val analyticsState: AnalyticsState
            get() = JSON.readValue<AnalyticsState>(analyticsStatePath.readText())

        fun maybeNotify() {
            if (shouldNotNotify) return

            println(
                listOf(
                    "Tryout our new Analyze with Ai feature.\n",
                    "See what's new:",
                    "> https://maestro.mobile.dev/cli/test-suites-and-reports#analyze",
                    "Analyze command:",
                    "$ maestro test flow-file.yaml --analyze | bash\n",
                    "To disable this notification, set $DISABLE_INSIGHTS_ENV_VAR environment variable to \"true\" before running Maestro."
                ).joinToString("\n").box()
            )
            ack();
        }

        private fun ack() {
            val state = AnalyticsState(
                acknowledged = true
            )

            val stateJson = JSON.writeValueAsString(state)
            analyticsStatePath.parent.createDirectories()
            analyticsStatePath.writeText(stateJson + "\n")
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnalyticsState(
    val acknowledged: Boolean = false
)
