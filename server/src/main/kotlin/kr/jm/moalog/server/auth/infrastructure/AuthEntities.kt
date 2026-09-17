package kr.jm.moalog.server.auth.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import kr.jm.moalog.core.contracts.AuthProvider
import kr.jm.moalog.core.contracts.ClientPlatform
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users", schema = "moalog")
class UserEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "display_name", length = 120)
    var displayName: String? = null,
    @Column(length = 320)
    var email: String? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,
)

@Entity
@Table(name = "auth_identities", schema = "moalog")
class AuthIdentityEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    var user: UserEntity = UserEntity(),
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var provider: AuthProvider = AuthProvider.GOOGLE,
    @Column(name = "provider_subject", nullable = false, length = 255)
    var providerSubject: String = "",
    @Column(name = "provider_email", length = 320)
    var providerEmail: String? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "auth_login_challenges", schema = "moalog")
class AuthLoginChallengeEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "nonce_hash", nullable = false, unique = true, length = 64)
    var nonceHash: String = "",
    @Column(name = "device_hash", nullable = false, length = 64)
    var deviceHash: String = "",
    @Column(name = "source_hash", nullable = false, length = 64)
    var sourceHash: String = "",
    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,
    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant = Instant.EPOCH,
    @Column(name = "used_at")
    var usedAt: Instant? = null,
)

@Entity
@Table(name = "auth_login_challenge_capacity", schema = "moalog")
class AuthLoginChallengeCapacityEntity(
    @Id
    var id: Short = 1,
)

@Entity
@Table(name = "auth_sessions", schema = "moalog")
class AuthSessionEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    var user: UserEntity = UserEntity(),
    @Column(name = "device_id", nullable = false)
    var deviceId: UUID = UUID.randomUUID(),
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var platform: ClientPlatform = ClientPlatform.ANDROID,
    @Column(name = "app_version", nullable = false, length = 80)
    var appVersion: String = "",
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,
    @Column(name = "last_used_at", nullable = false)
    var lastUsedAt: Instant = Instant.EPOCH,
    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant = Instant.EPOCH,
    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,
)

enum class RefreshTokenStatus { ACTIVE, USED }

@Entity
@Table(name = "auth_refresh_tokens", schema = "moalog")
class AuthRefreshTokenEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    var session: AuthSessionEntity = AuthSessionEntity(),
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    var tokenHash: String = "",
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: RefreshTokenStatus = RefreshTokenStatus.ACTIVE,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,
    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant = Instant.EPOCH,
    @Column(name = "used_at")
    var usedAt: Instant? = null,
)
