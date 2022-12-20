package maestro.studio

import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import maestro.Maestro

object MaestroStudio {

    fun start(port: Int, maestro: Maestro) {
        embeddedServer(Netty, port = port) {
            install(StatusPages) {
                exception<HttpException> { call, cause ->
                    call.respond(cause.statusCode, cause.errorMessage)
                }
            }
            routing {
                DeviceScreenService.routes(this, maestro)
                ReplService.routes(this, maestro)
                singlePageApplication {
                    useResources = true
                    filesPath = "web"
                    defaultPage = "index.html"
                }
            }
        }.start()
    }
}
