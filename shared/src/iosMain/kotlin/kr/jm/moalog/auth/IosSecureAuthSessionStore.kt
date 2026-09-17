package kr.jm.moalog.auth

import kr.jm.moalog.core.contracts.UuidString
import cnames.structs.__CFData
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

@OptIn(ExperimentalForeignApi::class)
class IosSecureAuthSessionStore : SecureAuthSessionStore {
    private val mutex = Mutex()

    override suspend fun read(): StoredAuthSession? = mutex.withLock {
        val value = IosAuthKeychain.read() ?: return@withLock null
        runCatching { decode(value) }.getOrElse {
            IosAuthKeychain.delete()
            null
        }
    }

    override suspend fun write(session: StoredAuthSession) = mutex.withLock {
        check(IosAuthKeychain.write(encode(session))) { "인증 정보를 안전하게 저장하지 못했어요" }
    }

    override suspend fun clear() = mutex.withLock {
        check(IosAuthKeychain.delete()) { "인증 정보를 삭제하지 못했어요" }
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
}

@OptIn(ExperimentalForeignApi::class)
private object IosAuthKeychain {
    private const val SERVICE = "kr.jm.moalog.auth.session"
    private const val ACCOUNT = "current-session-v2"
    private const val FAILURE = -1

    fun read(): String? = memScoped {
        val result = alloc<COpaquePointerVar>()
        val status = withQuery(FAILURE) { query ->
            CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
            SecItemCopyMatching(query, result.ptr)
        }
        if (status != errSecSuccess) return@memScoped null
        val data = result.value?.reinterpret<__CFData>() ?: return@memScoped null
        try {
            data.asByteArray().decodeToString()
        } finally {
            CFRelease(data)
        }
    }

    fun write(value: String): Boolean {
        if (!delete()) return false
        val bytes = value.encodeToByteArray()
        val data = bytes.usePinned { pinned ->
            CFDataCreate(
                allocator = kCFAllocatorDefault,
                bytes = pinned.addressOf(0).reinterpret(),
                length = bytes.size.convert(),
            )
        } ?: return false
        return try {
            withQuery(FAILURE) { query ->
                CFDictionarySetValue(query, kSecValueData, data)
                CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                SecItemAdd(query, null)
            } == errSecSuccess
        } finally {
            CFRelease(data)
        }
    }

    fun delete(): Boolean {
        val status = withQuery(FAILURE) { query -> SecItemDelete(query) }
        return status == errSecSuccess || status == errSecItemNotFound
    }

    private inline fun <T> withQuery(fallback: T, block: (CFMutableDictionaryRef?) -> T): T {
        val query = CFDictionaryCreateMutable(kCFAllocatorDefault, 0, null, null) ?: return fallback
        val service = CFStringCreateWithCString(kCFAllocatorDefault, SERVICE, kCFStringEncodingUTF8)
        val account = CFStringCreateWithCString(kCFAllocatorDefault, ACCOUNT, kCFStringEncodingUTF8)
        if (service == null || account == null) {
            service?.let(::CFRelease)
            account?.let(::CFRelease)
            CFRelease(query)
            return fallback
        }
        return try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, service)
            CFDictionarySetValue(query, kSecAttrAccount, account)
            block(query)
        } finally {
            CFRelease(account)
            CFRelease(service)
            CFRelease(query)
        }
    }

    private fun CFDataRef.asByteArray(): ByteArray {
        val length = CFDataGetLength(this).toInt()
        if (length == 0) return ByteArray(0)
        return CFDataGetBytePtr(this)?.readBytes(length) ?: ByteArray(0)
    }
}
