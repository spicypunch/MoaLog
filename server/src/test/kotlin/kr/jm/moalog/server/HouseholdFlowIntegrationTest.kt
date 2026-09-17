package kr.jm.moalog.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.jm.moalog.core.contracts.AnnualSavingsTargetDto
import kr.jm.moalog.core.contracts.HouseholdCreateRequest
import kr.jm.moalog.core.contracts.HouseholdInvitationResponse
import kr.jm.moalog.core.contracts.LedgerMemberInputDto
import kr.jm.moalog.server.auth.infrastructure.UserEntity
import kr.jm.moalog.server.auth.infrastructure.UserRepository
import kr.jm.moalog.server.household.infrastructure.AnnualSavingsTargetRepository
import kr.jm.moalog.server.household.infrastructure.HouseholdInvitationRepository
import kr.jm.moalog.server.household.infrastructure.HouseholdMembershipRepository
import kr.jm.moalog.server.household.infrastructure.LedgerMemberRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class HouseholdFlowIntegrationTest(
    @param:Autowired private val mockMvc: MockMvc,
    @param:Autowired private val objectMapper: ObjectMapper,
    @param:Autowired private val users: UserRepository,
    @param:Autowired private val members: LedgerMemberRepository,
    @param:Autowired private val memberships: HouseholdMembershipRepository,
    @param:Autowired private val savingsTargets: AnnualSavingsTargetRepository,
    @param:Autowired private val invitations: HouseholdInvitationRepository,
) {
    @Test
    fun `owner creates lists reads and updates a two-person household`() {
        val owner = newUser("owner")
        val created = createHousehold(owner)
        val householdId = UUID.fromString(created["householdId"].asText())

        assertEquals("우리집", created["name"].asText())
        assertEquals("KRW", created["currency"].asText())
        assertEquals("owner", created["currentUserRole"].asText())
        assertEquals(1L, created["version"].asLong())
        assertEquals(2, created["members"].size())
        assertEquals(0L, created["annualSavingsTargets"][0]["amountWon"].asLong())
        assertEquals(1, memberships.findAllByHouseholdId(householdId).size)
        assertEquals(2, members.findAllByHouseholdIdOrderByOrderAsc(householdId).size)

        mockMvc.get("/api/households") { with(jwtFor(owner)) }.andExpect {
            status { isOk() }
            jsonPath("$[0].householdId") { value(householdId.toString()) }
            jsonPath("$[0].currentUserRole") { value("owner") }
        }
        mockMvc.get("/api/households/$householdId") { with(jwtFor(owner)) }.andExpect {
            status { isOk() }
            jsonPath("$.members.length()") { value(2) }
        }

        val memberNodes = created["members"]
        val updateBody = """
            {
              "baseVersion":1,
              "name":"새 이름",
              "baseYear":2027,
              "members":[
                {"memberId":"${memberNodes[0]["memberId"].asText()}","displayName":"하나","order":0},
                {"memberId":"${memberNodes[1]["memberId"].asText()}","displayName":"둘","order":1}
              ]
            }
        """.trimIndent()
        mockMvc.patch("/api/households/$householdId") {
            with(jwtFor(owner))
            contentType = MediaType.APPLICATION_JSON
            content = updateBody
        }.andExpect {
            status { isOk() }
            jsonPath("$.name") { value("새 이름") }
            jsonPath("$.baseYear") { value(2027) }
            jsonPath("$.annualSavingsTargets[0].year") { value(2026) }
            jsonPath("$.annualSavingsTargets[0].amountWon") { value(0) }
            jsonPath("$.version") { value(2) }
        }
        assertEquals(1, savingsTargets.findAllByHouseholdIdOrderByYearAsc(householdId).size)

        mockMvc.patch("/api/households/$householdId") {
            with(jwtFor(owner))
            contentType = MediaType.APPLICATION_JSON
            content = """{"baseVersion":1,"name":"오래된 수정"}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.type") { value("urn:moalog:problem:household-version-conflict") }
        }
        mockMvc.get("/api/households/$householdId") { with(jwtFor(owner)) }.andExpect {
            status { isOk() }
            jsonPath("$.name") { value("새 이름") }
            jsonPath("$.version") { value(2) }
        }

        mockMvc.patch("/api/households/$householdId") {
            with(jwtFor(owner))
            contentType = MediaType.APPLICATION_JSON
            content = """{"baseVersion":2,"annualSavingsTargets":[]}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.annualSavingsTargets.length()") { value(0) }
        }
        assertTrue(savingsTargets.findAllByHouseholdIdOrderByYearAsc(householdId).isEmpty())
    }

    @Test
    fun `non-member cannot discover a household while member can edit but not invite`() {
        val owner = newUser("owner")
        val outsider = newUser("outsider")
        val memberUser = newUser("member")
        val created = createHousehold(owner)
        val householdId = UUID.fromString(created["householdId"].asText())
        val targetId = created["members"].first { it["order"].asInt() == 1 }["memberId"].asText()

        mockMvc.get("/api/households/$householdId") { with(jwtFor(outsider)) }.andExpect {
            status { isNotFound() }
            jsonPath("$.type") { value("urn:moalog:problem:household-not-found") }
        }
        mockMvc.patch("/api/households/$householdId") {
            with(jwtFor(outsider))
            contentType = MediaType.APPLICATION_JSON
            content = """{"baseVersion":1,"name":"침입"}"""
        }.andExpect { status { isNotFound() } }

        val invite = createInvitation(owner, householdId, targetId)
        acceptInvitation(memberUser, invite.invitationToken).andExpect { status { isOk() } }

        mockMvc.patch("/api/households/$householdId") {
            with(jwtFor(memberUser))
            contentType = MediaType.APPLICATION_JSON
            content = """{"baseVersion":2,"name":"함께 수정"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.name") { value("함께 수정") }
            jsonPath("$.currentUserRole") { value("member") }
        }
        mockMvc.post("/api/households/$householdId/invitations") {
            with(jwtFor(memberUser))
            contentType = MediaType.APPLICATION_JSON
            content = """{"targetMemberId":"$targetId"}"""
        }.andExpect { status { isForbidden() } }
        mockMvc.delete(
            "/api/households/$householdId/invitations/${invite.invitation.invitationId.value}",
        ) { with(jwtFor(memberUser)) }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `invitation plaintext is returned once hashed at rest and cannot be reused`() {
        val owner = newUser("owner")
        val invited = newUser("invited")
        val created = createHousehold(owner)
        val householdId = UUID.fromString(created["householdId"].asText())
        val targetId = created["members"].first { it["order"].asInt() == 1 }["memberId"].asText()
        val invite = createInvitation(owner, householdId, targetId)

        val stored = invitations.findById(UUID.fromString(invite.invitation.invitationId.value)).orElseThrow()
        assertNotEquals(invite.invitationToken, stored.tokenHash)
        assertEquals(64, stored.tokenHash.length)
        assertFalse(invite.toString().contains(invite.invitationToken))

        acceptInvitation(invited, invite.invitationToken).andExpect {
            status { isOk() }
            jsonPath("$.currentUserRole") { value("member") }
            jsonPath("$.members[1].linkedUserId") { value(invited.id.toString()) }
        }
        acceptInvitation(newUser("other"), invite.invitationToken).andExpect {
            status { isConflict() }
            jsonPath("$.type") { value("urn:moalog:problem:household-invitation-unavailable") }
        }
    }

    @Test
    fun `revoked expired and unknown invitations use the same stable problem type`() {
        val owner = newUser("owner")
        val acceptor = newUser("acceptor")
        val created = createHousehold(owner)
        val householdId = UUID.fromString(created["householdId"].asText())
        val targetId = created["members"].first { it["order"].asInt() == 1 }["memberId"].asText()

        val revoked = createInvitation(owner, householdId, targetId)
        mockMvc.delete(
            "/api/households/$householdId/invitations/${revoked.invitation.invitationId.value}",
        ) { with(jwtFor(owner)) }.andExpect { status { isNoContent() } }
        assertUnavailable(acceptInvitation(acceptor, revoked.invitationToken))

        val expired = createInvitation(owner, householdId, targetId)
        invitations.findById(UUID.fromString(expired.invitation.invitationId.value)).orElseThrow().also {
            it.createdAt = Instant.now().minus(2, ChronoUnit.DAYS)
            it.expiresAt = Instant.now().minus(1, ChronoUnit.DAYS)
            invitations.saveAndFlush(it)
        }
        assertUnavailable(acceptInvitation(acceptor, expired.invitationToken))
        assertUnavailable(acceptInvitation(acceptor, "A".repeat(43)))
    }

    @Test
    fun `invalid member shape and duplicate target years are validation errors`() {
        val owner = newUser("owner")
        val invalid = """
            {
              "name":"우리집","baseYear":2026,"creatorMemberOrder":0,
              "members":[{"displayName":"나","order":0},{"displayName":"배우자","order":0}],
              "annualSavingsTargets":[{"year":2026,"amountWon":0},{"year":2026,"amountWon":1}]
            }
        """.trimIndent()
        mockMvc.post("/api/households") {
            with(jwtFor(owner))
            contentType = MediaType.APPLICATION_JSON
            content = invalid
        }.andExpect {
            status { isBadRequest() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.type") { value("urn:moalog:problem:validation") }
        }
    }

    private fun createHousehold(owner: UserEntity): JsonNode {
        val request = HouseholdCreateRequest(
            name = "우리집",
            baseYear = 2026,
            creatorMemberOrder = 0,
            members = listOf(LedgerMemberInputDto("나", 0), LedgerMemberInputDto("배우자", 1)),
            annualSavingsTargets = listOf(AnnualSavingsTargetDto(year = 2026, amountWon = 0)),
        )
        val result = mockMvc.post("/api/households") {
            with(jwtFor(owner))
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsBytes(request)
        }.andExpect { status { isCreated() } }.andReturn()
        return objectMapper.readTree(result.response.contentAsByteArray)
    }

    private fun createInvitation(owner: UserEntity, householdId: UUID, targetId: String): HouseholdInvitationResponse {
        val result = mockMvc.post("/api/households/$householdId/invitations") {
            with(jwtFor(owner))
            contentType = MediaType.APPLICATION_JSON
            content = """{"targetMemberId":"$targetId"}"""
        }.andExpect { status { isCreated() } }.andReturn()
        return objectMapper.readValue(result.response.contentAsByteArray, HouseholdInvitationResponse::class.java)
    }

    private fun acceptInvitation(user: UserEntity, token: String): ResultActionsDsl =
        mockMvc.post("/api/household-invitations/accept") {
            with(jwtFor(user))
            contentType = MediaType.APPLICATION_JSON
            content = """{"invitationToken":"$token"}"""
        }

    private fun assertUnavailable(result: ResultActionsDsl) {
        result.andExpect {
            status { isConflict() }
            jsonPath("$.type") { value("urn:moalog:problem:household-invitation-unavailable") }
        }
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

    private fun jwtFor(user: UserEntity) = jwt().jwt { it.subject(user.id.toString()) }
}
