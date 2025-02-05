package maestro.ai

import maestro.ai.cloud.ApiClient
import maestro.ai.cloud.Defect

object Prediction {
    private val apiClient = ApiClient()

    suspend fun findDefects(
        apiKey: String,
        screen: ByteArray,
    ): List<Defect> {
        val response = apiClient.findDefects(apiKey, screen)

        return response.defects
    }

    suspend fun performAssertion(
        apiKey: String,
        screen: ByteArray,
        assertion: String,
    ): Defect? {
        val response = apiClient.findDefects(apiKey, screen, assertion)

        return response.defects.firstOrNull()
    }

    suspend fun extractText(
        apiKey: String,
        query: String,
        screen: ByteArray,
    ): String {
        val response = apiClient.extractTextWithAi(apiKey, query, screen)

        return response.text
    }
}