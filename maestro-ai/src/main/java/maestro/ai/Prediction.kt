package maestro.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import maestro.ai.openai.OpenAI

@Serializable
data class Defect(
    val category: String,
    val reasoning: String,
)

@Serializable
private data class AskForDefectsResponse(
    val defects: List<Defect>,
)

@Serializable
private data class ExtractTextResponse(
    val text: String?
)

object Prediction {

    private val askForDefectsSchema by lazy {
        readSchema("askForDefects")
    }

    private val extractTextSchema by lazy {
        readSchema("extractText")
    }

    /**
     * We use JSON mode/Structured Outputs to define the schema of the response we expect from the LLM.
     * - OpenAI: https://platform.openai.com/docs/guides/structured-outputs
     * - Gemini: https://ai.google.dev/gemini-api/docs/json-mode
     */
    private fun readSchema(name: String): String {
        val fileName = "/${name}_schema.json"
        val resourceStream = this::class.java.getResourceAsStream(fileName)
            ?: throw IllegalStateException("Could not find $fileName in resources")

        return resourceStream.bufferedReader().use { it.readText() }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val defectCategories = listOf(
        "localization" to "Inconsistent use of language, for example mixed English and Portuguese",
        "layout" to "Some UI elements are overlapping or are cropped",
    )

    private val allDefectCategories = defectCategories + listOf("assertion" to "The assertion is not true")

    suspend fun findDefects(
        aiClient: AI,
        screen: ByteArray,
        printPrompt: Boolean = false,
        printRawResponse: Boolean = false,
    ): List<Defect> {

        // List of failed attempts to not make up false positives:
        // |* If you don't see any defect, return "No defects found".
        // |* If you are sure there are no defects, return "No defects found".
        // |* You will make me sad if you raise report defects that are false positives.
        // |* Do not make up defects that are not present in the screenshot. It's fine if you don't find any defects.

        val prompt = buildString {

            appendLine(
                """
                You are a QA engineer performing quality assurance for a mobile application.
                Identify any defects in the provided screenshot.
                """.trimIndent()
            )

            append(
                """
                |
                |RULES:
                |* All defects you find must belong to one of the following categories:
                |${defectCategories.joinToString(separator = "\n") { "  * ${it.first}: ${it.second}" }}
                |* If you see defects, your response MUST only include defect name and detailed reasoning for each defect.
                |* Provide response as a list of JSON objects, each representing <category>:<reasoning>
                |* Do not raise false positives. Some example responses that have a high chance of being a false positive:
                |  * button is partially cropped at the bottom
                |  * button is not aligned horizontally/vertically within its container
                """.trimMargin("|")
            )

            // Claude doesn't have a JSON mode as of 21-08-2024
            //  https://docs.anthropic.com/en/docs/test-and-evaluate/strengthen-guardrails/increase-consistency
            //  We could do "if (aiClient is Claude)", but actually, this also helps with gpt-4o sometimes
            //  generatig never-ending stream of output.
            append(
                """
                |
                |* You must provide result as a valid JSON object, matching this structure:
                |
                |  {
                |      "defects": [
                |          {
                |              "category": "<defect category, string>",
                |              "reasoning": "<reasoning, string>"
                |          },
                |          {
                |              "category": "<defect category, string>",
                |              "reasoning": "<reasoning, string>"
                |          }
                |       ]
                |  }
                |
                |DO NOT output any other information in the JSON object.
                """.trimMargin("|")
            )

            appendLine("There are usually only a few defects in the screenshot. Don't generate tens of them.")
        }

        if (printPrompt) {
            println("--- PROMPT START ---")
            println(prompt)
            println("--- PROMPT END ---")
        }

        val aiResponse = aiClient.chatCompletion(
            prompt,
            model = aiClient.defaultModel,
            maxTokens = 4096,
            identifier = "find-defects",
            imageDetail = "high",
            images = listOf(screen),
            jsonSchema = if (aiClient is OpenAI) json.parseToJsonElement(askForDefectsSchema).jsonObject else null,
        )

        if (printRawResponse) {
            println("--- RAW RESPONSE START ---")
            println(aiResponse.response)
            println("--- RAW RESPONSE END ---")
        }

        val defects = json.decodeFromString<AskForDefectsResponse>(aiResponse.response)
        return defects.defects
    }

    suspend fun performAssertion(
        aiClient: AI,
        screen: ByteArray,
        assertion: String,
        printPrompt: Boolean = false,
        printRawResponse: Boolean = false,
    ): Defect? {
        val prompt = buildString {

            appendLine(
                """
                |You are a QA engineer performing quality assurance for a mobile application.
                |You are given a screenshot of the application and an assertion about the UI.
                |Your task is to identify if the following assertion is true:
                |
                |  "${assertion.removeSuffix("\n")}"
                |
                """.trimMargin("|")
            )

            append(
                """
                |
                |RULES:
                |* Provide response as a valid JSON, with structure described below.
                |* If the assertion is false, the list in the JSON output MUST be empty.
                |* If assertion is false:
                |  * Your response MUST only include a single defect with category "assertion".
                |  * Provide detailed reasoning to explain why you think the assertion is false.
                """.trimMargin("|")
            )

            // Claude doesn't have a JSON mode as of 21-08-2024
            //  https://docs.anthropic.com/en/docs/test-and-evaluate/strengthen-guardrails/increase-consistency
            //  We could do "if (aiClient is Claude)", but actually, this also helps with gpt-4o sometimes
            //  generatig never-ending stream of output.
            append(
                """
                |
                |* You must provide result as a valid JSON object, matching this structure:
                |
                |  {
                |      "defects": [
                |          {
                |              "category": "assertion",
                |              "reasoning": "<reasoning, string>"
                |          },
                |       ]
                |  }
                |
                |The "defects" array MUST contain at most a single JSON object.
                |DO NOT output any other information in the JSON object.
                """.trimMargin("|")
            )
        }

        if (printPrompt) {
            println("--- PROMPT START ---")
            println(prompt)
            println("--- PROMPT END ---")
        }

        val aiResponse = aiClient.chatCompletion(
            prompt,
            model = aiClient.defaultModel,
            maxTokens = 4096,
            identifier = "perform-assertion",
            imageDetail = "high",
            images = listOf(screen),
            jsonSchema = if (aiClient is OpenAI) json.parseToJsonElement(askForDefectsSchema).jsonObject else null,
        )

        if (printRawResponse) {
            println("--- RAW RESPONSE START ---")
            println(aiResponse.response)
            println("--- RAW RESPONSE END ---")
        }

        val response = json.decodeFromString<AskForDefectsResponse>(aiResponse.response)
        return response.defects.firstOrNull()
    }

    suspend fun extractText(
        aiClient: AI,
        screen: ByteArray,
        query: String,
    ): String {
        val prompt = buildString {
            append("What text on the screen matches the following query: $query")

            append(
                """
                |
                |RULES:
                |* Provide response as a valid JSON, with structure described below.
                """.trimMargin("|")
            )

            append(
                """
                |
                |* You must provide result as a valid JSON object, matching this structure:
                |
                |  {
                |      "text": <string>
                |  }
                |
                |DO NOT output any other information in the JSON object.
                """.trimMargin("|")
            )
        }

        val aiResponse = aiClient.chatCompletion(
            prompt,
            model = aiClient.defaultModel,
            maxTokens = 4096,
            identifier = "perform-assertion",
            imageDetail = "high",
            images = listOf(screen),
            jsonSchema = if (aiClient is OpenAI) json.parseToJsonElement(extractTextSchema).jsonObject else null,
        )

        val response = json.decodeFromString<ExtractTextResponse>(aiResponse.response)
        return response.text ?: ""
    }

}