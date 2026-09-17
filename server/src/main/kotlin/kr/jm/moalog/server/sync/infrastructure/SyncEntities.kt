package kr.jm.moalog.server.sync.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import kr.jm.moalog.server.household.infrastructure.HouseholdEntity
import kr.jm.moalog.server.sync.domain.SyncAction
import kr.jm.moalog.server.sync.domain.SyncEntityKind
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "sync_entity_snapshots", schema = "moalog")
class SyncEntitySnapshotEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    var household: HouseholdEntity = HouseholdEntity(),
    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 50)
    var entityType: SyncEntityKind = SyncEntityKind.EXPENSE_RECORD,
    @Column(name = "entity_id", nullable = false)
    var entityId: UUID = UUID.randomUUID(),
    @Column(name = "entity_version", nullable = false)
    var entityVersion: Long = 1,
    @Column(nullable = false)
    var deleted: Boolean = false,
    @Column(name = "payload_json", columnDefinition = "TEXT")
    var payloadJson: String? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "sync_change_log", schema = "moalog")
class SyncChangeEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    var cursor: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    var household: HouseholdEntity = HouseholdEntity(),
    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 50)
    var entityType: SyncEntityKind = SyncEntityKind.EXPENSE_RECORD,
    @Column(name = "entity_id", nullable = false)
    var entityId: UUID = UUID.randomUUID(),
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var operation: SyncAction = SyncAction.UPSERT,
    @Column(name = "entity_version", nullable = false)
    var entityVersion: Long = 1,
    @Column(name = "payload_json", columnDefinition = "TEXT")
    var payloadJson: String? = null,
    @Column(name = "changed_at", nullable = false)
    var changedAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "sync_processed_mutations", schema = "moalog")
class ProcessedSyncMutationEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    var household: HouseholdEntity = HouseholdEntity(),
    @Column(name = "mutation_id", nullable = false)
    var mutationId: UUID = UUID.randomUUID(),
    @Column(name = "device_id", nullable = false)
    var deviceId: UUID = UUID.randomUUID(),
    @Column(name = "content_fingerprint", nullable = false, length = 64)
    var contentFingerprint: String = "",
    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 50)
    var entityType: SyncEntityKind = SyncEntityKind.EXPENSE_RECORD,
    @Column(name = "entity_id", nullable = false)
    var entityId: UUID = UUID.randomUUID(),
    @Column(name = "applied_version", nullable = false)
    var appliedVersion: Long = 1,
    @Column(name = "change_cursor", nullable = false)
    var changeCursor: Long = 0,
    @Column(name = "processed_at", nullable = false)
    var processedAt: Instant = Instant.EPOCH,
)
