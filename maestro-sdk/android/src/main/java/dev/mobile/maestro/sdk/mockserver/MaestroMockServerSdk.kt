package dev.mobile.maestro.sdk.mockserver

import android.util.Base64
import dev.mobile.maestro.sdk.MaestroSdk
import dev.mobile.maestro.sdk.session.MaestroSession

class MaestroMockServerSdk internal constructor() {

    fun url(baseUrl: String): String {
        val sessionInfo = MaestroSession.getSessionInfo()

        val sessionPayload = generateSessionPayload(
            sessionId = sessionInfo.sessionId,
            targetUrl = baseUrl,
        )

        val url = "https://mock.mobile.dev/$sessionPayload"
        return if (baseUrl.endsWith("/")) {
            "$url/"
        } else {
            url
        }
    }

    private fun generateSessionPayload(
        sessionId: String,
        targetUrl: String,
    ): String {
        val payloadBuilder = StringBuilder()
        payloadBuilder
            .append((MaestroSdk.projectId ?: error("Project id is not set")) + "\n")
            .append(sessionId + "\n")
            .append(targetUrl + "\n")

        return String(
            Base64.encode(
                payloadBuilder.toString().toByteArray(),
                Base64.NO_WRAP
            )
        )
    }




}