package maestro.ai.claudecode

import maestro.ai.AIPredictionEngine
import maestro.ai.cloud.Defect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

private val logger = LoggerFactory.getLogger(ClaudeCodePredictionEngine::class.java)

class ClaudeCodePredictionEngine : AIPredictionEngine {

    private val json = Json { ignoreUnknownKeys = true }
    private val timeoutSeconds = 60L

    override suspend fun findDefects(screen: ByteArray): List<Defect> {
        val tempFile = writeTempScreenshot(screen)
        try {
            val prompt = buildString {
                append("Read the image at ${tempFile.absolutePath}. ")
                append("Analyze the screenshot for visual defects such as overlapping elements, ")
                append("cut-off text, misaligned components, or other UI issues. ")
                append("Respond with ONLY valid JSON (no markdown, no explanation): ")
                append("""{"defects": [{"category": "visual defect type", "reasoning": "brief explanation"}]}""")
                append(""" If no defects are found, respond with: {"defects": []}""")
            }

            val output = executeClaudeCommand(prompt)
            val jsonObj = parseJson(output)

            val defectsArray = jsonObj["defects"]?.jsonArray ?: return emptyList()
            return defectsArray.map { element ->
                val defectObj = element.jsonObject
                Defect(
                    category = defectObj["category"]?.jsonPrimitive?.content ?: "unknown",
                    reasoning = defectObj["reasoning"]?.jsonPrimitive?.content ?: "no reasoning provided",
                )
            }
        } finally {
            tempFile.delete()
        }
    }

    override suspend fun performAssertion(screen: ByteArray, assertion: String): Defect? {
        val tempFile = writeTempScreenshot(screen)
        try {
            val prompt = buildString {
                append("Read the image at ${tempFile.absolutePath}. ")
                append("Determine if this assertion is TRUE or FALSE: \"$assertion\". ")
                append("Respond with ONLY valid JSON (no markdown, no explanation): ")
                append("""{"passed": true, "category": "assertion", "reasoning": "brief explanation"}""")
            }

            val output = executeClaudeCommand(prompt)
            val jsonObj = parseJson(output)

            val passed = jsonObj["passed"]?.jsonPrimitive?.boolean ?: false
            if (passed) return null

            return Defect(
                category = jsonObj["category"]?.jsonPrimitive?.content ?: "assertion",
                reasoning = jsonObj["reasoning"]?.jsonPrimitive?.content ?: "Assertion failed: $assertion",
            )
        } finally {
            tempFile.delete()
        }
    }

    override suspend fun extractText(screen: ByteArray, query: String): String {
        val tempFile = writeTempScreenshot(screen)
        try {
            val prompt = buildString {
                append("Read the image at ${tempFile.absolutePath}. ")
                append("Extract the following from the screenshot: \"$query\". ")
                append("Respond with ONLY valid JSON (no markdown, no explanation): ")
                append("""{"text": "extracted text here"}""")
            }

            val output = executeClaudeCommand(prompt)
            val jsonObj = parseJson(output)

            return jsonObj["text"]?.jsonPrimitive?.content
                ?: throw RuntimeException("Claude Code did not return text in expected format")
        } finally {
            tempFile.delete()
        }
    }

    private fun writeTempScreenshot(screen: ByteArray): File {
        val tempFile = File.createTempFile("maestro-screenshot-", ".png")
        tempFile.writeBytes(screen)
        return tempFile
    }

    private fun executeClaudeCommand(prompt: String): String {
        val command = listOf(
            "claude",
            "-p", prompt,
            "--allowedTools", "Read",
            "--max-turns", "2",
            "--output-format", "text",
        )

        logger.info("Executing Claude Code CLI for AI assertion")

        val stderrFile = File.createTempFile("maestro-claude-err-", ".txt")
        try {
            val processBuilder = ProcessBuilder(command)
                .redirectError(stderrFile)
            // Suppress MCP servers to keep prompts clean (--bare skips OAuth so we avoid it)
            processBuilder.environment()["ENABLE_CLAUDEAI_MCP_SERVERS"] = "false"
            val process = processBuilder.start()

            val stdout = process.inputStream.bufferedReader().readText()

            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                throw RuntimeException("Claude Code CLI timed out after ${timeoutSeconds}s")
            }

            if (process.exitValue() != 0) {
                val stderr = stderrFile.readText()
                logger.error("Claude Code CLI failed (exit code ${process.exitValue()}): $stderr")
                throw RuntimeException("Claude Code CLI failed (exit code ${process.exitValue()}): $stderr")
            }

            return stdout.trim()
        } finally {
            stderrFile.delete()
        }
    }

    private fun parseJson(raw: String): JsonObject {
        val cleaned = extractJson(raw)
        return json.parseToJsonElement(cleaned).jsonObject
    }

    private fun extractJson(raw: String): String {
        // Handle markdown-wrapped JSON (```json ... ```)
        val codeBlockPattern = Regex("```(?:json)?\\s*\\n?(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL)
        val match = codeBlockPattern.find(raw)
        if (match != null) {
            return match.groupValues[1].trim()
        }
        return raw.trim()
    }

    companion object {
        fun isAvailable(): Boolean {
            return try {
                val process = ProcessBuilder("which", "claude")
                    .redirectErrorStream(true)
                    .start()
                process.inputStream.bufferedReader().readText()
                val completed = process.waitFor(5, TimeUnit.SECONDS)
                completed && process.exitValue() == 0
            } catch (e: Exception) {
                logger.debug("Claude Code CLI not available: ${e.message}")
                false
            }
        }
    }
}
