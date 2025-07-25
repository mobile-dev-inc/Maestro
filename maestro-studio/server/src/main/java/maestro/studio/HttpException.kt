package maestro.studio

import io.ktor.http.HttpStatusCode

data class HttpException(
    val statusCode: HttpStatusCode,
    val errorMessage: String,
) : RuntimeException("$statusCode: $errorMessage")
