package kr.jm.moalog.server.household.infrastructure

import jakarta.persistence.LockModeType
import kr.jm.moalog.core.contracts.HouseholdInvitationStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface HouseholdRepository : JpaRepository<HouseholdEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from HouseholdEntity h where h.id = :id")
    fun findByIdForUpdate(id: UUID): HouseholdEntity?

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from HouseholdEntity h where h.id = :id")
    fun deleteHouseholdById(id: UUID): Int
}

interface LedgerMemberRepository : JpaRepository<LedgerMemberEntity, UUID> {
    fun findAllByHouseholdIdOrderByOrderAsc(householdId: UUID): List<LedgerMemberEntity>
    fun findByIdAndHouseholdId(id: UUID, householdId: UUID): LedgerMemberEntity?
}

interface HouseholdMembershipRepository : JpaRepository<HouseholdMembershipEntity, UUID> {
    @Query(
        "select m from HouseholdMembershipEntity m " +
            "join fetch m.household h join fetch m.ledgerMember where m.user.id = :userId order by h.updatedAt desc",
    )
    fun findAllForUser(userId: UUID): List<HouseholdMembershipEntity>

    @Query(
        "select m from HouseholdMembershipEntity m join fetch m.household join fetch m.ledgerMember " +
            "where m.household.id = :householdId and m.user.id = :userId",
    )
    fun findByHouseholdIdAndUserId(householdId: UUID, userId: UUID): HouseholdMembershipEntity?

    @Query(
        "select m from HouseholdMembershipEntity m join fetch m.user join fetch m.ledgerMember " +
            "where m.household.id = :householdId",
    )
    fun findAllByHouseholdId(householdId: UUID): List<HouseholdMembershipEntity>

    fun findByLedgerMemberId(ledgerMemberId: UUID): HouseholdMembershipEntity?
    fun existsByHouseholdIdAndUserId(householdId: UUID, userId: UUID): Boolean
}

interface AnnualSavingsTargetRepository : JpaRepository<AnnualSavingsTargetEntity, UUID> {
    fun findAllByHouseholdIdOrderByYearAsc(householdId: UUID): List<AnnualSavingsTargetEntity>

    @Modifying
    @Query("delete from AnnualSavingsTargetEntity t where t.household.id = :householdId")
    fun deleteAllByHouseholdId(householdId: UUID): Int
}

interface HouseholdInvitationRepository : JpaRepository<HouseholdInvitationEntity, UUID> {
    @Query("select i.household.id from HouseholdInvitationEntity i where i.tokenHash = :tokenHash")
    fun findHouseholdIdByTokenHash(tokenHash: String): UUID?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select i from HouseholdInvitationEntity i join fetch i.household join fetch i.targetLedgerMember " +
            "where i.tokenHash = :tokenHash",
    )
    fun findByTokenHashForUpdate(tokenHash: String): HouseholdInvitationEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select i from HouseholdInvitationEntity i join fetch i.household join fetch i.targetLedgerMember " +
            "where i.id = :id and i.household.id = :householdId",
    )
    fun findByIdAndHouseholdIdForUpdate(id: UUID, householdId: UUID): HouseholdInvitationEntity?

    @Modifying
    @Query(
        "update HouseholdInvitationEntity i set i.status = :revoked, i.cancelledAt = :now, i.version = i.version + 1 " +
            "where i.targetLedgerMember.id = :memberId and i.status = :active",
    )
    fun revokeActiveForMember(
        memberId: UUID,
        now: Instant,
        active: HouseholdInvitationStatus = HouseholdInvitationStatus.ACTIVE,
        revoked: HouseholdInvitationStatus = HouseholdInvitationStatus.REVOKED,
    ): Int
}
