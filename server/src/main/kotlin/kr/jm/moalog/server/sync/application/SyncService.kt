package kr.jm.moalog.server.sync.application

import kr.jm.moalog.core.contracts.AppliedMutationDto
import kr.jm.moalog.core.contracts.MAX_SYNC_MUTATIONS_PER_PUSH
import kr.jm.moalog.core.contracts.MAX_SYNC_PAYLOAD_BYTES
import kr.jm.moalog.core.contracts.MAX_SYNC_PULL_LIMIT
import kr.jm.moalog.core.contracts.SyncChangeDto
import kr.jm.moalog.core.contracts.SyncEntityType
import kr.jm.moalog.core.contracts.SyncOperation
import kr.jm.moalog.core.contracts.SyncPullResponse
import kr.jm.moalog.core.contracts.SyncPushRequest
import kr.jm.moalog.core.contracts.SyncPushResponse
import kr.jm.moalog.core.contracts.UtcInstantString
import kr.jm.moalog.core.contracts.UuidString
import kr.jm.moalog.server.household.application.HouseholdNotFoundException
import kr.jm.moalog.server.auth.infrastructure.AuthSessionRepository
import kr.jm.moalog.server.household.infrastructure.HouseholdEntity
import kr.jm.moalog.server.household.infrastructure.HouseholdMembershipRepository
import kr.jm.moalog.server.household.infrastructure.HouseholdRepository
import kr.jm.moalog.server.sync.domain.CanonicalSyncMutation
import kr.jm.moalog.server.sync.domain.SyncAction
import kr.jm.moalog.server.sync.domain.SyncEntityKind
import kr.jm.moalog.server.sync.infrastructure.ProcessedSyncMutationEntity
import kr.jm.moalog.server.sync.infrastructure.ProcessedSyncMutationRepository
import kr.jm.moalog.server.sync.infrastructure.SyncChangeEntity
import kr.jm.moalog.server.sync.infrastructure.SyncChangeRepository
import kr.jm.moalog.server.sync.infrastructure.SyncEntitySnapshotEntity
import kr.jm.moalog.server.sync.infrastructure.SyncEntitySnapshotRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.util.UUID

