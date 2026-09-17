package kr.jm.moalog.server.household.application

import kr.jm.moalog.core.contracts.AnnualSavingsTargetDto
import kr.jm.moalog.core.contracts.HouseholdCreateRequest
import kr.jm.moalog.core.contracts.HouseholdDetailDto
import kr.jm.moalog.core.contracts.HouseholdInvitationAcceptRequest
import kr.jm.moalog.core.contracts.HouseholdInvitationDto
import kr.jm.moalog.core.contracts.HouseholdInvitationResponse
import kr.jm.moalog.core.contracts.HouseholdInvitationStatus
import kr.jm.moalog.core.contracts.HouseholdRole
import kr.jm.moalog.core.contracts.HouseholdSummaryDto
import kr.jm.moalog.core.contracts.HouseholdUpdateRequest
import kr.jm.moalog.core.contracts.LedgerCurrency
import kr.jm.moalog.core.contracts.LedgerMemberDto
import kr.jm.moalog.core.contracts.UtcInstantString
import kr.jm.moalog.core.contracts.UuidString
import kr.jm.moalog.server.auth.application.AuthenticatedUserNotFoundException
import kr.jm.moalog.server.auth.infrastructure.UserRepository
import kr.jm.moalog.server.household.config.HouseholdProperties
import kr.jm.moalog.server.household.domain.AnnualSavingsTarget
import kr.jm.moalog.server.household.domain.Household
import kr.jm.moalog.server.household.domain.LedgerMember
import kr.jm.moalog.server.household.infrastructure.AnnualSavingsTargetEntity
import kr.jm.moalog.server.household.infrastructure.AnnualSavingsTargetRepository
import kr.jm.moalog.server.household.infrastructure.HouseholdEntity
import kr.jm.moalog.server.household.infrastructure.HouseholdInvitationEntity
import kr.jm.moalog.server.household.infrastructure.HouseholdInvitationRepository
import kr.jm.moalog.server.household.infrastructure.HouseholdMembershipEntity
import kr.jm.moalog.server.household.infrastructure.HouseholdMembershipRepository
import kr.jm.moalog.server.household.infrastructure.HouseholdRepository
import kr.jm.moalog.server.household.infrastructure.LedgerMemberEntity
import kr.jm.moalog.server.household.infrastructure.LedgerMemberRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

data class IssuedHouseholdInvitation(
    val token: String,
    val hash: String,
) {
    override fun toString(): String = "IssuedHouseholdInvitation(token=<redacted>, hash=<redacted>)"
}

