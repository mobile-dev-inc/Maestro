package maestro.ai.cloud

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import maestro.ai.AI
import maestro.ai.openai.OpenAI
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(OpenAIClient::class.java)


class OpenAIClient {

    private val askForDefectsSchema by lazy {
        readSchema("askForDefects")
    }

    private val askForDefectsAssertionSchema by lazy {
        readSchema("askForDefectsAssertion")
    }

    private val extractTextSchema by lazy {
        readSchema("extractText")
    }

    private val extractPointWithReasoningSchema by lazy {
        readSchema("extractPointWithReasoning")
    }

    private val extractPointValidationSchema by lazy {
        readSchema("extractPointValidation")
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

    suspend fun extractTextWithAi(
        aiClient: AI,
        query: String,
        screen: ByteArray,
    ): ExtractTextWithAiResponse {
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
            imageDetail = "high",
            images = listOf(screen),
            jsonSchema = json.parseToJsonElement(extractTextSchema).jsonObject,
        )

        val response = json.decodeFromString<ExtractTextWithAiResponse>(aiResponse.response)
        return response
    }

    suspend fun extractPointWithAi(
        aiClient: AI,
        query: String,
        screen: ByteArray,
        viewHierarchy: String? = null,
    ): ExtractPointWithReasoningResponse {
        val prompt = buildString {
            append("You are a UI element locator. Find the center coordinates of the UI element described by this query: $query")

            if (!viewHierarchy.isNullOrBlank()) {
                append(
                    """
                    |
                    |VIEW HIERARCHY (use this to help identify elements):
                    |$viewHierarchy
                    """.trimMargin("|")
                )
            }

            append(
                """
                |
                |INSTRUCTIONS:
                |1. First, reason step-by-step about which element matches the query.
                |2. Consider the view hierarchy data (if provided) to narrow down the element's location.
                |3. Identify the approximate bounding region of the element.
                |4. Determine the center point of that element.
                |
                |RULES:
                |* Coordinates are percentages of image width (x) and height (y), as integers 0-100.
                |* Format coordinates as "x%,y%" (e.g., "25%,40%").
                |* The bounding region should be formatted as "x1%,y1%,x2%,y2%" representing top-left and bottom-right corners.
                |* Provide a brief description of the element you identified.
                |
                |You must provide result as a valid JSON object matching this structure:
                |
                |  {
                |      "reasoning": "<step-by-step reasoning>",
                |      "description": "<brief description of the identified element>",
                |      "boundingRegion": "x1%,y1%,x2%,y2%",
                |      "text": "x%,y%"
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
            imageDetail = "high",
            images = listOf(screen),
            jsonSchema = json.parseToJsonElement(extractPointWithReasoningSchema).jsonObject,
        )
        logger.info("AI extractPointWithAi response: ${aiResponse.response}")
        return json.decodeFromString<ExtractPointWithReasoningResponse>(aiResponse.response)
    }

    suspend fun extractPointRefined(
        aiClient: AI,
        query: String,
        croppedScreen: ByteArray,
        contextDescription: String,
    ): ExtractPointWithReasoningResponse {
        val prompt = buildString {
            append("You are a UI element locator performing a REFINEMENT pass. ")
            append("You previously identified an element matching this query: $query")

            append(
                """
                |
                |CONTEXT: $contextDescription
                |
                |This image is a CROPPED/ZOOMED region of the full screenshot, centered around where the element was initially located.
                |Your job is to precisely locate the center of the element within THIS cropped image.
                |
                |INSTRUCTIONS:
                |1. Look carefully at this zoomed-in view.
                |2. Identify the exact element matching the query.
                |3. Provide precise center coordinates relative to THIS cropped image.
                |
                |RULES:
                |* Coordinates are percentages of THIS cropped image's width (x) and height (y), as integers 0-100.
                |* Format coordinates as "x%,y%" (e.g., "50%,50%").
                |* The bounding region should be formatted as "x1%,y1%,x2%,y2%".
                |
                |You must provide result as a valid JSON object matching this structure:
                |
                |  {
                |      "reasoning": "<reasoning about precise location in cropped view>",
                |      "description": "<brief description of the element>",
                |      "boundingRegion": "x1%,y1%,x2%,y2%",
                |      "text": "x%,y%"
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
            imageDetail = "high",
            images = listOf(croppedScreen),
            jsonSchema = json.parseToJsonElement(extractPointWithReasoningSchema).jsonObject,
        )
        logger.info("AI extractPointRefined response: ${aiResponse.response}")
        return json.decodeFromString<ExtractPointWithReasoningResponse>(aiResponse.response)
    }

    suspend fun validatePoint(
        aiClient: AI,
        query: String,
        screen: ByteArray,
        pointXPercent: Int,
        pointYPercent: Int,
    ): ExtractPointValidationResponse {
        val prompt = buildString {
            append("You are a UI element locator performing a VALIDATION pass. ")
            append("We believe the UI element matching this query is located at coordinates ${pointXPercent}%,${pointYPercent}% on the screen: $query")

            append(
                """
                |
                |INSTRUCTIONS:
                |1. Look at the screenshot and identify where ${pointXPercent}%,${pointYPercent}% falls.
                |2. Determine if this point is on or very near the center of the element described by the query.
                |3. If correct (within a reasonable tolerance), set isCorrect to true.
                |4. If incorrect, set isCorrect to false and provide corrected coordinates.
                |
                |RULES:
                |* Coordinates are percentages of image width (x) and height (y), as integers 0-100.
                |* Format correctedText as "x%,y%" (e.g., "25%,40%"). If isCorrect is true, set correctedText to "${pointXPercent}%,${pointYPercent}%".
                |
                |You must provide result as a valid JSON object matching this structure:
                |
                |  {
                |      "isCorrect": <true or false>,
                |      "correctedText": "x%,y%",
                |      "reasoning": "<reasoning about whether the point is correct>"
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
            imageDetail = "high",
            images = listOf(screen),
            jsonSchema = json.parseToJsonElement(extractPointValidationSchema).jsonObject,
        )
        logger.info("AI validatePoint response: ${aiResponse.response}")
        return json.decodeFromString<ExtractPointValidationResponse>(aiResponse.response)
    }

    suspend fun extractComponentPoint(
        aiClient: AI,
        componentImage: ByteArray,
        screen: ByteArray,
        viewHierarchy: String? = null,
    ): ExtractPointWithReasoningResponse {
        val prompt = buildString {
            append("You are a UI element locator. The FIRST image is a reference UI component (e.g., a Storybook snapshot). The SECOND image is the current device screen.")
            append(" Find where this component appears on the screen and return the center coordinates of the matching area.")

            if (!viewHierarchy.isNullOrBlank()) {
                append(
                    """
                    |
                    |VIEW HIERARCHY (use this to help identify elements):
                    |$viewHierarchy
                    """.trimMargin("|")
                )
            }

            append(
                """
                |
                |INSTRUCTIONS:
                |1. Study the reference component in the first image carefully - note its visual appearance, text, icons, layout.
                |2. Scan the device screenshot (second image) to find where this component appears.
                |3. Identify the approximate bounding region of the matching component on the screen.
                |4. Determine the center point of that component.
                |
                |RULES:
                |* Coordinates are percentages of the SECOND image's width (x) and height (y), as integers 0-100.
                |* Format coordinates as "x%,y%" (e.g., "25%,40%").
                |* The bounding region should be formatted as "x1%,y1%,x2%,y2%" representing top-left and bottom-right corners.
                |* Provide a brief description of the component you matched.
                |
                |You must provide result as a valid JSON object matching this structure:
                |
                |  {
                |      "reasoning": "<step-by-step reasoning>",
                |      "description": "<brief description of the matched component>",
                |      "boundingRegion": "x1%,y1%,x2%,y2%",
                |      "text": "x%,y%"
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
            imageDetail = "high",
            images = listOf(componentImage, screen),
            jsonSchema = json.parseToJsonElement(extractPointWithReasoningSchema).jsonObject,
        )
        logger.info("AI extractComponentPoint response: ${aiResponse.response}")
        return json.decodeFromString<ExtractPointWithReasoningResponse>(aiResponse.response)
    }

    suspend fun findDefects(
        aiClient: AI,
        screen: ByteArray,
        assertion: String? = null,
    ): FindDefectsResponse {

        if(assertion !== null) {
            return performAssertion(aiClient, screen, assertion)
        }

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
            //  generating never-ending stream of output.
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

        val aiResponse = aiClient.chatCompletion(
            prompt,
            model = aiClient.defaultModel,
            maxTokens = 4096,
            imageDetail = "high",
            images = listOf(screen),
            jsonSchema = json.parseToJsonElement(askForDefectsSchema).jsonObject,
        )

        val defects = json.decodeFromString<FindDefectsResponse>(aiResponse.response)
        return defects
    }

    private suspend fun performAssertion(
        aiClient: AI,
        screen: ByteArray,
        assertion: String,
    ): FindDefectsResponse{
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
                |* If the assertion is true, the "defects" list in the JSON output MUST be an empty list.
                |* If the assertion is false:
                |  * Your response MUST only include a single defect with category "assertion".
                |  * Provide detailed reasoning to explain why you think the assertion is false.
                """.trimMargin("|")
            )
        }

        val aiResponse = aiClient.chatCompletion(
            prompt,
            model = aiClient.defaultModel,
            maxTokens = 4096,
            imageDetail = "high",
            images = listOf(screen),
            jsonSchema = json.parseToJsonElement(askForDefectsAssertionSchema).jsonObject,
        )

        val response = json.decodeFromString<FindDefectsResponse>(aiResponse.response)
        return response
    }
}
