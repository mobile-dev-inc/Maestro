package maestro.ai

import maestro.ai.cloud.Defect
import maestro.ai.Prediction

class CloudAIPredictionEngine() : AIPredictionEngine {
    override suspend fun findDefects(screen: ByteArray, aiClient: AI): List<Defect> {
        return Prediction.findDefects(aiClient, screen)
    }

    override suspend fun performAssertion(screen: ByteArray, aiClient: AI, assertion: String): Defect? {
        return Prediction.performAssertion(aiClient, screen, assertion)
    }

    override suspend fun extractText(screen: ByteArray, aiClient: AI, query: String): String {
        return Prediction.extractText(aiClient, query, screen)
    }

    override suspend fun extractPoint(screen: ByteArray, aiClient: AI, query: String): String {
        return Prediction.extractPoint(aiClient, query, screen)
    }
}
