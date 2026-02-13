package maestro.ai

import maestro.ai.cloud.Defect
import maestro.ai.cloud.ExtractPointValidationResponse
import maestro.ai.cloud.ExtractPointWithReasoningResponse
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

    @Deprecated("Use extractPointWithReasoning for improved accuracy", replaceWith = ReplaceWith("extractPointWithReasoning(aiClient, query, screen)"))
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

    suspend fun extractPointWithReasoning(
        aiClient: AI?,
        query: String,
        screen: ByteArray,
        viewHierarchy: String? = null,
    ): ExtractPointWithReasoningResponse? {
        if(aiClient !== null){
            return openApi.extractPointWithAi(aiClient, query, screen, viewHierarchy)
        }
        return null
    }

    suspend fun extractPointRefined(
        aiClient: AI?,
        query: String,
        croppedScreen: ByteArray,
        contextDescription: String,
    ): ExtractPointWithReasoningResponse? {
        if(aiClient !== null){
            return openApi.extractPointRefined(aiClient, query, croppedScreen, contextDescription)
        }
        return null
    }

    suspend fun extractComponentPoint(
        aiClient: AI?,
        componentImage: ByteArray,
        screen: ByteArray,
        viewHierarchy: String? = null,
    ): ExtractPointWithReasoningResponse? {
        if(aiClient !== null){
            return openApi.extractComponentPoint(aiClient, componentImage, screen, viewHierarchy)
        }
        return null
    }

    suspend fun validatePoint(
        aiClient: AI?,
        query: String,
        screen: ByteArray,
        pointXPercent: Int,
        pointYPercent: Int,
    ): ExtractPointValidationResponse? {
        if(aiClient !== null){
            return openApi.validatePoint(aiClient, query, screen, pointXPercent, pointYPercent)
        }
        return null
    }
}
