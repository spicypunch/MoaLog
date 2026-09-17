package kr.jm.moalog.core.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class SyncOperation {
    @SerialName("upsert")
    UPSERT,

    @SerialName("delete")
    DELETE,
}

@Serializable
enum class SyncEntityType {
    @SerialName("household") HOUSEHOLD,
    @SerialName("ledger_member") LEDGER_MEMBER,
    @SerialName("annual_savings_target") ANNUAL_SAVINGS_TARGET,
    @SerialName("plan_catalog_item") PLAN_CATALOG_ITEM,
    @SerialName("monthly_plan_item") MONTHLY_PLAN_ITEM,
    @SerialName("expense_category") EXPENSE_CATEGORY,
    @SerialName("expense_record") EXPENSE_RECORD,
    @SerialName("salary_income") SALARY_INCOME,
    @SerialName("salary_allocation_category") SALARY_ALLOCATION_CATEGORY,
    @SerialName("salary_allocation_deduction") SALARY_ALLOCATION_DEDUCTION,
    @SerialName("salary_allocation_child") SALARY_ALLOCATION_CHILD,
    @SerialName("salary_allocation_grandchild") SALARY_ALLOCATION_GRANDCHILD,
    @SerialName("fixed_cost_item") FIXED_COST_ITEM,
    @SerialName("asset") ASSET,
    @SerialName("asset_valuation") ASSET_VALUATION,
    @SerialName("asset_growth_rule") ASSET_GROWTH_RULE,
    @SerialName("maintenance_fee_month") MAINTENANCE_FEE_MONTH,
}

@Serializable
data class SyncMutationDto(
    val mutationId: UuidString,
    val entityType: SyncEntityType,
    val entityId: UuidString,
    val operation: SyncOperation,
    val baseVersion: Long?,
    val payload: JsonObject? = null,
) {
    init {
        require(baseVersion == null || baseVersion >= 0) { "baseVersion must be null or non-negative" }
        require(operation != SyncOperation.UPSERT || payload != null) { "upsert requires a payload" }
        require(operation != SyncOperation.DELETE || payload == null) { "delete must not include a payload" }
        require(payload == null || payload.toString().encodeToByteArray().size <= MAX_SYNC_PAYLOAD_BYTES) {
            "payload must contain at most $MAX_SYNC_PAYLOAD_BYTES UTF-8 bytes"
        }
    }
}

@Serializable
data class SyncPushRequest(
    val deviceId: UuidString,
    val mutations: List<SyncMutationDto>,
) {
    init {
        require(mutations.isNotEmpty()) { "mutations must not be empty" }
        require(mutations.size <= MAX_SYNC_MUTATIONS_PER_PUSH) {
            "mutations must contain at most $MAX_SYNC_MUTATIONS_PER_PUSH items"
        }
        require(mutations.map { it.mutationId }.distinct().size == mutations.size) {
            "mutationId must be unique within a push"
        }
    }
}

@Serializable
data class AppliedMutationDto(
    val mutationId: UuidString,
    val entityId: UuidString,
    val version: Long,
) {
    init {
        require(version > 0) { "version must be positive" }
    }
}

@Serializable
data class SyncPushResponse(
    val applied: List<AppliedMutationDto>,
    val currentCursor: Long,
) {
    init {
        require(currentCursor >= 0) { "currentCursor must be non-negative" }
        require(applied.map(AppliedMutationDto::mutationId).distinct().size == applied.size) {
            "applied mutation ids must be unique"
        }
    }
}

@Serializable
data class SyncChangeDto(
    val cursor: Long,
    val entityType: SyncEntityType,
    val entityId: UuidString,
    val operation: SyncOperation,
    val version: Long,
    val payload: JsonObject? = null,
    val changedAt: UtcInstantString,
) {
    init {
        require(cursor > 0) { "cursor must be positive" }
        require(version > 0) { "version must be positive" }
        require(operation != SyncOperation.UPSERT || payload != null) { "upsert requires a payload" }
        require(operation != SyncOperation.DELETE || payload == null) { "delete must not include a payload" }
    }
}

@Serializable
data class SyncPullResponse(
    val changes: List<SyncChangeDto>,
    val nextCursor: Long,
    val hasMore: Boolean,
) {
    init {
        require(nextCursor >= 0) { "nextCursor must be non-negative" }
        require(changes.zipWithNext().all { (left, right) -> left.cursor < right.cursor }) {
            "changes must be ordered by cursor"
        }
        require(changes.lastOrNull()?.cursor?.let { it <= nextCursor } != false) {
            "nextCursor must not precede the last change"
        }
    }
}

const val MAX_SYNC_MUTATIONS_PER_PUSH = 100
const val MAX_SYNC_PAYLOAD_BYTES = 65_536
const val DEFAULT_SYNC_PULL_LIMIT = 200
const val MAX_SYNC_PULL_LIMIT = 500
