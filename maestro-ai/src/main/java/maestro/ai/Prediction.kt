package maestro.ai

import maestro.ai.cloud.Defect
import maestro.ai.cloud.OpenAIClient

object Prediction {
    private val openApi = OpenAIClient()

    suspend fun findDefects(
        aiClient: AI?,
        screen: ByteArray,
    ): List<Defect> {
        if(aiClient !== null){
            val response = openApi.findDefects(aiClient, screen)
            return response.defects
        }
        return listOf()
    }

    suspend fun performAssertion(
        aiClient: AI?,
        screen: ByteArray,
        assertion: String,
    ): Defect? {
        if(aiClient !== null){
            val response = openApi.findDefects(aiClient, screen, assertion)
            return response.defects.firstOrNull()
        }
        return null
    }

    suspend fun extractText(
        aiClient: AI?,
        query: String,
        screen: ByteArray,
    ): String {
        if(aiClient !== null){
            val response = openApi.extractTextWithAi(aiClient, query, screen)
            return response.text
        }
        return ""
    }

    suspend fun extractPoint(
        aiClient: AI?,
        query: String,
        screen: ByteArray,
    ): String {
        if(aiClient !== null){
            val response = openApi.extractPointWithAi(aiClient, query, screen)
            return response.text
        }
        return ""
    }
}
