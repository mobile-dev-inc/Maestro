package dev.mobile.maestro.sdk

import dev.mobile.maestro.sdk.model.SessionInfo
import dev.mobile.maestro.sdk.route.HealthRoute.healthRoute
import dev.mobile.maestro.sdk.route.SessionInfoRoute.sessionInfoRoute
import io.ktor.serialization.gson.gson
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing

object SdkService {

    lateinit var sessionInfo: SessionInfo
        private set

    fun initSession(
        sessionId: String,
    ) {
        sessionInfo = SessionInfo(
            sessionId = sessionId,
        )
    }

    fun startService() {
        embeddedServer(CIO, port = 7008) {
            install(ContentNegotiation) {
                gson()
            }

            routing {
                healthRoute()
                sessionInfoRoute()
            }
        }.start(wait = false)
    }

}

fun main() {
    SdkService.initSession("session-id")
    SdkService.startService()

    Thread.currentThread().join()
}
