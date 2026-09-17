package kr.jm.moalog.server.auth.application

import kr.jm.moalog.core.contracts.AuthExchangeRequest
import kr.jm.moalog.core.contracts.AuthRefreshRequest
import kr.jm.moalog.core.contracts.AuthTokenResponse
import kr.jm.moalog.core.contracts.AuthenticatedUserDto
import kr.jm.moalog.core.contracts.UuidString
import kr.jm.moalog.server.auth.config.AuthProperties
import kr.jm.moalog.server.auth.domain.InvalidProviderTokenException
import kr.jm.moalog.server.auth.domain.ProviderIdTokenVerifier
import kr.jm.moalog.server.auth.infrastructure.AuthIdentityEntity
import kr.jm.moalog.server.auth.infrastructure.AuthIdentityRepository
import kr.jm.moalog.server.auth.infrastructure.AuthLoginChallengeRepository
import kr.jm.moalog.server.auth.infrastructure.AuthLoginChallengeEntity
import kr.jm.moalog.server.auth.infrastructure.AuthRefreshTokenEntity
import kr.jm.moalog.server.auth.infrastructure.AuthRefreshTokenRepository
import kr.jm.moalog.server.auth.infrastructure.AuthSessionEntity
import kr.jm.moalog.server.auth.infrastructure.AuthSessionRepository
import kr.jm.moalog.server.auth.infrastructure.RefreshTokenStatus
import kr.jm.moalog.server.auth.infrastructure.UserEntity
import kr.jm.moalog.server.auth.infrastructure.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID
import java.security.MessageDigest

