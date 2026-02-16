package maestro.ai

import maestro.ai.cloud.Defect
import maestro.ai.cloud.ExtractPointValidationResponse
import maestro.ai.cloud.ExtractPointWithReasoningResponse

interface AIPredictionEngine {
    suspend fun findDefects(screen: ByteArray, aiClient: AI): List<Defect>
    suspend fun performAssertion(screen: ByteArray, aiClient: AI, assertion: String): Defect?
    suspend fun extractText(screen: ByteArray, aiClient: AI, query: String): String
    suspend fun extractPoint(screen: ByteArray, aiClient: AI, query: String): String
    suspend fun extractPointWithReasoning(screen: ByteArray, aiClient: AI, query: String, viewHierarchy: String? = null): ExtractPointWithReasoningResponse?
    suspend fun extractPointRefined(croppedScreen: ByteArray, aiClient: AI, query: String, contextDescription: String): ExtractPointWithReasoningResponse?
    suspend fun validatePoint(screen: ByteArray, aiClient: AI, query: String, pointXPercent: Int, pointYPercent: Int): ExtractPointValidationResponse?
    suspend fun extractComponentPoint(componentImage: ByteArray, screen: ByteArray, aiClient: AI, viewHierarchy: String? = null): ExtractPointWithReasoningResponse?
}
