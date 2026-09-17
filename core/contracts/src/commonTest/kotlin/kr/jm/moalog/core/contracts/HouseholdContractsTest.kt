package kr.jm.moalog.core.contracts

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HouseholdContractsTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    @Test
    fun `household creation round trips with stable role and currency values`() {
        val request = HouseholdCreateRequest(
            name = "우리집 가계부",
            baseYear = 2026,
            creatorMemberOrder = 1,
            members = listOf(
                LedgerMemberInputDto("수아", 0),
                LedgerMemberInputDto("종민", 1),
            ),
            annualSavingsTargets = listOf(AnnualSavingsTargetDto(2026, 30_000_000L)),
        )
        val detail = HouseholdDetailDto(
            householdId = uuid(1),
            name = request.name,
            currency = LedgerCurrency.KRW,
            baseYear = request.baseYear,
            currentUserRole = HouseholdRole.OWNER,
            members = request.members.mapIndexed { index, member ->
                LedgerMemberDto(uuid(index + 2), member.displayName, member.order, null)
            },
            annualSavingsTargets = request.annualSavingsTargets,
            version = 1,
            updatedAt = UtcInstantString("2026-09-14T01:02:03Z"),
        )

        assertEquals(request, json.decodeFromString<HouseholdCreateRequest>(json.encodeToString(request)))
        val encodedDetail = json.encodeToString(detail)
        assertEquals(detail, json.decodeFromString<HouseholdDetailDto>(encodedDetail))
        assertTrue(encodedDetail.contains("\"currency\":\"KRW\""))
        assertTrue(encodedDetail.contains("\"currentUserRole\":\"owner\""))
    }

    @Test
    fun `update distinguishes unchanged targets from clearing all targets`() {
        val renameOnly = HouseholdUpdateRequest(baseVersion = 1, name = "새 이름")
        val clearTargets = HouseholdUpdateRequest(baseVersion = 1, annualSavingsTargets = emptyList())

        assertFalse(json.encodeToString(renameOnly).contains("annualSavingsTargets"))
        assertTrue(json.encodeToString(clearTargets).contains("\"annualSavingsTargets\":[]"))
        assertFailsWith<IllegalArgumentException> { HouseholdUpdateRequest(baseVersion = 1) }
    }

    @Test
    fun `couple ledger requires exactly two ordered display members and unique target years`() {
        assertFailsWith<IllegalArgumentException> {
            HouseholdCreateRequest(
                name = "가계부",
                baseYear = 2026,
                creatorMemberOrder = 0,
                members = listOf(LedgerMemberInputDto("한 명", 0)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            HouseholdCreateRequest(
                name = "가계부",
                baseYear = 2026,
                creatorMemberOrder = 0,
                members = listOf(LedgerMemberInputDto("A", 0), LedgerMemberInputDto("B", 1)),
                annualSavingsTargets = listOf(
                    AnnualSavingsTargetDto(2026, 0),
                    AnnualSavingsTargetDto(2026, 1),
                ),
            )
        }
    }

    @Test
    fun `invitation secrets are redacted`() {
        val invitation = HouseholdInvitationResponse(
            invitation = HouseholdInvitationDto(
                invitationId = uuid(8),
                householdId = uuid(1),
                targetMemberId = uuid(2),
                status = HouseholdInvitationStatus.ACTIVE,
                expiresAt = UtcInstantString("2026-09-15T01:02:03Z"),
            ),
            invitationToken = "secret-invitation-token",
        )
        val accept = HouseholdInvitationAcceptRequest("secret-invitation-token")

        assertFalse(invitation.toString().contains("secret-invitation-token"))
        assertFalse(accept.toString().contains("secret-invitation-token"))
    }

    private fun uuid(last: Int) = UuidString("00000000-0000-4000-8000-${last.toString().padStart(12, '0')}")
}
