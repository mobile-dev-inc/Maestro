package maestro.ai

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.float
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import maestro.ai.anthropic.Claude
import maestro.ai.cloud.Defect
import maestro.ai.openai.OpenAI
import java.io.File
import java.nio.file.Path


fun main(args: Array<String>) = DemoApp().main(args)

/**
 * This is a small helper program to help evaluate LLM results against a directory of screenshots and prompts.
 *
 * ### Input format
 *
 * Screenshot name format:
 * - {app_name}_{screenshot_number}_{good|bad}.png
 *
 * A screenshot can optionally have a prompt. In this case, the model will treat the prompt as the assertion command.
 * To associate a prompt with a screenshot, prompt text file name must have
 * the following format:
 * - {app_name_{screenshot_number}_{good|bad}.txt
 *
 * For example:
 * - foo_1_bad.png
 * - bar_2_good.png
 *
 * ### Output format
 *
 * The output for a single screenshot should indicate either PASS or FAIL, screenshot name, the result, and the defects
 * founds (if any).
 *
 * For example:
 *
 * ```text
 * PASS uber_2_bad.png: 1 defects found (as expected)
 * 	* layout: The prompt for entering a verification code is visible, indicating that the 2-factor authentication process is present. The screen instructs the user to enter a verification code generated for Uber, which is a typical 2-factor authentication step.
 * ```
 *
 * Some of the flags change output format.
 */
class DemoApp : CliktCommand() {
    private val inputFiles: List<Path> by argument(help = "screenshots to use").path(mustExist = true).multiple()

    private val model: String by option(help = "LLM to use").default("gpt-4o")

    private val showOnlyFails: Boolean by option(help = "Show only failed tests").flag()

    private val showPrompts: Boolean by option(help = "Show prompts").flag()

    private val showRawResponse: Boolean by option(help = "Show raw LLM response").flag()

    private val temperature: Float by option(help = "Temperature for LLM").float().default(0.2f)

    private val parallel: Boolean by option(help = "Run in parallel. May get rate limited").flag()

    // IDEA: "--json" flag to allow for easy filtering with jq

    override fun run() = runBlocking {
        val apiKey = System.getenv("MAESTRO_CLI_AI_KEY")
        require(apiKey != null) { "OpenAI API key is not provided" }

        val testCases = inputFiles.map { it.toFile() }.map { file ->
            require(!file.isDirectory) { "Provided file is a directory, not a file" }
            require(file.exists()) { "Provided file does not exist" }
            require(file.extension == "png") { "Provided file is not a PNG file" }
            file
        }.map { file ->
            val filename = file.nameWithoutExtension
            val parts = filename.split("_")
            require(parts.size == 3) { "Screenshot name is invalid: ${file.name}" }

            val appName = parts[0]
            val index =
                parts[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid screenshot name: ${file.name}")
            val status = parts[2]

            val promptFile = "${file.parent}/${appName}_${index}_${status}.txt"
            val prompt = File(promptFile).run {
                if (exists()) {
                    println("Found prompt file: $promptFile")
                    readText()
                } else null
            }

            TestCase(
                screenshot = file,
                appName = appName,
                shouldPass = status == "good",
                index = index,
                prompt = prompt,
            )
        }.toList()

        val aiClient: AI = when {
            model.startsWith("gpt") -> OpenAI(
                apiKey = apiKey,
                defaultModel = model,
                defaultTemperature = temperature,
            )

            model.startsWith("claude") -> Claude(
                apiKey = apiKey,
                defaultModel = model,
                defaultTemperature = temperature,
            )

            else -> throw IllegalArgumentException("Unknown model: $model")
        }

        testCases.forEach { testCase ->
            val bytes = testCase.screenshot.readBytes()

            val job = async {
                val defects = if (testCase.prompt == null) Prediction.findDefects(
                    aiClient = aiClient,
                    screen = bytes,
                ) else {
                    val result = Prediction.performAssertion(
                        aiClient = aiClient,
                        screen = bytes,
                        assertion = testCase.prompt,
                    )

                    if (result == null) emptyList()
                    else listOf(result)
                }

                verify(testCase, defects)
            }

            if (!parallel) job.await()
        }
    }

    private fun verify(testCase: TestCase, defects: List<Defect>) {
        if (!testCase.shouldPass) {
            // Check if LLM found defects (i.e. didn't commit false negative)
            if (defects.isNotEmpty()) {
                if (showOnlyFails) return

                println(
                    """
                    PASS ${testCase.screenshot.name}: ${defects.size} defects found (as expected)
                    ${defects.joinToString("\n") { "\t* ${it.category}: ${it.reasoning}" }}
                    """.trimIndent()
                )
            } else {
                println("FAIL ${testCase.screenshot.name} false-negative: No defects found but some were expected")
            }

        } else {
            // Check that LLM didn't raise false positives
            if (defects.isEmpty()) {
                if (showOnlyFails) return

                println(
                    """
                    PASS ${testCase.screenshot.name}: No defects found (as expected)
                    """.trimIndent()
                )
            } else {
                println(
                    """
                    FAIL ${testCase.screenshot.name} false-positive: ${defects.size} defects found but none were expected
                    ${defects.joinToString("\n") { "\t* ${it.category}: ${it.reasoning}" }}
                    """.trimIndent()
                )
            }
        }
    }
}

data class TestCase(
    val screenshot: File,
    val appName: String,
    val prompt: String?,
    val shouldPass: Boolean,
    val index: Int,
)
