package kr.jm.moalog.server.sync.application

import kr.jm.moalog.core.contracts.AssetGrowthRuleSyncPayload
import kr.jm.moalog.core.contracts.AssetSyncPayload
import kr.jm.moalog.core.contracts.AssetValuationSyncPayload
import kr.jm.moalog.core.contracts.ExpenseCategorySyncPayload
import kr.jm.moalog.core.contracts.ExpenseRecordSyncPayload
import kr.jm.moalog.core.contracts.FixedCostItemSyncPayload
import kr.jm.moalog.core.contracts.MaintenanceFeeMonthSyncPayload
import kr.jm.moalog.core.contracts.MonthlyPlanItemSyncPayload
import kr.jm.moalog.core.contracts.PlanCatalogItemSyncPayload
import kr.jm.moalog.core.contracts.SalaryAllocationCategorySyncPayload
import kr.jm.moalog.core.contracts.SalaryAllocationChildSyncPayload
import kr.jm.moalog.core.contracts.SalaryAllocationDeductionSyncPayload
import kr.jm.moalog.core.contracts.SalaryAllocationGrandchildSyncPayload
import kr.jm.moalog.core.contracts.SalaryIncomeSyncPayload
import kr.jm.moalog.core.contracts.SyncPlanItemType
import kr.jm.moalog.core.contracts.SyncSalaryAllocationMethod
import kr.jm.moalog.server.household.infrastructure.LedgerMemberRepository
import kr.jm.moalog.server.sync.domain.CanonicalSyncMutation
import kr.jm.moalog.server.sync.domain.SyncAction
import kr.jm.moalog.server.sync.domain.SyncEntityKind
import kr.jm.moalog.server.sync.infrastructure.SyncEntitySnapshotEntity
import kr.jm.moalog.server.sync.infrastructure.SyncEntitySnapshotRepository
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class SyncPayloadValidator(
    private val snapshots: SyncEntitySnapshotRepository,
    private val ledgerMembers: LedgerMemberRepository,
) {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        explicitNulls = true
    }

    /**
     * Captures the active household graph once for one push request. Mutations are
     * validated and recorded in request order, so a later mutation sees every
     * earlier mutation from the same batch without querying or decoding snapshots
     * again.
     */
    fun context(householdId: UUID): ValidationContext = ValidationContext(
        snapshotEntities = snapshots.findAllByHouseholdIdAndDeletedFalse(householdId),
        memberOrders = ledgerMembers.findAllByHouseholdIdOrderByOrderAsc(householdId)
            .mapTo(mutableSetOf()) { it.order },
    )

    inner class ValidationContext internal constructor(
        snapshotEntities: List<SyncEntitySnapshotEntity>,
        private val memberOrders: Set<Int>,
    ) {
        private val nodes = snapshotEntities.associateTo(mutableMapOf()) { snapshot ->
            val key = EntityKey(snapshot.entityType, snapshot.entityId)
            key to Node(key, decode(snapshot.entityType, snapshot.payloadJson))
        }
        private val logicalOwners = mutableMapOf<LogicalKey, EntityKey>()
        private val referencesByTarget = mutableMapOf<EntityKey, MutableSet<EntityKey>>()

        init {
            nodes.values.forEach(::index)
        }

        /** Records the in-memory graph only after persistence succeeds. */
        fun <T> validateAndApply(mutation: CanonicalSyncMutation, apply: () -> T): T {
            val key = EntityKey(mutation.entityType, mutation.entityId)
            val payload = if (mutation.operation == SyncAction.DELETE) {
                validateDelete(key)
                null
            } else {
                val decoded = decode(
                    mutation.entityType,
                    mutation.canonicalPayload ?: throw InvalidSyncPayloadException(),
                )
                validateUpsert(key, decoded)
                decoded
            }

            return apply().also {
                if (payload == null) remove(key) else replace(key, payload)
            }
        }

        private fun validateUpsert(key: EntityKey, payload: Any) {
            val previousPayload = nodes[key]?.payload
            validateMemberReference(payload)
            validateEntityReferences(
                payload = payload,
                previousPayload = previousPayload,
                override = null,
            )
            validateLogicalUniqueness(key, payload)

            // Revalidate current children against the prospective parent value.
            // This prevents a parent update from leaving an already stored child
            // with an invalid type, month, member, or deduction relationship.
            val override = PayloadOverride(key, payload)
            referencesByTarget[key].orEmpty().toList().forEach { dependentKey ->
                val dependent = nodes[dependentKey] ?: return@forEach
                validateEntityReferences(
                    payload = dependent.payload,
                    previousPayload = dependent.payload,
                    override = override,
                )
            }
        }

        private fun validateDelete(key: EntityKey) {
            if (referencesByTarget[key].orEmpty().isNotEmpty()) {
                throw InvalidSyncPayloadException()
            }
        }

        private fun validateMemberReference(payload: Any) {
            val memberOrder = when (payload) {
                is PlanCatalogItemSyncPayload -> payload.ownerMemberOrder
                is SalaryIncomeSyncPayload -> payload.memberOrder
                is SalaryAllocationCategorySyncPayload -> payload.sourceMemberOrder
                is FixedCostItemSyncPayload -> payload.payerMemberOrder
                is AssetSyncPayload -> payload.ownerMemberOrder
                else -> null
            } ?: return
            if (memberOrder !in memberOrders) throw InvalidSyncPayloadException()
        }

        private fun validateEntityReferences(
            payload: Any,
            previousPayload: Any?,
            override: PayloadOverride?,
        ) {
            when (payload) {
                is MonthlyPlanItemSyncPayload -> {
                    val catalog = requirePayload<PlanCatalogItemSyncPayload>(
                        SyncEntityKind.PLAN_CATALOG_ITEM,
                        payload.catalogId.uuid(),
                        override,
                    )
                    if (catalog.archived && previousPayload == null) throw InvalidSyncPayloadException()
                    if (catalog.type != SyncPlanItemType.SAVINGS && payload.amountWon?.let { it < 0 } == true) {
                        throw InvalidSyncPayloadException()
                    }
                }

                is ExpenseRecordSyncPayload -> {
                    val category = requirePayload<ExpenseCategorySyncPayload>(
                        SyncEntityKind.EXPENSE_CATEGORY,
                        payload.categoryId.uuid(),
                        override,
                    )
                    val previousCategoryId = (previousPayload as? ExpenseRecordSyncPayload)?.categoryId
                    val retainsCategory = previousCategoryId == payload.categoryId
                    if (!retainsCategory && (category.archived || category.name != payload.categoryName)) {
                        throw InvalidSyncPayloadException()
                    }
                }

                is SalaryAllocationDeductionSyncPayload -> {
                    val source = requirePayload<SalaryAllocationCategorySyncPayload>(
                        SyncEntityKind.SALARY_ALLOCATION_CATEGORY,
                        payload.categoryId.uuid(),
                        override,
                    )
                    val target = requirePayload<SalaryAllocationCategorySyncPayload>(
                        SyncEntityKind.SALARY_ALLOCATION_CATEGORY,
                        payload.deductedCategoryId.uuid(),
                        override,
                    )
                    if (
                        source.method != SyncSalaryAllocationMethod.REMAINING_FROM_SOURCE ||
                        target.method == SyncSalaryAllocationMethod.REMAINING_FROM_SOURCE ||
                        source.attributionMonth != target.attributionMonth ||
                        source.sourceMemberOrder != target.sourceMemberOrder
                    ) {
                        throw InvalidSyncPayloadException()
                    }
                }

                is SalaryAllocationChildSyncPayload -> {
                    val parent = requirePayload<SalaryAllocationCategorySyncPayload>(
                        SyncEntityKind.SALARY_ALLOCATION_CATEGORY,
                        payload.categoryId.uuid(),
                        override,
                    )
                    if (parent.attributionMonth != payload.attributionMonth) {
                        throw InvalidSyncPayloadException()
                    }
                }

                is SalaryAllocationGrandchildSyncPayload -> {
                    val parent = requirePayload<SalaryAllocationChildSyncPayload>(
                        SyncEntityKind.SALARY_ALLOCATION_CHILD,
                        payload.childId.uuid(),
                        override,
                    )
                    if (parent.attributionMonth != payload.attributionMonth) {
                        throw InvalidSyncPayloadException()
                    }
                }

                is AssetValuationSyncPayload -> requirePayload<AssetSyncPayload>(
                    SyncEntityKind.ASSET,
                    payload.assetId.uuid(),
                    override,
                )

                is AssetGrowthRuleSyncPayload -> requirePayload<AssetSyncPayload>(
                    SyncEntityKind.ASSET,
                    payload.assetId.uuid(),
                    override,
                )
            }
        }

        private fun validateLogicalUniqueness(key: EntityKey, payload: Any) {
            val logicalKey = logicalKey(key.entityType, payload) ?: return
            val owner = logicalOwners[logicalKey]
            if (owner != null && owner != key) throw InvalidSyncPayloadException()
        }

        private inline fun <reified T : Any> requirePayload(
            entityType: SyncEntityKind,
            entityId: UUID,
            override: PayloadOverride?,
        ): T {
            val key = EntityKey(entityType, entityId)
            val payload = if (override?.key == key) override.payload else nodes[key]?.payload
            return payload as? T ?: throw InvalidSyncPayloadException()
        }

        private fun replace(key: EntityKey, payload: Any) {
            remove(key)
            val node = Node(key, payload)
            nodes[key] = node
            index(node)
        }

        private fun remove(key: EntityKey) {
            val node = nodes.remove(key) ?: return
            logicalKey(node.key.entityType, node.payload)?.let { logicalKey ->
                if (logicalOwners[logicalKey] == key) logicalOwners.remove(logicalKey)
            }
            references(node.payload).forEach { target ->
                referencesByTarget[target]?.let { dependents ->
                    dependents.remove(key)
                    if (dependents.isEmpty()) referencesByTarget.remove(target)
                }
            }
        }

        private fun index(node: Node) {
            logicalKey(node.key.entityType, node.payload)?.let { logicalKey ->
                val previousOwner = logicalOwners.putIfAbsent(logicalKey, node.key)
                if (previousOwner != null && previousOwner != node.key) {
                    throw InvalidSyncPayloadException()
                }
            }
            references(node.payload).forEach { target ->
                referencesByTarget.getOrPut(target, ::mutableSetOf).add(node.key)
            }
        }
    }

    private fun references(payload: Any): Set<EntityKey> = when (payload) {
        is MonthlyPlanItemSyncPayload -> setOf(
            EntityKey(SyncEntityKind.PLAN_CATALOG_ITEM, payload.catalogId.uuid()),
        )

        is ExpenseRecordSyncPayload -> setOf(
            EntityKey(SyncEntityKind.EXPENSE_CATEGORY, payload.categoryId.uuid()),
        )

        is SalaryAllocationDeductionSyncPayload -> setOf(
            EntityKey(SyncEntityKind.SALARY_ALLOCATION_CATEGORY, payload.categoryId.uuid()),
            EntityKey(SyncEntityKind.SALARY_ALLOCATION_CATEGORY, payload.deductedCategoryId.uuid()),
        )

        is SalaryAllocationChildSyncPayload -> setOf(
            EntityKey(SyncEntityKind.SALARY_ALLOCATION_CATEGORY, payload.categoryId.uuid()),
        )

        is SalaryAllocationGrandchildSyncPayload -> setOf(
            EntityKey(SyncEntityKind.SALARY_ALLOCATION_CHILD, payload.childId.uuid()),
        )

        is AssetValuationSyncPayload -> setOf(
            EntityKey(SyncEntityKind.ASSET, payload.assetId.uuid()),
        )

        is AssetGrowthRuleSyncPayload -> setOf(
            EntityKey(SyncEntityKind.ASSET, payload.assetId.uuid()),
        )

        else -> emptySet()
    }

    private fun logicalKey(entityType: SyncEntityKind, payload: Any): LogicalKey? = when (payload) {
        is MonthlyPlanItemSyncPayload -> LogicalKey(
            entityType,
            listOf(payload.catalogId.value, payload.attributionMonth.key()),
        )

        is SalaryIncomeSyncPayload -> LogicalKey(
            entityType,
            listOf(payload.attributionMonth.key(), payload.memberOrder.toString()),
        )

        is SalaryAllocationDeductionSyncPayload -> LogicalKey(
            entityType,
            listOf(payload.categoryId.value, payload.deductedCategoryId.value),
        )

        is AssetValuationSyncPayload -> LogicalKey(
            entityType,
            listOf(payload.assetId.value, payload.valuationMonth.key()),
        )

        is AssetGrowthRuleSyncPayload -> LogicalKey(entityType, listOf(payload.assetId.value))
        is MaintenanceFeeMonthSyncPayload -> LogicalKey(entityType, listOf(payload.billMonth.key()))
        else -> null
    }

    private fun decode(type: SyncEntityKind, value: String?): Any = try {
        when (type) {
            SyncEntityKind.PLAN_CATALOG_ITEM -> json.decodeFromString<PlanCatalogItemSyncPayload>(value.orEmpty())
            SyncEntityKind.MONTHLY_PLAN_ITEM -> json.decodeFromString<MonthlyPlanItemSyncPayload>(value.orEmpty())
            SyncEntityKind.EXPENSE_CATEGORY -> json.decodeFromString<ExpenseCategorySyncPayload>(value.orEmpty())
            SyncEntityKind.EXPENSE_RECORD -> json.decodeFromString<ExpenseRecordSyncPayload>(value.orEmpty())
            SyncEntityKind.SALARY_INCOME -> json.decodeFromString<SalaryIncomeSyncPayload>(value.orEmpty())
            SyncEntityKind.SALARY_ALLOCATION_CATEGORY -> json.decodeFromString<SalaryAllocationCategorySyncPayload>(value.orEmpty())
            SyncEntityKind.SALARY_ALLOCATION_DEDUCTION -> json.decodeFromString<SalaryAllocationDeductionSyncPayload>(value.orEmpty())
            SyncEntityKind.SALARY_ALLOCATION_CHILD -> json.decodeFromString<SalaryAllocationChildSyncPayload>(value.orEmpty())
            SyncEntityKind.SALARY_ALLOCATION_GRANDCHILD -> json.decodeFromString<SalaryAllocationGrandchildSyncPayload>(value.orEmpty())
            SyncEntityKind.FIXED_COST_ITEM -> json.decodeFromString<FixedCostItemSyncPayload>(value.orEmpty())
            SyncEntityKind.ASSET -> json.decodeFromString<AssetSyncPayload>(value.orEmpty())
            SyncEntityKind.ASSET_VALUATION -> json.decodeFromString<AssetValuationSyncPayload>(value.orEmpty())
            SyncEntityKind.ASSET_GROWTH_RULE -> json.decodeFromString<AssetGrowthRuleSyncPayload>(value.orEmpty())
            SyncEntityKind.MAINTENANCE_FEE_MONTH -> json.decodeFromString<MaintenanceFeeMonthSyncPayload>(value.orEmpty())
            SyncEntityKind.HOUSEHOLD,
            SyncEntityKind.LEDGER_MEMBER,
            SyncEntityKind.ANNUAL_SAVINGS_TARGET,
            -> throw InvalidSyncPayloadException()
        }
    } catch (_: IllegalArgumentException) {
        throw InvalidSyncPayloadException()
    }

    private fun kr.jm.moalog.core.contracts.UuidString.uuid(): UUID = UUID.fromString(value)

    private fun kr.jm.moalog.core.contracts.SyncYearMonthPayload.key(): String = "$year:$month"

    private data class EntityKey(val entityType: SyncEntityKind, val entityId: UUID)
    private data class Node(val key: EntityKey, val payload: Any)
    private data class LogicalKey(val entityType: SyncEntityKind, val parts: List<String>)
    private data class PayloadOverride(val key: EntityKey, val payload: Any)
}
