package maestro.networkproxy

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.requestMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.http.Request
import com.github.tomakehurst.wiremock.http.RequestListener
import com.github.tomakehurst.wiremock.matching.MatchResult
import com.github.tomakehurst.wiremock.matching.MatchResult.exactMatch
import com.github.tomakehurst.wiremock.matching.MatchResult.noMatch
import maestro.networkproxy.NetworkProxyUtils.toMap
import maestro.networkproxy.yaml.YamlMappingRule
import maestro.networkproxy.yaml.YamlMappingRuleParser
import java.io.File
import java.nio.file.Paths

class NetworkProxy(
    val port: Int,
) : AutoCloseable {

    private var server: WireMockServer? = null

    private var ruleListener: ((MatchResult) -> Unit)? = null

    fun start(rules: List<YamlMappingRule>) {
        val certFile = unpackJksCertificates()

        server = WireMockServer(
            WireMockConfiguration.wireMockConfig()
                .port(port)
                .enableBrowserProxying(true)
                .caKeystorePath(certFile.absolutePath)
                .caKeystorePassword("maestro")
                .keystorePath(certFile.absolutePath)
                .keystorePassword("maestro")
                .keyManagerPassword("maestro")
        )

        replaceRules(rules)

        server?.start()
    }

    fun setListener(listener: RequestListener) {
        server?.addMockServiceRequestListener(listener)
    }

    fun setRuleListener(listener: ((MatchResult) -> Unit)?) {
        ruleListener = listener
    }

    fun isStarted() = server != null

    fun replaceRules(rules: List<YamlMappingRule>) {
        server?.resetMappings()

        rules.forEach { rule ->
            server?.stubFor(
                requestMatching { request ->
                    tryToMatch(rule, request)
                }.willReturn(
                    aResponse().buildResponse(rule)
                )
            )
        }
    }

    private fun ResponseDefinitionBuilder.buildResponse(rule: YamlMappingRule): ResponseDefinitionBuilder {
        withStatus(rule.response.status)
        rule.response.headers.forEach { (key, value) ->
            val values = value
                .split(",")
                .map { it.trim() }
                .toTypedArray()

            withHeader(key, *values)
        }

        if (rule.response.body != null && rule.response.bodyFile != null) {
            error("Cannot specify both body and bodyFile")
        }

        rule.response.body?.let {
            withBody(it)
        }
        rule.response.bodyFile?.let {
            val ruleDirectory = rule.ruleFilePath
                ?.let { ruleFilePath ->
                    File(ruleFilePath).parentFile.absolutePath
                }
                ?: "."

            val bodyFile = File(ruleDirectory, it)

            withBody(bodyFile.readText())
        }

        return this
    }

    private fun tryToMatch(
        rule: YamlMappingRule,
        request: Request
    ): com.github.tomakehurst.wiremock.matching.MatchResult? {
        val pathMatches = rule.path.toRegex().matches(request.absoluteUrl)
            || rule.path == request.absoluteUrl

        if (pathMatches && request.method.value() == rule.method.uppercase()) {
            val headersMatch = request.headers.toMap()
                .all { (key, value) ->
                    val ruleKey = rule.headers?.get(key)
                    ruleKey == value || ruleKey == null
                }

            if (!headersMatch) {
                return noMatch()
            }

            if (ruleListener != null) {
                ruleListener?.invoke(
                    MatchResult(
                        url = request.absoluteUrl,
                        rule = rule,
                        result = MatchResult.MatchType.EXACT_MATCH
                    )
                )
            }

            return exactMatch()
        } else {
            return noMatch()
        }
    }

    override fun close() {
        server?.stop()
    }

    data class MatchResult(
        val url: String,
        val rule: YamlMappingRule,
        val result: MatchType,
    ) {

        enum class MatchType {
            EXACT_MATCH,
            NO_MATCH,
        }

    }

    companion object {

        private val JKS_FILE = Paths
            .get(
                System.getProperty("user.home"),
                ".maestro",
                "maestro-cert.jks"
            )
            .toAbsolutePath()
            .toFile()

        private val PEM_FILE = Paths
            .get(
                System.getProperty("user.home"),
                ".maestro",
                "maestro-cert.pem"
            )
            .toAbsolutePath()
            .toFile()

        fun unpackJksCertificates(): File {
            NetworkProxy::class.java.classLoader.getResource("maestro-cert.jks")?.let {
                val file = JKS_FILE
                if (!file.exists()) {
                    file.writeBytes(it.readBytes())
                }
            }

            return JKS_FILE
        }

        fun unpackPemCertificates(): File {
            NetworkProxy::class.java.classLoader.getResource("maestro-cert.pem")?.let {
                val file = PEM_FILE
                if (!file.exists()) {
                    file.writeBytes(it.readBytes())
                }
            }

            return PEM_FILE
        }

    }

}

fun main() {
    val proxy = NetworkProxy(port = 8080)
    proxy.start(
        YamlMappingRuleParser.readRules(
            File("rules").toPath()
        )
    )

    while (!Thread.interrupted()) {
        Thread.sleep(100)
    }
}
