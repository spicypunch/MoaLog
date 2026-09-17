package kr.jm.moalog.auth

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kr.jm.moalog.core.network.MoaLogNetworkConfig
import kr.jm.moalog.core.network.MoaLogTokenProvider
import kr.jm.moalog.core.network.createMoaLogHttpClient

class AndroidBackendRuntime(private val context: Context) {
    val appVersion: String by lazy {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        packageInfo.versionName?.takeIf(String::isNotBlank) ?: "0"
    }

    val networkConfig: MoaLogNetworkConfig by lazy {
        val applicationInfo = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA,
        )
        val baseUrl = applicationInfo.metaData?.getString(API_BASE_URL_META_DATA)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: error("AndroidManifest.xml에 $API_BASE_URL_META_DATA 값을 설정해 주세요")
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        MoaLogNetworkConfig(baseUrl, allowInsecureHttp = debuggable && baseUrl.startsWith("http://"))
    }

    fun publicClient(): HttpClient = createMoaLogHttpClient(OkHttp.create(), networkConfig)

    fun authenticatedClient(tokenProvider: MoaLogTokenProvider): HttpClient =
        createMoaLogHttpClient(OkHttp.create(), networkConfig, tokenProvider)

    private companion object {
        const val API_BASE_URL_META_DATA = "kr.jm.moalog.API_BASE_URL"
    }
}
