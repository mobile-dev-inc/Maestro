package dev.mobile.maestro.sdk.route

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

object HealthRoute {

    fun Route.healthRoute() {
        get("/health") {
            call.respond("OK")
        }
    }

}