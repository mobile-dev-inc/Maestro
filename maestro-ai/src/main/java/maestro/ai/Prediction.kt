package maestro.ai

import maestro.ai.cloud.ApiClient
import maestro.ai.cloud.Defect
import maestro.ai.cloud.OpenAIClient

object Prediction {
    private val openApi = OpenAIClient()
    private val cloud = ApiClient()

    suspend fun findDefects(
        apiKey: String? = "",
        aiClient: AI?,
        screen: ByteArray,
    ): List<Defect> {
        if(aiClient !== null){
            val response = openApi.findDefects(aiClient, screen)
            return response.defects
        } else if(apiKey !== null){
            val response = cloud.findDefects(apiKey, screen)
            return response.defects
        }
        return listOf()
    }

    suspend fun performAssertion(
        apiKey: String? = "",
        aiClient: AI?,
        screen: ByteArray,
        assertion: String,
    ): Defect? {
        if(aiClient !== null){
            val response = openApi.findDefects(aiClient, screen, assertion)
            return response.defects.firstOrNull()
        } else if(apiKey !== null){
            val response = cloud.findDefects(apiKey, screen, assertion)
            return response.defects.firstOrNull()
        }
        return null
    }

    suspend fun extractText(
        apiKey: String? = "",
        aiClient: AI?,
        query: String,
        screen: ByteArray,
    ): String {
        if(aiClient !== null){
            val response = openApi.extractTextWithAi(aiClient, query, screen)
            return response.text
        } else if(apiKey !== null){
            val response = cloud.extractTextWithAi(apiKey, query, screen)
            return response.text
        }
        return ""
    }
}
