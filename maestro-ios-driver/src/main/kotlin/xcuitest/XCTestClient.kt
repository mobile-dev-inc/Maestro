package xcuitest

import okhttp3.HttpUrl

class XCTestClient(
    val host: String,
    val port: Int,
) {

    fun xctestAPIBuilder(pathSegment: String): HttpUrl.Builder {
        return HttpUrl.Builder()
            .scheme("http")
            .host(host)
            .addPathSegment(pathSegment)
            .port(port)
    }
}
