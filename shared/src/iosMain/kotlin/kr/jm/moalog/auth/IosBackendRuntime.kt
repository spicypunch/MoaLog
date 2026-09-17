package kr.jm.moalog.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kr.jm.moalog.core.network.MoaLogNetworkConfig
import kr.jm.moalog.core.network.MoaLogTokenProvider
import kr.jm.moalog.core.network.createMoaLogHttpClient
import platform.Foundation.NSBundle

class IosBackendRuntime {
    val appVersion: String
        get() = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "0"

    val networkConfig: MoaLogNetworkConfig by lazy {
        val baseUrl = (NSBundle.mainBundle.objectForInfoDictionaryKey(API_BASE_URL_INFO_KEY) as? String)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: error("Info.plist에 $API_BASE_URL_INFO_KEY 값을 설정해 주세요")
        val allowInsecure = when (
            NSBundle.mainBundle.objectForInfoDictionaryKey(ALLOW_INSECURE_INFO_KEY)?.toString()?.lowercase()
        ) {
            "true", "yes", "1" -> true
            else -> false
        }
        MoaLogNetworkConfig(baseUrl, allowInsecureHttp = allowInsecure)
    }

    fun publicClient(): HttpClient = createMoaLogHttpClient(Darwin.create(), networkConfig)

    fun authenticatedClient(tokenProvider: MoaLogTokenProvider): HttpClient =
        createMoaLogHttpClient(Darwin.create(), networkConfig, tokenProvider)

    private companion object {
        const val API_BASE_URL_INFO_KEY = "MoaLogAPIBaseURL"
        const val ALLOW_INSECURE_INFO_KEY = "MoaLogAPIAllowInsecureHTTP"
    }
}
