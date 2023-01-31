package dev.mobile.maestro.sdk.route

import dev.mobile.maestro.sdk.SdkService
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

object SessionInfoRoute {

    fun Route.sessionInfoRoute() {
        get("/session") {
            call.respond(SdkService.sessionInfo)
        }
    }

}