@Service
class HouseholdService(
    private val households: HouseholdRepository,
    private val ledgerMembers: LedgerMemberRepository,
    private val memberships: HouseholdMembershipRepository,
    private val savingsTargets: AnnualSavingsTargetRepository,
    private val invitations: HouseholdInvitationRepository,
    private val users: UserRepository,
    private val properties: HouseholdProperties,
    private val clock: Clock,
) {
    private val secureRandom = SecureRandom()

    @Transactional
    fun create(userId: UUID, request: HouseholdCreateRequest): HouseholdDetailDto {
        validateCreate(request)
        val user = users.findById(userId).orElseThrow(::AuthenticatedUserNotFoundException)
        val now = clock.instant()
        val household = households.save(
            HouseholdEntity(
                name = request.name.trim(),
                currency = LedgerCurrency.KRW,
                baseYear = request.baseYear,
                version = 1,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val createdMembers = ledgerMembers.saveAll(
            request.members.sortedBy { it.order }.map {
                LedgerMemberEntity(
                    household = household,
                    order = it.order,
                    displayName = it.displayName.trim(),
                    version = 1,
                    createdAt = now,
                    updatedAt = now,
                )
            },
        )
        val creatorMember = createdMembers.single { it.order == request.creatorMemberOrder }
        memberships.save(
            HouseholdMembershipEntity(
                household = household,
                user = user,
                ledgerMember = creatorMember,
                role = HouseholdRole.OWNER,
                version = 1,
                createdAt = now,
                updatedAt = now,
            ),
        )
        savingsTargets.saveAll(request.annualSavingsTargets.map { it.toEntity(household, now) })
        return detailFor(household, HouseholdRole.OWNER)
    }

    @Transactional(readOnly = true)
    fun list(userId: UUID): List<HouseholdSummaryDto> = memberships.findAllForUser(userId).map { membership ->
        val household = membership.household
        HouseholdSummaryDto(
            householdId = household.id.uuidString(),
            name = household.name,
            currency = household.currency,
            baseYear = household.baseYear,
            currentUserRole = membership.role,
            updatedAt = household.updatedAt.utcString(),
        )
    }

    @Transactional(readOnly = true)
    fun get(userId: UUID, householdId: UUID): HouseholdDetailDto {
        val membership = requireMembership(userId, householdId)
        return detailFor(membership.household, membership.role)
    }

    @Transactional
    fun update(userId: UUID, householdId: UUID, request: HouseholdUpdateRequest): HouseholdDetailDto {
        val membership = requireMembership(userId, householdId)
        val household = households.findByIdForUpdate(householdId) ?: throw HouseholdNotFoundException()
        if (household.version != request.baseVersion) throw HouseholdVersionConflictException()
        val now = clock.instant()
        request.name?.let { household.name = it.trim() }
        request.baseYear?.let { household.baseYear = it }
        request.members?.let { updates ->
            val current = ledgerMembers.findAllByHouseholdIdOrderByOrderAsc(householdId)
            if (
                current.size != 2 || updates.size != 2 ||
                updates.map { UUID.fromString(it.memberId.value) }.toSet() != current.map { it.id }.toSet()
            ) throw InvalidHouseholdSettingsException()
            val currentById = current.associateBy { it.id }
            updates.forEach { update ->
                val member = currentById.getValue(UUID.fromString(update.memberId.value))
                if (member.order != update.order) throw InvalidHouseholdSettingsException()
                member.displayName = update.displayName.trim()
                member.updatedAt = now
                member.version += 1
            }
        }
        request.annualSavingsTargets?.let { targets ->
            savingsTargets.deleteAllByHouseholdId(householdId)
            savingsTargets.flush()
            savingsTargets.saveAll(targets.map { it.toEntity(household, now) })
        }
        household.updatedAt = now
        household.version += 1
        households.save(household)
        return detailFor(household, membership.role)
    }

    @Transactional
    fun createInvitation(
        userId: UUID,
        householdId: UUID,
        targetMemberId: UUID,
    ): HouseholdInvitationResponse {
        requireOwner(userId, householdId)
        val household = households.findByIdForUpdate(householdId) ?: throw HouseholdNotFoundException()
        val target = ledgerMembers.findByIdAndHouseholdId(targetMemberId, householdId)
            ?: throw InvitationTargetUnavailableException()
        if (memberships.findByLedgerMemberId(target.id) != null) throw InvitationTargetUnavailableException()
        val now = clock.instant()
        invitations.revokeActiveForMember(
            memberId = target.id,
            now = now,
            active = HouseholdInvitationStatus.ACTIVE,
            revoked = HouseholdInvitationStatus.REVOKED,
        )
        val issued = issueInvitationToken()
        val user = users.findById(userId).orElseThrow(::AuthenticatedUserNotFoundException)
        val invitation = invitations.save(
            HouseholdInvitationEntity(
                household = household,
                targetLedgerMember = target,
                createdByUser = user,
                tokenHash = issued.hash,
                status = HouseholdInvitationStatus.ACTIVE,
                version = 1,
                createdAt = now,
                expiresAt = now.plus(properties.invitationTtl),
            ),
        )
        return HouseholdInvitationResponse(invitation.toDto(), issued.token)
    }

    @Transactional
    fun revokeInvitation(userId: UUID, householdId: UUID, invitationId: UUID) {
        requireOwner(userId, householdId)
        households.findByIdForUpdate(householdId) ?: throw HouseholdNotFoundException()
        val invitation = invitations.findByIdAndHouseholdIdForUpdate(invitationId, householdId)
            ?: throw HouseholdInvitationUnavailableException()
        when (invitation.status) {
            HouseholdInvitationStatus.ACTIVE -> {
                invitation.status = HouseholdInvitationStatus.REVOKED
                invitation.cancelledAt = clock.instant()
                invitation.version += 1
            }
            HouseholdInvitationStatus.REVOKED -> Unit
            HouseholdInvitationStatus.ACCEPTED -> throw HouseholdInvitationUnavailableException()
        }
    }

    @Transactional
    fun acceptInvitation(userId: UUID, request: HouseholdInvitationAcceptRequest): HouseholdDetailDto {
        val tokenHash = hashToken(request.invitationToken)
        val householdId = invitations.findHouseholdIdByTokenHash(tokenHash)
            ?: throw HouseholdInvitationUnavailableException()
        val household = households.findByIdForUpdate(householdId)
            ?: throw HouseholdInvitationUnavailableException()
        val invitation = invitations.findByTokenHashForUpdate(tokenHash)
            ?: throw HouseholdInvitationUnavailableException()
        val now = clock.instant()
        if (invitation.status != HouseholdInvitationStatus.ACTIVE || !invitation.expiresAt.isAfter(now)) {
            throw HouseholdInvitationUnavailableException()
        }
        if (
            memberships.findByLedgerMemberId(invitation.targetLedgerMember.id) != null ||
            memberships.existsByHouseholdIdAndUserId(household.id, userId)
        ) throw HouseholdInvitationUnavailableException()
        val user = users.findById(userId).orElseThrow(::AuthenticatedUserNotFoundException)
        memberships.saveAndFlush(
            HouseholdMembershipEntity(
                household = household,
                user = user,
                ledgerMember = invitation.targetLedgerMember,
                role = HouseholdRole.MEMBER,
                version = 1,
                createdAt = now,
                updatedAt = now,
            ),
        )
        invitation.status = HouseholdInvitationStatus.ACCEPTED
        invitation.acceptedByUser = user
        invitation.acceptedAt = now
        invitation.version += 1
        household.updatedAt = now
        household.version += 1
        return detailFor(household, HouseholdRole.MEMBER)
    }

    private fun requireMembership(userId: UUID, householdId: UUID): HouseholdMembershipEntity =
        memberships.findByHouseholdIdAndUserId(householdId, userId) ?: throw HouseholdNotFoundException()

    private fun requireOwner(userId: UUID, householdId: UUID): HouseholdMembershipEntity {
        val membership = requireMembership(userId, householdId)
        if (membership.role != HouseholdRole.OWNER) throw HouseholdOwnerRequiredException()
        return membership
    }

    private fun detailFor(household: HouseholdEntity, role: HouseholdRole): HouseholdDetailDto {
        val linkedUsers = memberships.findAllByHouseholdId(household.id)
            .associate { it.ledgerMember.id to it.user.id }
        val members = ledgerMembers.findAllByHouseholdIdOrderByOrderAsc(household.id).map {
            LedgerMember(
                id = it.id,
                displayName = it.displayName,
                order = it.order,
                linkedUserId = linkedUsers[it.id],
            )
        }
        if (members.size != 2) throw IllegalStateException("A household must contain exactly two ledger members")
        val domain = Household(
            id = household.id,
            name = household.name,
            currency = household.currency,
            baseYear = household.baseYear,
            members = members,
            annualSavingsTargets = savingsTargets.findAllByHouseholdIdOrderByYearAsc(household.id).map {
                AnnualSavingsTarget(year = it.year, amountWon = it.amountWon)
            },
            version = household.version,
            updatedAt = household.updatedAt,
        )
        return domain.toDto(role)
    }

    private fun Household.toDto(role: HouseholdRole) = HouseholdDetailDto(
        householdId = id.uuidString(),
        name = name,
        currency = currency,
        baseYear = baseYear,
        currentUserRole = role,
        members = members.map {
            LedgerMemberDto(
                memberId = it.id.uuidString(),
                displayName = it.displayName,
                order = it.order,
                linkedUserId = it.linkedUserId?.uuidString(),
            )
        },
        annualSavingsTargets = annualSavingsTargets.map {
            AnnualSavingsTargetDto(year = it.year, amountWon = it.amountWon)
        },
        version = version,
        updatedAt = updatedAt.utcString(),
    )

    private fun validateCreate(request: HouseholdCreateRequest) {
        if (request.members.size != 2 || request.members.map { it.order }.toSet() != setOf(0, 1)) {
            throw InvalidHouseholdSettingsException()
        }
    }

    private fun AnnualSavingsTargetDto.toEntity(household: HouseholdEntity, now: Instant) =
        AnnualSavingsTargetEntity(
            household = household,
            year = year,
            amountWon = amountWon,
            version = 1,
            createdAt = now,
            updatedAt = now,
        )

    private fun issueInvitationToken(): IssuedHouseholdInvitation {
        val randomBytes = ByteArray(32).also(secureRandom::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)
        return IssuedHouseholdInvitation(token = token, hash = hashToken(token))
    }

    private fun hashToken(token: String): String = MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun HouseholdInvitationEntity.toDto() = HouseholdInvitationDto(
        invitationId = id.uuidString(),
        householdId = household.id.uuidString(),
        targetMemberId = targetLedgerMember.id.uuidString(),
        status = status,
        expiresAt = expiresAt.utcString(),
    )

    private fun UUID.uuidString() = UuidString(toString())
    private fun Instant.utcString() = UtcInstantString(toString())
}
