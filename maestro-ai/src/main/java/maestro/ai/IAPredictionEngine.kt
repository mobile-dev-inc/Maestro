package maestro.ai

import maestro.ai.cloud.Defect

interface AIPredictionEngine {
    suspend fun findDefects(screen: ByteArray, aiClient: AI): List<Defect>
    suspend fun performAssertion(screen: ByteArray, aiClient: AI, assertion: String): Defect?
    suspend fun extractText(screen: ByteArray, aiClient: AI, query: String): String
    suspend fun extractPoint(screen: ByteArray, aiClient: AI, query: String): String
}
