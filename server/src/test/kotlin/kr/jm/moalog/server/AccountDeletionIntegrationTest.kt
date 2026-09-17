package kr.jm.moalog.server

import kr.jm.moalog.core.contracts.AuthProvider
import kr.jm.moalog.core.contracts.ClientPlatform
import kr.jm.moalog.core.contracts.HouseholdCreateRequest
import kr.jm.moalog.core.contracts.HouseholdInvitationAcceptRequest
import kr.jm.moalog.core.contracts.HouseholdRole
import kr.jm.moalog.core.contracts.LedgerMemberInputDto
import kr.jm.moalog.server.auth.infrastructure.AuthIdentityEntity
import kr.jm.moalog.server.auth.infrastructure.AuthIdentityRepository
import kr.jm.moalog.server.auth.infrastructure.AuthSessionEntity
import kr.jm.moalog.server.auth.infrastructure.AuthSessionRepository
import kr.jm.moalog.server.auth.infrastructure.UserEntity
import kr.jm.moalog.server.auth.infrastructure.UserRepository
import kr.jm.moalog.server.household.application.HouseholdService
import kr.jm.moalog.server.household.infrastructure.HouseholdMembershipRepository
import kr.jm.moalog.server.household.infrastructure.HouseholdRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import java.time.Instant
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountDeletionIntegrationTest(
    @param:Autowired private val mockMvc: MockMvc,
    @param:Autowired private val users: UserRepository,
    @param:Autowired private val identities: AuthIdentityRepository,
    @param:Autowired private val sessions: AuthSessionRepository,
    @param:Autowired private val householdService: HouseholdService,
    @param:Autowired private val households: HouseholdRepository,
    @param:Autowired private val memberships: HouseholdMembershipRepository,
) {
    @Test
    fun `deleting account anonymizes user transfers shared household and removes solo household`() {
        val owner = newUser("delete-owner")
        val partner = newUser("delete-partner")
        identities.saveAndFlush(
            AuthIdentityEntity(
                user = owner,
                provider = AuthProvider.GOOGLE,
                providerSubject = "delete-${UUID.randomUUID()}",
                providerEmail = owner.email,
                createdAt = owner.createdAt,
                updatedAt = owner.updatedAt,
            ),
        )
        sessions.saveAndFlush(
            AuthSessionEntity(
                user = owner,
                deviceId = UUID.randomUUID(),
                platform = ClientPlatform.ANDROID,
                appVersion = "test",
                createdAt = owner.createdAt,
                lastUsedAt = owner.createdAt,
                expiresAt = owner.createdAt.plusSeconds(3600),
            ),
        )
        val shared = newHousehold(owner, "공유 가계부")
        val targetMember = householdService.get(owner.id, shared).members.single { it.order == 1 }
        val invitation = householdService.createInvitation(owner.id, shared, UUID.fromString(targetMember.memberId.value))
        householdService.acceptInvitation(partner.id, HouseholdInvitationAcceptRequest(invitation.invitationToken))
        val solo = newHousehold(owner, "개인 가계부")

        mockMvc.delete("/api/auth/me") {
            with(jwt().jwt { it.subject(owner.id.toString()) })
        }.andExpect { status { isNoContent() } }

        val anonymized = users.findById(owner.id).orElseThrow()
        assertNull(anonymized.displayName)
        assertNull(anonymized.email)
        assertNotNull(anonymized.deletedAt)
        assertNull(users.findByIdAndDeletedAtIsNull(owner.id))
        assertTrue(identities.findAll().none { it.user.id == owner.id })
        assertTrue(sessions.findAll().none { it.user.id == owner.id })
        assertFalse(memberships.existsByHouseholdIdAndUserId(shared, owner.id))
        assertTrue(households.existsById(shared))
        assertFalse(households.existsById(solo))
        assertEquals(HouseholdRole.OWNER, memberships.findByHouseholdIdAndUserId(shared, partner.id)?.role)
    }

    private fun newUser(label: String): UserEntity {
        val now = Instant.now()
        return users.saveAndFlush(
            UserEntity(
                displayName = label,
                email = "$label-${UUID.randomUUID()}@example.test",
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun newHousehold(owner: UserEntity, name: String): UUID = UUID.fromString(
        householdService.create(
            owner.id,
            HouseholdCreateRequest(
                name = name,
                baseYear = 2026,
                creatorMemberOrder = 0,
                members = listOf(LedgerMemberInputDto("나", 0), LedgerMemberInputDto("배우자", 1)),
            ),
        ).householdId.value,
    )
}
