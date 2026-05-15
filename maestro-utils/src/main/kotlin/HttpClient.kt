package maestro.utils

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
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
        callTimeout: Duration? = null,
        interceptors: List<Interceptor> = emptyList(),
        networkInterceptors: List<Interceptor> = emptyList(),
        protocols: List<Protocol> = listOf(Protocol.HTTP_1_1),
        metrics: Metrics = MetricsProvider.getInstance(),
        retryOnTransientFailure: Boolean = false,
        retryMaxAttempts: Int = 3,
        retryInitialBackoff: Duration = 500.milliseconds,
        retryMaxBackoff: Duration = 2.seconds,
    ): OkHttpClient {
        val effectiveCallTimeout = callTimeout ?: maxOf(60.seconds, readTimeout)
        var b = OkHttpClient.Builder()
            .eventListenerFactory(MetricsEventListener.Factory(metrics, name))
            .connectTimeout(connectTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .writeTimeout(writeTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .callTimeout(effectiveCallTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
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

        if (retryOnTransientFailure) {
            b = b.addInterceptor(
                TransientFailureRetryInterceptor(
                    clientName = name,
                    maxAttempts = retryMaxAttempts,
                    initialBackoff = retryInitialBackoff,
                    maxBackoff = retryMaxBackoff,
                    metrics = metrics,
                )
            )
        }

        b = networkInterceptors.map { b.addNetworkInterceptor(it) }.lastOrNull() ?: b
        b = interceptors.map { b.addInterceptor(it) }.lastOrNull() ?: b

        return b.build()
    }
}

/**
 * Retries HTTP calls on transient failures:
 *  - [IOException] thrown by `chain.proceed()` (transport-level: connect refused,
 *    "no route to host", socket reset, etc.).
 *  - HTTP 5xx responses from the server.
 *
 * 4xx responses are deterministic client errors and pass through unchanged.
 *
 * Applied as an application interceptor (not network) so OkHttp's own
 * connection-failure recovery runs first and retries are visible to higher-level
 * callers as a single logical call.
 */
internal class TransientFailureRetryInterceptor(
    private val clientName: String,
    private val maxAttempts: Int,
    private val initialBackoff: Duration,
    private val maxBackoff: Duration,
    private val metrics: Metrics,
    private val sleeper: (Long) -> Unit = Thread::sleep,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var lastIo: IOException? = null
        var lastResponse: Response? = null

        for (attempt in 1..maxAttempts) {
            lastResponse?.close()
            lastResponse = null

            try {
                val response = chain.proceed(request)
                if (response.code < 500 || attempt == maxAttempts) {
                    return response
                }
                recordRetry(request.url.host, request.method, "http_${response.code}")
                lastResponse = response
            } catch (e: IOException) {
                if (attempt == maxAttempts) {
                    throw e
                }
                recordRetry(request.url.host, request.method, e.javaClass.simpleName)
                lastIo = e
            }

            sleeper(backoffMillis(attempt))
        }

        // Unreachable: the loop above returns on the last attempt or throws.
        return lastResponse ?: throw (lastIo ?: IOException("$clientName: retry loop exited unexpectedly"))
    }

    private fun recordRetry(host: String, method: String, kind: String) {
        metrics.counter(
            "http.client.retries",
            mapOf("client" to clientName, "url" to host, "method" to method, "kind" to kind)
        ).increment()
    }

    private fun backoffMillis(attempt: Int): Long {
        // attempt is 1-based; first backoff is initialBackoff, doubling per attempt up to maxBackoff
        val scaled = initialBackoff.inWholeMilliseconds shl (attempt - 1).coerceAtMost(30)
        return scaled.coerceAtMost(maxBackoff.inWholeMilliseconds)
    }
}
