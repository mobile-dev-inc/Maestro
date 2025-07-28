package maestro.cli.util

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.request
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

object FileDownloader {

    fun downloadFile(
        url: String,
        destination: File
    ): Flow<DownloadResult> {
        val httpClient = HttpClient(CIO)
        return flow {
            with(httpClient) {
                val response = request {
                    url(url)
                    method = HttpMethod.Get
                }.call.response

                val contentLength = response.contentLength()
                    ?: error("Content length is null")

                val data = ByteArray(contentLength.toInt())

                val bodyChannel = response.bodyAsChannel()

                var offset = 0
                do {
                    val currentRead = bodyChannel
                        .readAvailable(data, offset, data.size)

                    offset += currentRead
                    val progress = offset / data.size.toFloat()
                    emit(DownloadResult.Progress(progress))
                } while (currentRead > 0)

                if (response.status.isSuccess()) {
                    destination.writeBytes(data)
                    emit(DownloadResult.Success)
                } else {
                    emit(DownloadResult.Error("Network error. Response code: ${response.status}"))
                }
            }
        }
    }

    sealed class DownloadResult {

        object Success : DownloadResult()

        data class Error(val message: String, val cause: Exception? = null) : DownloadResult()

        data class Progress(
            val progress: Float
        ) : DownloadResult()
    }

}