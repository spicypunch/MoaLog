package kr.jm.moalog.appshell

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

internal class AndroidGoogleSignIn(private val activity: Activity) {
    suspend fun idToken(nonce: String): String {
        require(nonce.isNotBlank())
        val option = GetSignInWithGoogleOption.Builder(serverClientId())
            .setNonce(nonce)
            .build()
        val result = CredentialManager.create(activity).getCredential(
            context = activity,
            request = GetCredentialRequest.Builder().addCredentialOption(option).build(),
        )
        val credential = result.credential as? CustomCredential
            ?: error("Google 로그인 응답을 확인하지 못했어요")
        require(credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "Google 로그인 응답 형식이 올바르지 않아요"
        }
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }

    private fun serverClientId(): String {
        val info: ApplicationInfo = activity.packageManager.getApplicationInfo(
            activity.packageName,
            PackageManager.GET_META_DATA,
        )
        return info.metaData?.getString(GOOGLE_CLIENT_ID_META_DATA)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: error("Google 웹 클라이언트 ID를 설정해 주세요")
    }

    private companion object {
        const val GOOGLE_CLIENT_ID_META_DATA = "kr.jm.moalog.GOOGLE_WEB_CLIENT_ID"
    }
}
