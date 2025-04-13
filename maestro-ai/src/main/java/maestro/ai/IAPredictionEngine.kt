package maestro.ai

import maestro.ai.cloud.Defect

interface AIPredictionEngine {
    suspend fun findDefects(screen: ByteArray): List<Defect>
    suspend fun performAssertion(screen: ByteArray, assertion: String): Defect?
    suspend fun extractText(screen: ByteArray, query: String): String
}
