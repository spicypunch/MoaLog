package kr.jm.moalog.server.sync.infrastructure

import kr.jm.moalog.server.sync.domain.SyncEntityKind
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface SyncEntitySnapshotRepository : JpaRepository<SyncEntitySnapshotEntity, UUID> {
    fun findByHouseholdIdAndEntityTypeAndEntityId(
        householdId: UUID,
        entityType: SyncEntityKind,
        entityId: UUID,
    ): SyncEntitySnapshotEntity?

    fun findAllByHouseholdIdAndDeletedFalse(householdId: UUID): List<SyncEntitySnapshotEntity>
}

interface SyncChangeRepository : JpaRepository<SyncChangeEntity, Long> {
    fun findByHouseholdIdAndCursorGreaterThanOrderByCursorAsc(
        householdId: UUID,
        cursor: Long,
        pageable: Pageable,
    ): List<SyncChangeEntity>

    @Query("select max(c.cursor) from SyncChangeEntity c where c.household.id = :householdId")
    fun findCurrentCursor(householdId: UUID): Long?
}

interface ProcessedSyncMutationRepository : JpaRepository<ProcessedSyncMutationEntity, UUID> {
    fun findByHouseholdIdAndMutationId(householdId: UUID, mutationId: UUID): ProcessedSyncMutationEntity?
}
