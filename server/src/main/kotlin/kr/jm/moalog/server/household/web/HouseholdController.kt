package kr.jm.moalog.server.household.web

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import kr.jm.moalog.core.contracts.AnnualSavingsTargetDto
import kr.jm.moalog.core.contracts.HouseholdCreateRequest
import kr.jm.moalog.core.contracts.HouseholdDetailDto
import kr.jm.moalog.core.contracts.HouseholdInvitationAcceptRequest
import kr.jm.moalog.core.contracts.HouseholdInvitationResponse
import kr.jm.moalog.core.contracts.HouseholdSummaryDto
import kr.jm.moalog.core.contracts.HouseholdUpdateRequest
import kr.jm.moalog.core.contracts.LedgerMemberInputDto
import kr.jm.moalog.core.contracts.LedgerMemberUpdateDto
import kr.jm.moalog.core.contracts.UuidString
import kr.jm.moalog.server.household.application.HouseholdService
import kr.jm.moalog.server.household.application.InvalidHouseholdSettingsException
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api")
class HouseholdController(
    private val householdService: HouseholdService,
) {
    @PostMapping("/households")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: HouseholdCreateWebRequest,
    ): HouseholdDetailDto = householdService.create(jwt.userId(), request.toContractSafely())

    @GetMapping("/households")
    fun list(@AuthenticationPrincipal jwt: Jwt): List<HouseholdSummaryDto> =
        householdService.list(jwt.userId())

    @GetMapping("/households/{householdId}")
    fun get(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable householdId: UUID,
    ): HouseholdDetailDto = householdService.get(jwt.userId(), householdId)

    @PatchMapping("/households/{householdId}")
    fun update(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable householdId: UUID,
        @Valid @RequestBody request: HouseholdUpdateWebRequest,
    ): HouseholdDetailDto = householdService.update(jwt.userId(), householdId, request.toContractSafely())

    @PostMapping("/households/{householdId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    fun createInvitation(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable householdId: UUID,
        @Valid @RequestBody request: HouseholdInvitationCreateWebRequest,
    ): HouseholdInvitationResponse = householdService.createInvitation(
        jwt.userId(),
        householdId,
        UUID.fromString(request.targetMemberId),
    )

    @DeleteMapping("/households/{householdId}/invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revokeInvitation(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable householdId: UUID,
        @PathVariable invitationId: UUID,
    ) {
        householdService.revokeInvitation(jwt.userId(), householdId, invitationId)
    }

    @PostMapping("/household-invitations/accept")
    fun acceptInvitation(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: HouseholdInvitationAcceptWebRequest,
    ): HouseholdDetailDto = householdService.acceptInvitation(jwt.userId(), request.toContract())

    private fun Jwt.userId(): UUID = UUID.fromString(subject)
}

data class HouseholdCreateWebRequest(
    @field:NotBlank
    @field:Size(max = 120)
    val name: String,
    @field:Min(1900)
    @field:Max(9999)
    val baseYear: Int,
    @field:Min(0)
    @field:Max(1)
    val creatorMemberOrder: Int,
    @field:Valid
    @field:Size(min = 2, max = 2)
    val members: List<LedgerMemberInputWebRequest>,
    @field:Valid
    val annualSavingsTargets: List<AnnualSavingsTargetWebRequest> = emptyList(),
) {
    fun toContractSafely(): HouseholdCreateRequest = validationMapping {
        HouseholdCreateRequest(
            name = name.trim(),
            baseYear = baseYear,
            creatorMemberOrder = creatorMemberOrder,
            members = members.map { it.toContract() },
            annualSavingsTargets = annualSavingsTargets.map { it.toContract() },
        )
    }
}

data class HouseholdUpdateWebRequest(
    @field:jakarta.validation.constraints.Positive
    val baseVersion: Long,
    @field:Size(max = 120)
    val name: String? = null,
    @field:Min(1900)
    @field:Max(9999)
    val baseYear: Int? = null,
    @field:Valid
    @field:Size(min = 2, max = 2)
    val members: List<LedgerMemberUpdateWebRequest>? = null,
    @field:Valid
    val annualSavingsTargets: List<AnnualSavingsTargetWebRequest>? = null,
) {
    fun toContractSafely(): HouseholdUpdateRequest = validationMapping {
        HouseholdUpdateRequest(
            baseVersion = baseVersion,
            name = name?.trim(),
            baseYear = baseYear,
            members = members?.map { it.toContract() },
            annualSavingsTargets = annualSavingsTargets?.map { it.toContract() },
        )
    }
}

data class LedgerMemberInputWebRequest(
    @field:NotBlank
    @field:Size(max = 80)
    val displayName: String,
    @field:Min(0)
    @field:Max(1)
    val order: Int,
) {
    fun toContract() = LedgerMemberInputDto(displayName.trim(), order)
}

data class LedgerMemberUpdateWebRequest(
    @field:Pattern(regexp = CANONICAL_UUID_PATTERN)
    val memberId: String,
    @field:NotBlank
    @field:Size(max = 80)
    val displayName: String,
    @field:Min(0)
    @field:Max(1)
    val order: Int,
) {
    fun toContract() = LedgerMemberUpdateDto(UuidString(memberId), displayName.trim(), order)
}

data class AnnualSavingsTargetWebRequest(
    @field:Min(1900)
    @field:Max(9999)
    val year: Int,
    @field:PositiveOrZero
    val amountWon: Long,
) {
    fun toContract() = AnnualSavingsTargetDto(year, amountWon)
}

data class HouseholdInvitationCreateWebRequest(
    @field:Pattern(regexp = CANONICAL_UUID_PATTERN)
    val targetMemberId: String,
)

data class HouseholdInvitationAcceptWebRequest(
    @field:Pattern(regexp = "^[A-Za-z0-9_-]{43}$")
    val invitationToken: String,
) {
    fun toContract() = HouseholdInvitationAcceptRequest(invitationToken)
    override fun toString(): String = "HouseholdInvitationAcceptWebRequest(invitationToken=<redacted>)"
}

private inline fun <T> validationMapping(block: () -> T): T = try {
    block()
} catch (_: IllegalArgumentException) {
    throw InvalidHouseholdSettingsException()
}

private const val CANONICAL_UUID_PATTERN =
    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
