package maestro.ai

import maestro.ai.cloud.Defect
import maestro.ai.Prediction

class CloudAIPredictionEngine(private val apiKey: String) : AIPredictionEngine {
    override suspend fun findDefects(screen: ByteArray): List<Defect> {
        return Prediction.findDefects(apiKey, screen)
    }

    override suspend fun performAssertion(screen: ByteArray, assertion: String): Defect? {
        return Prediction.performAssertion(apiKey, screen, assertion)
    }

    override suspend fun extractText(screen: ByteArray, query: String): String {
        return Prediction.extractText(apiKey, query, screen)
    }
}
