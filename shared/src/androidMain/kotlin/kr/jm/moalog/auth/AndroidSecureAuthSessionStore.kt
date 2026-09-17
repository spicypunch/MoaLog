package kr.jm.moalog.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kr.jm.moalog.core.contracts.UuidString
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidSecureAuthSessionStore(context: Context) : SecureAuthSessionStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    override suspend fun read(): StoredAuthSession? = mutex.withLock {
        val encrypted = preferences.getString(SESSION_KEY, null) ?: return@withLock null
        runCatching { decode(AndroidAuthCipher.decrypt(encrypted)) }
            .getOrElse {
                preferences.edit().remove(SESSION_KEY).apply()
                null
            }
    }

    override suspend fun write(session: StoredAuthSession) = mutex.withLock {
        val encrypted = AndroidAuthCipher.encrypt(encode(session))
        check(preferences.edit().putString(SESSION_KEY, encrypted).commit()) {
            "인증 정보를 안전하게 저장하지 못했어요"
        }
    }

    override suspend fun clear() = mutex.withLock {
        check(preferences.edit().remove(SESSION_KEY).commit()) {
            "인증 정보를 삭제하지 못했어요"
        }
    }

    private fun encode(value: StoredAuthSession): String = listOf(
        value.accessToken,
        value.accessTokenExpiresAtEpochSeconds.toString(),
        value.refreshToken,
        value.refreshTokenExpiresAtEpochSeconds.toString(),
        value.sessionId.value,
        value.deviceId.value,
    ).joinToString("\n")

    private fun decode(value: String): StoredAuthSession {
        val fields = value.split('\n')
        require(fields.size == 6)
        return StoredAuthSession(
            accessToken = fields[0],
            accessTokenExpiresAtEpochSeconds = fields[1].toLong(),
            refreshToken = fields[2],
            refreshTokenExpiresAtEpochSeconds = fields[3].toLong(),
            sessionId = UuidString(fields[4]),
            deviceId = UuidString(fields[5]),
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "moalog_secure_auth"
        const val SESSION_KEY = "encrypted_session_v2"
    }
}

private object AndroidAuthCipher {
    private const val KEY_STORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "moalog.auth.session.aes.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BITS = 128

    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
        val ciphertext = cipher.doFinal(plaintext.encodeToByteArray())
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    fun decrypt(payload: String): String {
        val separator = payload.indexOf(':')
        require(separator in 1 until payload.lastIndex)
        val iv = Base64.decode(payload.substring(0, separator), Base64.NO_WRAP)
        val ciphertext = Base64.decode(payload.substring(separator + 1), Base64.NO_WRAP)
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            doFinal(ciphertext).decodeToString()
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEY_STORE).apply { load(null) }
        val existing = (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }
}
