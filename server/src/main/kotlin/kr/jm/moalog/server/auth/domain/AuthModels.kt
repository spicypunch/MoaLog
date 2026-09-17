package kr.jm.moalog.server.auth.domain

import kr.jm.moalog.core.contracts.AuthProvider

data class VerifiedProviderIdentity(
    val provider: AuthProvider,
    val subject: String,
    val email: String?,
    val displayName: String?,
    val nonce: String,
) {
    init {
        require(subject.isNotBlank())
        require(nonce.isNotBlank())
    }
}

fun interface ProviderIdTokenVerifier {
    fun verify(provider: AuthProvider, idToken: String): VerifiedProviderIdentity
}

class InvalidProviderTokenException : RuntimeException()