@Service
class SyncService(
    private val households: HouseholdRepository,
    private val memberships: HouseholdMembershipRepository,
    private val snapshots: SyncEntitySnapshotRepository,
    private val changes: SyncChangeRepository,
    private val processedMutations: ProcessedSyncMutationRepository,
    private val authSessions: AuthSessionRepository,
    private val canonicalizer: SyncCanonicalizer,
    private val payloadValidator: SyncPayloadValidator,
    private val clock: Clock,
) {
    @Transactional
    fun push(userId: UUID, sessionId: UUID, householdId: UUID, request: SyncPushRequest): SyncPushResponse {
        val deviceId = UUID.fromString(request.deviceId.value)
        val session = authSessions.findByIdAndUserIdAndRevokedAtIsNullAndExpiresAtAfter(
            sessionId,
            userId,
            clock.instant(),
        ) ?: throw SyncDeviceMismatchException()
        if (session.deviceId != deviceId) throw SyncDeviceMismatchException()
        val household = authorizedHouseholdForWrite(userId, householdId)
        if (request.mutations.isEmpty() || request.mutations.size > MAX_SYNC_MUTATIONS_PER_PUSH) {
            throw InvalidSyncRequestException()
        }
        val canonicalMutations = request.mutations.map { canonicalizer.canonicalize(deviceId, it) }
        canonicalMutations.forEach(::validateMutation)
        val validationContext = payloadValidator.context(householdId)

        val applied = canonicalMutations.map { mutation ->
            val previous = processedMutations.findByHouseholdIdAndMutationId(householdId, mutation.mutationId)
            if (previous != null) {
                if (previous.contentFingerprint != mutation.fingerprint) throw SyncIdempotencyConflictException()
                previous.toAppliedDto()
            } else {
                validationContext.validateAndApply(mutation) {
                    applyNewMutation(household, mutation)
                }
            }
        }
        return SyncPushResponse(
            applied = applied,
            currentCursor = changes.findCurrentCursor(householdId) ?: 0,
        )
    }

    @Transactional(readOnly = true)
    fun pull(userId: UUID, householdId: UUID, cursor: Long, limit: Int): SyncPullResponse {
        requireMembership(userId, householdId)
        if (cursor < 0 || limit !in 1..MAX_SYNC_PULL_LIMIT) throw InvalidSyncRequestException()
        val fetched = changes.findByHouseholdIdAndCursorGreaterThanOrderByCursorAsc(
            householdId = householdId,
            cursor = cursor,
            pageable = PageRequest.of(0, limit + 1),
        )
        val page = fetched.take(limit)
        return SyncPullResponse(
            changes = page.map { it.toContract() },
            nextCursor = page.lastOrNull()?.cursor ?: cursor,
            hasMore = fetched.size > limit,
        )
    }

    private fun authorizedHouseholdForWrite(userId: UUID, householdId: UUID): HouseholdEntity {
        requireMembership(userId, householdId)
        return households.findByIdForUpdate(householdId) ?: throw HouseholdNotFoundException()
    }

    private fun requireMembership(userId: UUID, householdId: UUID) {
        if (!memberships.existsByHouseholdIdAndUserId(householdId, userId)) throw HouseholdNotFoundException()
    }

    private fun validateMutation(mutation: CanonicalSyncMutation) {
        if (mutation.canonicalContentSize > MAX_SYNC_PAYLOAD_BYTES) throw InvalidSyncRequestException()
        if (mutation.entityType !in GENERIC_ENTITY_TYPES) throw UnsupportedSyncEntityTypeException()
    }

    private fun applyNewMutation(
        household: HouseholdEntity,
        mutation: CanonicalSyncMutation,
    ): AppliedMutationDto {
        val existing = snapshots.findByHouseholdIdAndEntityTypeAndEntityId(
            household.id,
            mutation.entityType,
            mutation.entityId,
        )
        val version = nextVersion(existing, mutation)
        val now = clock.instant()
        val snapshot = existing ?: SyncEntitySnapshotEntity(
            household = household,
            entityType = mutation.entityType,
            entityId = mutation.entityId,
            createdAt = now,
        )
        snapshot.entityVersion = version
        snapshot.deleted = mutation.operation == SyncAction.DELETE
        snapshot.payloadJson = mutation.canonicalPayload
        snapshot.updatedAt = now
        snapshots.save(snapshot)

        val change = changes.saveAndFlush(
            SyncChangeEntity(
                household = household,
                entityType = mutation.entityType,
                entityId = mutation.entityId,
                operation = mutation.operation,
                entityVersion = version,
                payloadJson = mutation.canonicalPayload,
                changedAt = now,
            ),
        )
        processedMutations.save(
            ProcessedSyncMutationEntity(
                household = household,
                mutationId = mutation.mutationId,
                deviceId = mutation.deviceId,
                contentFingerprint = mutation.fingerprint,
                entityType = mutation.entityType,
                entityId = mutation.entityId,
                appliedVersion = version,
                changeCursor = change.cursor,
                processedAt = now,
            ),
        )
        return AppliedMutationDto(
            mutationId = mutation.mutationId.uuidString(),
            entityId = mutation.entityId.uuidString(),
            version = version,
        )
    }

    private fun nextVersion(
        existing: SyncEntitySnapshotEntity?,
        mutation: CanonicalSyncMutation,
    ): Long {
        if (existing == null) {
            if (mutation.operation != SyncAction.UPSERT || mutation.baseVersion !in setOf(null, 0L)) {
                throw SyncVersionConflictException()
            }
            return 1
        }
        if (existing.entityVersion != mutation.baseVersion) {
            throw SyncVersionConflictException()
        }
        // A matching tombstone can be superseded by an UPSERT (resurrection), while repeating a
        // DELETE advances the tombstone deterministically. This lets offline client-wins rebases
        // converge after another device deleted the same entity.
        return existing.entityVersion + 1
    }

    private fun ProcessedSyncMutationEntity.toAppliedDto() = AppliedMutationDto(
        mutationId = mutationId.uuidString(),
        entityId = entityId.uuidString(),
        version = appliedVersion,
    )

    private fun SyncChangeEntity.toContract() = SyncChangeDto(
        cursor = cursor,
        entityType = SyncEntityType.valueOf(entityType.name),
        entityId = entityId.uuidString(),
        operation = SyncOperation.valueOf(operation.name),
        version = entityVersion,
        payload = payloadJson?.let { Json.parseToJsonElement(it).jsonObject },
        changedAt = UtcInstantString(DateTimeFormatter.ISO_INSTANT.format(changedAt)),
    )

    private fun UUID.uuidString() = UuidString(toString())

    private companion object {
        val GENERIC_ENTITY_TYPES = SyncEntityKind.entries.toSet() - setOf(
            SyncEntityKind.HOUSEHOLD,
            SyncEntityKind.LEDGER_MEMBER,
            SyncEntityKind.ANNUAL_SAVINGS_TARGET,
        )
    }
}
