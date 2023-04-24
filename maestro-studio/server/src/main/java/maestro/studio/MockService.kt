package maestro.studio

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import maestro.mockserver.MockEvent
import maestro.mockserver.MockInteractor
import java.util.UUID

private data class GetMockDataResponse(
    val projectId: UUID?,
    val events: List<MockEvent>,
)

object MockService {

    fun routes(routing: Routing, interactor: MockInteractor) {
        routing.get("/api/mock-server/data") {
            val data = GetMockDataResponse(
                projectId = interactor.getProjectId(),
                events = interactor.getMockEvents()
            )

            val response = jacksonObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(data)
            call.respondText(response)
        }
    }

}