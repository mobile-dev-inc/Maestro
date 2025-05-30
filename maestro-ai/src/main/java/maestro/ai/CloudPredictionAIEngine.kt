package maestro.ai

import maestro.ai.cloud.Defect
import maestro.ai.Prediction

class CloudAIPredictionEngine(private val apiKey: String) : AIPredictionEngine {
    override suspend fun findDefects(screen: ByteArray, aiClient: AI): List<Defect> {
        return Prediction.findDefects(apiKey, aiClient, screen)
    }

    override suspend fun performAssertion(screen: ByteArray, aiClient: AI, assertion: String): Defect? {
        return Prediction.performAssertion(apiKey, aiClient, screen, assertion)
    }

    override suspend fun extractText(screen: ByteArray, aiClient: AI, query: String): String {
        return Prediction.extractText(apiKey, aiClient, query, screen)
    }
}
