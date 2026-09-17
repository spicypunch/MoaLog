package kr.jm.moalog.server.account.application

import kr.jm.moalog.core.contracts.HouseholdRole
import kr.jm.moalog.server.auth.application.AuthenticatedUserNotFoundException
import kr.jm.moalog.server.auth.infrastructure.AuthIdentityRepository
import kr.jm.moalog.server.auth.infrastructure.AuthSessionRepository
import kr.jm.moalog.server.auth.infrastructure.UserRepository
import kr.jm.moalog.server.household.infrastructure.HouseholdMembershipRepository
import kr.jm.moalog.server.household.infrastructure.HouseholdRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
class AccountDeletionService(
    private val users: UserRepository,
    private val identities: AuthIdentityRepository,
    private val sessions: AuthSessionRepository,
    private val memberships: HouseholdMembershipRepository,
    private val households: HouseholdRepository,
    private val clock: Clock,
) {
    @Transactional
    fun delete(userId: UUID) {
        val user = users.findByIdAndDeletedAtIsNull(userId) ?: throw AuthenticatedUserNotFoundException()
        val now = clock.instant()
        memberships.findAllForUser(userId).forEach { membership ->
            val household = households.findByIdForUpdate(membership.household.id) ?: return@forEach
            if (membership.role == HouseholdRole.OWNER) {
                val successor = memberships.findAllByHouseholdId(household.id)
                    .firstOrNull { it.user.id != userId }
                if (successor == null) {
                    households.deleteHouseholdById(household.id)
                    return@forEach
                }
                successor.role = HouseholdRole.OWNER
                successor.version += 1
                successor.updatedAt = now
                household.version += 1
                household.updatedAt = now
                memberships.save(successor)
                households.save(household)
            }
            memberships.delete(membership)
        }
        identities.deleteAllByUserId(userId)
        sessions.deleteAllByUserId(userId)
        user.displayName = null
        user.email = null
        user.deletedAt = now
        user.updatedAt = now
        users.save(user)
    }
}
