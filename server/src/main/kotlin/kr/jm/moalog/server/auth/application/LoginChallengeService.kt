package kr.jm.moalog.server.auth.application

import kr.jm.moalog.core.contracts.AuthChallengeResponse
import kr.jm.moalog.core.contracts.UuidString
import kr.jm.moalog.server.auth.config.AuthProperties
import kr.jm.moalog.server.auth.infrastructure.AuthLoginChallengeEntity
import kr.jm.moalog.server.auth.infrastructure.AuthLoginChallengeRepository
import kr.jm.moalog.server.auth.infrastructure.AuthLoginChallengeCapacityRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.UUID

@Service
class LoginChallengeService(
    private val challenges: AuthLoginChallengeRepository,
    private val capacity: AuthLoginChallengeCapacityRepository,
    private val tokenService: AuthTokenService,
    private val properties: AuthProperties,
    private val clock: Clock,
) {
    private val secureRandom = SecureRandom()

    @Transactional
    fun issue(deviceId: UUID, sourceAddress: String): AuthChallengeResponse {
        val now = clock.instant()
        capacity.lockSingleton()
        challenges.deleteExpiredOrUsed(now)
        val deviceHash = tokenService.hashSecret(deviceId.toString())
        val sourceHash = tokenService.hashSecret(sourceAddress)
        val globalCapacityReached =
            challenges.countByExpiresAtAfterAndUsedAtIsNull(now) >= properties.maxActiveLoginChallenges
        val deviceCapacityReached = challenges.countByDeviceHashAndExpiresAtAfterAndUsedAtIsNull(deviceHash, now) >=
            properties.maxActiveLoginChallengesPerDevice
        val sourceCapacityReached = challenges.countBySourceHashAndExpiresAtAfterAndUsedAtIsNull(sourceHash, now) >=
            properties.maxActiveLoginChallengesPerSource
        if (globalCapacityReached || deviceCapacityReached || sourceCapacityReached) {
            throw LoginChallengeCapacityException()
        }
        val expiresAt = now.plus(properties.loginChallengeTtl)
        val nonce = ByteArray(32)
            .also(secureRandom::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val challenge = challenges.save(
            AuthLoginChallengeEntity(
                id = UUID.randomUUID(),
                nonceHash = tokenService.hashSecret(nonce),
                deviceHash = deviceHash,
                sourceHash = sourceHash,
                createdAt = now,
                expiresAt = expiresAt,
            ),
        )
        return AuthChallengeResponse(
            challengeId = UuidString(challenge.id.toString()),
            nonce = nonce,
            expiresAtEpochSeconds = expiresAt.epochSecond,
        )
    }
}
