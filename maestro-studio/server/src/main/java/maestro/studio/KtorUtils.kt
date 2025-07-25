package maestro.studio

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

suspend inline fun <reified T> ApplicationCall.parseBody(): T {
    return try {
        receiveStream().use { body ->
            withContext(Dispatchers.IO) {
                jacksonObjectMapper().readValue(body, T::class.java)
            }
        }
    } catch (e: IOException) {
        throw HttpException(HttpStatusCode.BadRequest, "Failed to parse request body")
    }
}
