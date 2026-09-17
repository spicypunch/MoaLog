package kr.jm.moalog.core.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class HouseholdRole {
    @SerialName("owner") OWNER,
    @SerialName("member") MEMBER,
}

@Serializable
enum class LedgerCurrency {
    @SerialName("KRW") KRW,
}

@Serializable
enum class HouseholdInvitationStatus {
    @SerialName("active") ACTIVE,
    @SerialName("accepted") ACCEPTED,
    @SerialName("revoked") REVOKED,
}

@Serializable
data class LedgerMemberInputDto(
    val displayName: String,
    val order: Int,
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= MAX_MEMBER_DISPLAY_NAME_LENGTH) {
            "member displayName must contain 1 to $MAX_MEMBER_DISPLAY_NAME_LENGTH characters"
        }
        require(order in 0..1) { "member order must be 0 or 1" }
    }
}

@Serializable
data class LedgerMemberUpdateDto(
    val memberId: UuidString,
    val displayName: String,
    val order: Int,
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= MAX_MEMBER_DISPLAY_NAME_LENGTH) {
            "member displayName must contain 1 to $MAX_MEMBER_DISPLAY_NAME_LENGTH characters"
        }
        require(order in 0..1) { "member order must be 0 or 1" }
    }
}

@Serializable
data class LedgerMemberDto(
    val memberId: UuidString,
    val displayName: String,
    val order: Int,
    val linkedUserId: UuidString? = null,
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= MAX_MEMBER_DISPLAY_NAME_LENGTH) {
            "member displayName must contain 1 to $MAX_MEMBER_DISPLAY_NAME_LENGTH characters"
        }
        require(order in 0..1) { "member order must be 0 or 1" }
    }
}

@Serializable
data class AnnualSavingsTargetDto(
    val year: Int,
    val amountWon: WonAmount,
) {
    init {
        require(year in MIN_LEDGER_YEAR..MAX_LEDGER_YEAR) { "savings target year is out of range" }
        require(amountWon >= 0L) { "savings target amount must be non-negative" }
    }
}

@Serializable
data class HouseholdCreateRequest(
    val name: String,
    val baseYear: Int,
    val creatorMemberOrder: Int,
    val members: List<LedgerMemberInputDto>,
    val annualSavingsTargets: List<AnnualSavingsTargetDto> = emptyList(),
) {
    init {
        validateHouseholdName(name)
        validateLedgerYear(baseYear)
        require(creatorMemberOrder in 0..1) { "creatorMemberOrder must be 0 or 1" }
        validateMemberOrders(members.map(LedgerMemberInputDto::order))
        validateTargetYears(annualSavingsTargets)
    }
}

/** Null means unchanged. An empty annualSavingsTargets list explicitly clears every target. */
@Serializable
data class HouseholdUpdateRequest(
    val baseVersion: Long,
    val name: String? = null,
    val baseYear: Int? = null,
    val members: List<LedgerMemberUpdateDto>? = null,
    val annualSavingsTargets: List<AnnualSavingsTargetDto>? = null,
) {
    init {
        require(baseVersion > 0) { "baseVersion must be positive" }
        require(name != null || baseYear != null || members != null || annualSavingsTargets != null) {
            "at least one household setting must be supplied"
        }
        name?.let(::validateHouseholdName)
        baseYear?.let(::validateLedgerYear)
        members?.let {
            validateMemberOrders(it.map(LedgerMemberUpdateDto::order))
            require(it.map(LedgerMemberUpdateDto::memberId).distinct().size == it.size) {
                "ledger member ids must be unique"
            }
        }
        annualSavingsTargets?.let(::validateTargetYears)
    }
}

@Serializable
data class HouseholdSummaryDto(
    val householdId: UuidString,
    val name: String,
    val currency: LedgerCurrency,
    val baseYear: Int,
    val currentUserRole: HouseholdRole,
    val updatedAt: UtcInstantString,
)

@Serializable
data class HouseholdDetailDto(
    val householdId: UuidString,
    val name: String,
    val currency: LedgerCurrency,
    val baseYear: Int,
    val currentUserRole: HouseholdRole,
    val members: List<LedgerMemberDto>,
    val annualSavingsTargets: List<AnnualSavingsTargetDto>,
    val version: Long,
    val updatedAt: UtcInstantString,
) {
    init {
        validateHouseholdName(name)
        validateLedgerYear(baseYear)
        validateMemberOrders(members.map(LedgerMemberDto::order))
        validateTargetYears(annualSavingsTargets)
        require(version > 0) { "household version must be positive" }
    }
}

@Serializable
data class HouseholdInvitationCreateRequest(
    val targetMemberId: UuidString,
)

@Serializable
data class HouseholdInvitationDto(
    val invitationId: UuidString,
    val householdId: UuidString,
    val targetMemberId: UuidString,
    val status: HouseholdInvitationStatus,
    val expiresAt: UtcInstantString,
)

@Serializable
data class HouseholdInvitationResponse(
    val invitation: HouseholdInvitationDto,
    val invitationToken: String,
) {
    init {
        require(invitationToken.isNotBlank()) { "invitationToken must not be blank" }
    }

    override fun toString(): String =
        "HouseholdInvitationResponse(invitation=$invitation, invitationToken=<redacted>)"
}

@Serializable
data class HouseholdInvitationAcceptRequest(
    val invitationToken: String,
) {
    init {
        require(invitationToken.isNotBlank()) { "invitationToken must not be blank" }
    }

    override fun toString(): String = "HouseholdInvitationAcceptRequest(invitationToken=<redacted>)"
}

private const val MIN_LEDGER_YEAR = 1900
private const val MAX_LEDGER_YEAR = 9999
private const val MAX_HOUSEHOLD_NAME_LENGTH = 120
private const val MAX_MEMBER_DISPLAY_NAME_LENGTH = 80

private fun validateHouseholdName(name: String) {
    require(name.isNotBlank() && name.length <= MAX_HOUSEHOLD_NAME_LENGTH) {
        "household name must contain 1 to $MAX_HOUSEHOLD_NAME_LENGTH characters"
    }
}

private fun validateLedgerYear(year: Int) {
    require(year in MIN_LEDGER_YEAR..MAX_LEDGER_YEAR) { "base year is out of range" }
}

private fun validateMemberOrders(orders: List<Int>) {
    require(orders.size == 2 && orders.toSet() == setOf(0, 1)) {
        "a couple ledger must contain exactly one member in each order 0 and 1"
    }
}

private fun validateTargetYears(targets: List<AnnualSavingsTargetDto>) {
    require(targets.map(AnnualSavingsTargetDto::year).distinct().size == targets.size) {
        "annual savings target years must be unique"
    }
}
