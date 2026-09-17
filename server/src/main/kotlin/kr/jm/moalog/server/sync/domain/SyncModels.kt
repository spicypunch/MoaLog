package kr.jm.moalog.server.sync.domain

import java.time.Instant
import java.util.UUID

enum class SyncEntityKind {
    HOUSEHOLD,
    LEDGER_MEMBER,
    ANNUAL_SAVINGS_TARGET,
    PLAN_CATALOG_ITEM,
    MONTHLY_PLAN_ITEM,
    EXPENSE_CATEGORY,
    EXPENSE_RECORD,
    SALARY_INCOME,
    SALARY_ALLOCATION_CATEGORY,
    SALARY_ALLOCATION_DEDUCTION,
    SALARY_ALLOCATION_CHILD,
    SALARY_ALLOCATION_GRANDCHILD,
    FIXED_COST_ITEM,
    ASSET,
    ASSET_VALUATION,
    ASSET_GROWTH_RULE,
    MAINTENANCE_FEE_MONTH,
}

enum class SyncAction { UPSERT, DELETE }

data class CanonicalSyncMutation(
    val mutationId: UUID,
    val deviceId: UUID,
    val entityType: SyncEntityKind,
    val entityId: UUID,
    val operation: SyncAction,
    val baseVersion: Long?,
    val canonicalPayload: String?,
    val fingerprint: String,
    val canonicalContentSize: Int,
)

data class StoredSyncChange(
    val cursor: Long,
    val entityType: SyncEntityKind,
    val entityId: UUID,
    val operation: SyncAction,
    val version: Long,
    val canonicalPayload: String?,
    val changedAt: Instant,
)