@Service
class AuthService(
    private val providerVerifier: ProviderIdTokenVerifier,
    private val users: UserRepository,
    private val identities: AuthIdentityRepository,
    private val loginChallenges: AuthLoginChallengeRepository,
    private val sessions: AuthSessionRepository,
    private val refreshTokens: AuthRefreshTokenRepository,
    private val tokenService: AuthTokenService,
    private val properties: AuthProperties,
    private val clock: Clock,
) {
    @Transactional(noRollbackFor = [InvalidProviderCredentialException::class])
    fun exchange(request: AuthExchangeRequest): AuthTokenResponse {
        val now = clock.instant()
        val challenge = beginLoginAttempt(
            challengeId = UUID.fromString(request.challengeId.value),
            deviceId = request.device.deviceId.value,
            now = now,
        )
        val verified = try {
            providerVerifier.verify(request.provider, request.idToken)
        } catch (_: InvalidProviderTokenException) {
            throw InvalidProviderCredentialException()
        }
        if (verified.provider != request.provider) throw InvalidProviderCredentialException()
        consumeLoginChallenge(challenge, verified.nonce)
        val identity = identities.findByProviderAndProviderSubject(verified.provider, verified.subject)
            ?: run {
                val user = users.save(
                    UserEntity(
                        displayName = verified.displayName,
                        email = verified.email,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                identities.save(
                    AuthIdentityEntity(
                        user = user,
                        provider = verified.provider,
                        providerSubject = verified.subject,
                        providerEmail = verified.email,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
        val user = identity.user.apply {
            verified.displayName?.let { displayName = it }
            verified.email?.let { email = it }
            updatedAt = now
        }
        val session = sessions.save(
            AuthSessionEntity(
                user = user,
                deviceId = UUID.fromString(request.device.deviceId.value),
                platform = request.device.platform,
                appVersion = request.device.appVersion,
                createdAt = now,
                lastUsedAt = now,
                expiresAt = now.plus(properties.refreshTokenTtl),
            ),
        )
        return issueTokenPair(session)
    }

    private fun beginLoginAttempt(
        challengeId: UUID,
        deviceId: String,
        now: java.time.Instant,
    ): AuthLoginChallengeEntity {
        val challenge = loginChallenges.findByIdForUpdate(challengeId) ?: throw InvalidProviderCredentialException()
        if (challenge.usedAt != null || !challenge.expiresAt.isAfter(now)) throw InvalidProviderCredentialException()
        if (challenge.attemptCount >= properties.maxExchangeAttemptsPerChallenge) {
            throw InvalidProviderCredentialException()
        }
        challenge.attemptCount += 1
        val deviceMatches = MessageDigest.isEqual(
            challenge.deviceHash.toByteArray(Charsets.US_ASCII),
            tokenService.hashSecret(deviceId).toByteArray(Charsets.US_ASCII),
        )
        if (!deviceMatches) throw InvalidProviderCredentialException()
        return challenge
    }

    private fun consumeLoginChallenge(challenge: AuthLoginChallengeEntity, providerNonce: String) {
        val presentedHash = tokenService.hashSecret(providerNonce)
        val matches = MessageDigest.isEqual(
            challenge.nonceHash.toByteArray(Charsets.US_ASCII),
            presentedHash.toByteArray(Charsets.US_ASCII),
        )
        if (!matches) throw InvalidProviderCredentialException()
        challenge.usedAt = clock.instant()
    }

    @Transactional(
        noRollbackFor = [RefreshTokenReplayException::class, SessionReauthenticationRequiredException::class],
    )
    fun refresh(request: AuthRefreshRequest): AuthTokenResponse {
        val sessionId = tokenService.parseSessionId(request.refreshToken) ?: throw InvalidRefreshTokenException()
        val session = sessions.findByIdForUpdate(sessionId) ?: throw InvalidRefreshTokenException()
        val now = clock.instant()
        if (session.revokedAt != null || !session.expiresAt.isAfter(now)) throw InvalidRefreshTokenException()

        val hash = tokenService.hashRefreshToken(request.refreshToken)
        val stored = refreshTokens.findBySessionIdAndTokenHash(sessionId, hash)
            ?: throw InvalidRefreshTokenException()
        if (stored.status == RefreshTokenStatus.USED) {
            // A client can lose the successful response after this token was committed as used.
            // Permit a short, bounded replay window so it can obtain a fresh successor instead of
            // revoking the whole device session. Replays outside the grace window remain theft
            // signals and revoke the session.
            val usedAt = stored.usedAt
            if (usedAt != null && !now.isAfter(usedAt.plus(properties.refreshTokenReplayGrace))) {
                // Session-row locking serializes this replacement. Keep only one ACTIVE branch:
                // any successor from a lost earlier response becomes a grace-eligible predecessor.
                refreshTokens.findAllBySessionIdAndStatus(sessionId, RefreshTokenStatus.ACTIVE)
                    .forEach { active ->
                        active.status = RefreshTokenStatus.USED
                        active.usedAt = now
                    }
                session.lastUsedAt = now
                sessions.save(session)
                return issueTokenPair(session)
            }
            session.revokedAt = now
            sessions.save(session)
            throw RefreshTokenReplayException()
        }
        if (!stored.expiresAt.isAfter(now)) throw InvalidRefreshTokenException()
        if (!session.expiresAt.isAfter(now.plus(properties.accessToken.ttl))) {
            session.revokedAt = now
            throw SessionReauthenticationRequiredException()
        }

        stored.status = RefreshTokenStatus.USED
        stored.usedAt = now
        session.lastUsedAt = now
        refreshTokens.save(stored)
        sessions.save(session)
        return issueTokenPair(session)
    }

    @Transactional(readOnly = true)
    fun currentUser(userId: UUID): AuthenticatedUserDto {
        val user = users.findByIdAndDeletedAtIsNull(userId) ?: throw AuthenticatedUserNotFoundException()
        return AuthenticatedUserDto(
            userId = UuidString(user.id.toString()),
            displayName = user.displayName,
            email = user.email,
        )
    }

    @Transactional
    fun revokeSession(authenticatedUserId: UUID, sessionId: UUID) {
        val session = sessions.findByIdForUpdate(sessionId) ?: throw SessionAccessDeniedException()
        if (session.user.id != authenticatedUserId) throw SessionAccessDeniedException()
        if (session.revokedAt == null) session.revokedAt = clock.instant()
    }

    private fun issueTokenPair(session: AuthSessionEntity): AuthTokenResponse {
        val now = clock.instant()
        val refresh = tokenService.issueRefreshToken(session.id)
        refreshTokens.save(
            AuthRefreshTokenEntity(
                session = session,
                tokenHash = refresh.hash,
                status = RefreshTokenStatus.ACTIVE,
                createdAt = now,
                expiresAt = session.expiresAt,
            ),
        )
        val access = tokenService.issueAccessToken(session.user.id, session.id, session.expiresAt)
        return AuthTokenResponse(
            accessToken = access.value,
            accessTokenExpiresAtEpochSeconds = access.expiresAt.epochSecond,
            refreshToken = refresh.value,
            refreshTokenExpiresAtEpochSeconds = session.expiresAt.epochSecond,
            sessionId = UuidString(session.id.toString()),
        )
    }
}
