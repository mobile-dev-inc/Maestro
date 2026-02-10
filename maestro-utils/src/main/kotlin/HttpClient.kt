package maestro.utils

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.net.InetSocketAddress
import java.net.Proxy

class MetricsEventListener(
    private val registry: Metrics,
    private val clientName: String,
) : EventListener() {

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException
    ) {
        registry.counter(
            "http.client.errors",
            mapOf(
                "client" to clientName,
                "method" to call.request().method,
                "url" to call.request().url.host,
                "exception" to ioe.javaClass.simpleName,
                "kind" to "connect"
            )
        ).increment()
    }

    override fun callFailed(call: Call, ioe: IOException) {
        registry.counter(
            "http.client.errors",
            mapOf(
                "client" to clientName,
                "method" to call.request().method,
                "url" to call.request().url.host,
                "exception" to ioe.javaClass.simpleName,
                "kind" to "call"
            )
        ).increment()
    }

    class Factory(
        private val registry: Metrics,
        private val clientName: String,
    ) : EventListener.Factory {
        override fun create(call: Call): EventListener =
            MetricsEventListener(registry, clientName)
    }
}

// utility object to build http clients with metrics
object HttpClient {
    fun build(
        name: String,
        connectTimeout: Duration = 10.seconds,
        readTimeout: Duration = 10.seconds,
        writeTimeout: Duration = 10.seconds,
        callTimeout: Duration = 60.seconds,
        interceptors: List<Interceptor> = emptyList(),
        networkInterceptors: List<Interceptor> = emptyList(),
        protocols: List<Protocol> = listOf(Protocol.HTTP_1_1),
        metrics: Metrics = MetricsProvider.getInstance()
    ): OkHttpClient {
        var b = OkHttpClient.Builder()
            .eventListenerFactory(MetricsEventListener.Factory(metrics, name))
            .connectTimeout(connectTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .writeTimeout(writeTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .callTimeout(callTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .addNetworkInterceptor(Interceptor { chain ->
                val start = System.currentTimeMillis()
                val response = chain.proceed(chain.request())
                val duration = System.currentTimeMillis() - start
                metrics.timer(
                    "http.client.request.duration",
                    mapOf(
                        "client" to name,
                        "method" to chain.request().method,
                        "url" to chain.request().url.host,
                        "status" to response.code.toString()
                    )
                ).record(duration, TimeUnit.MILLISECONDS)
                response
            })
            .protocols(protocols)

        b = networkInterceptors.map { b.addNetworkInterceptor(it) }.lastOrNull() ?: b
        b = interceptors.map { b.addInterceptor(it) }.lastOrNull() ?: b

        return b.build()
    }
}
