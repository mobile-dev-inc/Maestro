package xcuitest.api

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object OkHttpClientInstance {

    fun get(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectionPool(ConnectionPool(225, 10, TimeUnit.MINUTES))
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(200, TimeUnit.SECONDS)
            .build()
    }
}