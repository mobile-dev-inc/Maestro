package maestro.studio

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import maestro.Maestro

object MaestroStudio {

    fun start(port: Int, maestro: Maestro) {
        embeddedServer(Netty, port = port) {
            routing {
                get("/api/hierarchy") {
                    val hierarchy = jacksonObjectMapper()
                        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(maestro.viewHierarchy().root)
                    call.respondText(hierarchy)
                }
                singlePageApplication {
                    useResources = true
                    filesPath = "web"
                    defaultPage = "index.html"
                }
            }
        }.start()
    }
}
