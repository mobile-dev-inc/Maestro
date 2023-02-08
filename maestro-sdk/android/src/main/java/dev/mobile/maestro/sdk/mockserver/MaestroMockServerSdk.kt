package dev.mobile.maestro.sdk.mockserver

import android.util.Base64
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.mobile.maestro.sdk.MaestroSdk
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MaestroMockServerSdk internal constructor() {

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }

    private val jsonMapper by lazy {
        jacksonObjectMapper()
    }

    fun url(baseUrl: String): String {
        val sessionInfo = obtainSessionInfo()

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

    private fun obtainSessionInfo(): SessionInfo {
        val sessionFuture = Executors.newSingleThreadExecutor()
            .submit<SessionInfo> {
                querySessionInfoFromSdk()
            }

        val sessionInfo = try {
            sessionFuture.get()
        } catch (ignored: Exception) {
            SessionInfo(
                sessionId = UUID.randomUUID().toString(),
            )
        }

        return sessionInfo
    }

    private fun querySessionInfoFromSdk(): SessionInfo {
        val request = Request.Builder()
            .get()
            .url("http://localhost:7008/session")
            .build()

        return httpClient
            .newCall(request)
            .execute()
            .use {
                jsonMapper.readValue(
                    it.body?.bytes(),
                    SessionInfo::class.java
                )
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

    data class SessionInfo(
        val sessionId: String,
    )

}