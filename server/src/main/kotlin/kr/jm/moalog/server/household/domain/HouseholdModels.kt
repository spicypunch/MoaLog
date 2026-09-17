package kr.jm.moalog.server.household.domain

import kr.jm.moalog.core.contracts.HouseholdInvitationStatus
import kr.jm.moalog.core.contracts.HouseholdRole
import kr.jm.moalog.core.contracts.LedgerCurrency
import java.time.Instant
import java.util.UUID

data class Household(
    val id: UUID,
    val name: String,
    val currency: LedgerCurrency,
    val baseYear: Int,
    val members: List<LedgerMember>,
    val annualSavingsTargets: List<AnnualSavingsTarget>,
    val version: Long,
    val updatedAt: Instant,
)

data class LedgerMember(
    val id: UUID,
    val displayName: String,
    val order: Int,
    val linkedUserId: UUID?,
)

data class AnnualSavingsTarget(
    val year: Int,
    val amountWon: Long,
)

data class HouseholdMembership(
    val householdId: UUID,
    val userId: UUID,
    val ledgerMemberId: UUID,
    val role: HouseholdRole,
)

data class HouseholdInvitation(
    val id: UUID,
    val householdId: UUID,
    val targetMemberId: UUID,
    val status: HouseholdInvitationStatus,
    val expiresAt: Instant,
)
