package kr.jm.moalog.server.auth.infrastructure

import jakarta.persistence.LockModeType
import kr.jm.moalog.core.contracts.AuthProvider
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.Modifying
import java.time.Instant
import java.util.UUID

interface UserRepository : JpaRepository<UserEntity, UUID> {
    fun findByIdAndDeletedAtIsNull(id: UUID): UserEntity?
}

interface AuthIdentityRepository : JpaRepository<AuthIdentityEntity, UUID> {
    fun findByProviderAndProviderSubject(provider: AuthProvider, providerSubject: String): AuthIdentityEntity?
    fun deleteAllByUserId(userId: UUID)
}

interface AuthLoginChallengeRepository : JpaRepository<AuthLoginChallengeEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AuthLoginChallengeEntity c where c.id = :id")
    fun findByIdForUpdate(id: UUID): AuthLoginChallengeEntity?

    @Modifying
    @Query("delete from AuthLoginChallengeEntity c where c.expiresAt <= :now or c.usedAt is not null")
    fun deleteExpiredOrUsed(now: Instant): Int

    fun countByExpiresAtAfterAndUsedAtIsNull(now: Instant): Long

    fun countByDeviceHashAndExpiresAtAfterAndUsedAtIsNull(deviceHash: String, now: Instant): Long

    fun countBySourceHashAndExpiresAtAfterAndUsedAtIsNull(sourceHash: String, now: Instant): Long
}

interface AuthLoginChallengeCapacityRepository : JpaRepository<AuthLoginChallengeCapacityEntity, Short> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AuthLoginChallengeCapacityEntity c where c.id = 1")
    fun lockSingleton(): AuthLoginChallengeCapacityEntity
}

interface AuthSessionRepository : JpaRepository<AuthSessionEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AuthSessionEntity s join fetch s.user where s.id = :id")
    fun findByIdForUpdate(id: UUID): AuthSessionEntity?

    fun existsByIdAndUserIdAndRevokedAtIsNullAndExpiresAtAfter(
        id: UUID,
        userId: UUID,
        now: java.time.Instant,
    ): Boolean

    fun findByIdAndUserIdAndRevokedAtIsNullAndExpiresAtAfter(
        id: UUID,
        userId: UUID,
        now: Instant,
    ): AuthSessionEntity?

    fun deleteAllByUserId(userId: UUID)
}

interface AuthRefreshTokenRepository : JpaRepository<AuthRefreshTokenEntity, UUID> {
    fun findBySessionIdAndTokenHash(sessionId: UUID, tokenHash: String): AuthRefreshTokenEntity?
    fun findAllBySessionIdAndStatus(
        sessionId: UUID,
        status: RefreshTokenStatus,
    ): List<AuthRefreshTokenEntity>
}
