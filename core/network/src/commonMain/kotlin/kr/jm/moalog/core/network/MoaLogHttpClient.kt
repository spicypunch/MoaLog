package kr.jm.moalog.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.plugins.plugin
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

data class MoaLogNetworkConfig(
    val baseUrl: String,
    val allowInsecureHttp: Boolean = false,
    val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    val connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    val socketTimeoutMillis: Long = DEFAULT_SOCKET_TIMEOUT_MILLIS,
) {
    init {
        val url = Url(baseUrl)
        require(url.protocol.name == "http" || url.protocol.name == "https") {
            "baseUrl must use HTTP or HTTPS"
        }
        require(url.protocol.name == "https" || allowInsecureHttp) {
            "HTTP baseUrl requires explicit local-development opt in"
        }
        require(url.host.isNotBlank()) { "baseUrl must include a host" }
        require(url.user.isNullOrEmpty() && url.password.isNullOrEmpty()) {
            "baseUrl must not include user info"
        }
        require(url.encodedPath.isEmpty() || url.encodedPath == "/") {
            "baseUrl must not include a path"
        }
        require(url.parameters.isEmpty() && !url.trailingQuery) { "baseUrl must not include a query" }
        require(url.fragment.isEmpty()) { "baseUrl must not include a fragment" }
        require(requestTimeoutMillis > 0) { "requestTimeoutMillis must be positive" }
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive" }
        require(socketTimeoutMillis > 0) { "socketTimeoutMillis must be positive" }
    }

    internal val normalizedBaseUrl: String = Url(baseUrl).toString().trimEnd('/') + "/"

    companion object {
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 15_000L
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000L
        const val DEFAULT_SOCKET_TIMEOUT_MILLIS = 15_000L
    }
}

fun createMoaLogHttpClient(
    engine: HttpClientEngine,
    config: MoaLogNetworkConfig,
    tokenProvider: MoaLogTokenProvider? = null,
): HttpClient {
    val apiOrigin = Url(config.normalizedBaseUrl)
    val client = HttpClient(engine) {
        expectSuccess = false

        install(DefaultRequest) {
            url(config.normalizedBaseUrl)
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
        }

        install(HttpTimeout) {
            requestTimeoutMillis = config.requestTimeoutMillis
            connectTimeoutMillis = config.connectTimeoutMillis
            socketTimeoutMillis = config.socketTimeoutMillis
        }

        if (tokenProvider != null) {
            install(Auth) {
                bearer {
                    loadTokens {
                        tokenProvider.loadTokens()?.toBearerTokens()
                    }
                    refreshTokens {
                        if (response.call.request.url.hasSameOrigin(apiOrigin)) {
                            tokenProvider.refreshTokens()?.toBearerTokens()
                        } else {
                            null
                        }
                    }
                    sendWithoutRequest { request ->
                        request.url.build().hasSameOrigin(apiOrigin)
                    }
                }
            }
        }

        install(ContentNegotiation) {
            json(MoaLogJson)
        }
    }
    client.plugin(HttpSend).intercept { request ->
        require(request.url.build().hasSameOrigin(apiOrigin)) {
            "MoaLog API client cannot send requests to a different origin"
        }
        execute(request)
    }
    return client
}

interface MoaLogTokenProvider {
    suspend fun loadTokens(): MoaLogNetworkTokens?

    /** Refresh through a client that does not use this provider to avoid recursive refresh calls. */
    suspend fun refreshTokens(): MoaLogNetworkTokens?
}

class MoaLogNetworkTokens(
    val accessToken: String,
    val refreshToken: String,
) {
    init {
        require(accessToken.isNotBlank()) { "accessToken must not be blank" }
        require(refreshToken.isNotBlank()) { "refreshToken must not be blank" }
    }

    override fun toString(): String =
        "MoaLogNetworkTokens(accessToken=<redacted>, refreshToken=<redacted>)"
}

private fun MoaLogNetworkTokens.toBearerTokens(): BearerTokens =
    BearerTokens(accessToken = accessToken, refreshToken = refreshToken)

private fun Url.hasSameOrigin(other: Url): Boolean =
    protocol == other.protocol && host == other.host && port == other.port

val MoaLogJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}
