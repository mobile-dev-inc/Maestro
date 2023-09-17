package maestro.studio

import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.ApplicationReceivePipeline
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import maestro.Maestro
import maestro.mockserver.MockInteractor

object MaestroStudio {

    fun start(port: Int, maestro: Maestro?) {
        embeddedServer(Netty, port = port) {
            install(CORS) {
                allowHost("localhost:3000")
                allowHost("studio.mobile.dev", listOf("https"))
                allowHeader(HttpHeaders.ContentType)
            }
            install(StatusPages) {
                exception<HttpException> { call, cause ->
                    call.respond(cause.statusCode, cause.errorMessage)
                }
                exception { _, cause: Throwable ->
                    cause.printStackTrace()
                }
            }
            receivePipeline.intercept(ApplicationReceivePipeline.Before) {
                withContext(Dispatchers.IO) {
                    proceed()
                }
            }
            routing {
                if (maestro != null) {
                    DeviceService.routes(this, maestro)
                    InsightService.routes(this)
                    AuthService.routes(this)
                }
                MockService.routes(this, MockInteractor())
                singlePageApplication {
                    useResources = true
                    filesPath = "web"
                    defaultPage = "index.html"
                }
            }
        }.start()
    }
}
