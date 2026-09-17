package kr.jm.moalog.server

import kr.jm.moalog.core.contracts.HouseholdCreateRequest
import kr.jm.moalog.core.contracts.HouseholdInvitationAcceptRequest
import kr.jm.moalog.core.contracts.LedgerMemberInputDto
import kr.jm.moalog.server.auth.infrastructure.UserEntity
import kr.jm.moalog.server.auth.infrastructure.UserRepository
import kr.jm.moalog.server.household.application.HouseholdInvitationUnavailableException
import kr.jm.moalog.server.household.application.HouseholdService
import kr.jm.moalog.server.household.infrastructure.HouseholdMembershipRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@ActiveProfiles("test")
class HouseholdInvitationConcurrencyTest(
    @param:Autowired private val householdService: HouseholdService,
    @param:Autowired private val users: UserRepository,
    @param:Autowired private val memberships: HouseholdMembershipRepository,
) {
    @Test
    fun `only one user can accept the same invitation concurrently`() {
        val owner = newUser("concurrent-owner")
        val first = newUser("concurrent-first")
        val second = newUser("concurrent-second")
        val household = householdService.create(
            owner.id,
            HouseholdCreateRequest(
                name = "동시 수락 가계부",
                baseYear = 2026,
                creatorMemberOrder = 0,
                members = listOf(LedgerMemberInputDto("나", 0), LedgerMemberInputDto("배우자", 1)),
            ),
        )
        val targetMemberId = household.members.single { it.order == 1 }.memberId.value.let(UUID::fromString)
        val invitation = householdService.createInvitation(
            owner.id,
            UUID.fromString(household.householdId.value),
            targetMemberId,
        )

        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = listOf(first, second).map { user ->
                executor.submit(Callable {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    try {
                        householdService.acceptInvitation(
                            user.id,
                            HouseholdInvitationAcceptRequest(invitation.invitationToken),
                        )
                        "accepted"
                    } catch (_: HouseholdInvitationUnavailableException) {
                        "unavailable"
                    }
                })
            }
            check(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val outcomes = results.map { it.get(10, TimeUnit.SECONDS) }.sorted()

            assertEquals(listOf("accepted", "unavailable"), outcomes)
            assertEquals(1, memberships.findAllByHouseholdId(UUID.fromString(household.householdId.value))
                .count { it.ledgerMember.id == targetMemberId })
        } finally {
            executor.shutdownNow()
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
}
