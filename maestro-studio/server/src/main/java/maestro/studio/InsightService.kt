package maestro.studio

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import maestro.studio.BannerMessage.*
import maestro.utils.Insight
import maestro.utils.CliInsights

object InsightService {

    private var currentInsight: Insight = Insight("", Insight.Level.NONE)

    fun routes(routing: Route) {
        registerInsightUpdateCallback()

        routing.get("/api/banner-message") {
            if (currentInsight.level != Insight.Level.NONE) {
                val bannerMessage = BannerMessage(
                    currentInsight.message,
                    Level.valueOf(currentInsight.level.toString()).toString().lowercase()
                )
                val response = jacksonObjectMapper()
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(bannerMessage)
                call.respondText(response)
            } else {
                val response = jacksonObjectMapper()
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(BannerMessage("", Level.NONE.toString().lowercase()))
                call.respondText(response)
            }
        }
    }

    private fun registerInsightUpdateCallback() {
        CliInsights.onInsightsUpdated {
            currentInsight = it
        }
    }
}

data class BannerMessage(val message: String, val level: String) {
    enum class Level {
        WARNING,
        NONE,
        INFO
    }
}