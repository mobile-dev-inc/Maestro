package dev.mobile.maestro.sdk.session

import java.util.UUID

data class SessionInfo(
    val sessionId: String,
)

object MaestroSession {

    private const val maestroSessionIdPropName = "debug.maestro.sessionId"

    fun getSessionInfo(): SessionInfo {
        val sessionId = SystemProperties.read(maestroSessionIdPropName)

        val sessionUuid = try {
            UUID.fromString(sessionId)
        } catch (_: Exception) {
            UUID.randomUUID()
        }

        return SessionInfo(sessionId = sessionUuid.toString())

    }
}
