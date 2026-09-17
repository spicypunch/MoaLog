package kr.jm.moalog.server.household.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import kr.jm.moalog.core.contracts.HouseholdInvitationStatus
import kr.jm.moalog.core.contracts.HouseholdRole
import kr.jm.moalog.core.contracts.LedgerCurrency
import kr.jm.moalog.server.auth.infrastructure.UserEntity
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "households", schema = "moalog")
class HouseholdEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(nullable = false, length = 120)
    var name: String = "",
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    var currency: LedgerCurrency = LedgerCurrency.KRW,
    @Column(name = "base_year", nullable = false)
    var baseYear: Int = 1900,
    @Column(nullable = false)
    var version: Long = 1,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "ledger_members", schema = "moalog")
class LedgerMemberEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    var household: HouseholdEntity = HouseholdEntity(),
    @Column(name = "member_order", nullable = false)
    var order: Int = 0,
    @Column(name = "display_name", nullable = false, length = 80)
    var displayName: String = "",
    @Column(nullable = false)
    var version: Long = 1,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "household_memberships", schema = "moalog")
class HouseholdMembershipEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    var household: HouseholdEntity = HouseholdEntity(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    var user: UserEntity = UserEntity(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ledger_member_id", nullable = false)
    var ledgerMember: LedgerMemberEntity = LedgerMemberEntity(),
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var role: HouseholdRole = HouseholdRole.MEMBER,
    @Column(nullable = false)
    var version: Long = 1,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "annual_savings_targets", schema = "moalog")
class AnnualSavingsTargetEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    var household: HouseholdEntity = HouseholdEntity(),
    @Column(name = "target_year", nullable = false)
    var year: Int = 1900,
    @Column(name = "amount_won", nullable = false)
    var amountWon: Long = 0,
    @Column(nullable = false)
    var version: Long = 1,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "household_invitations", schema = "moalog")
class HouseholdInvitationEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    var household: HouseholdEntity = HouseholdEntity(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_ledger_member_id", nullable = false)
    var targetLedgerMember: LedgerMemberEntity = LedgerMemberEntity(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    var createdByUser: UserEntity = UserEntity(),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accepted_by_user_id")
    var acceptedByUser: UserEntity? = null,
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    var tokenHash: String = "",
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: HouseholdInvitationStatus = HouseholdInvitationStatus.ACTIVE,
    @Column(nullable = false)
    var version: Long = 1,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,
    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant = Instant.EPOCH,
    @Column(name = "accepted_at")
    var acceptedAt: Instant? = null,
    @Column(name = "cancelled_at")
    var cancelledAt: Instant? = null,
)
