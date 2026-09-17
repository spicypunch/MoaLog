package kr.jm.moalog.feature.plan.presentation

import kr.jm.moalog.core.database.ExistingPlanPolicy
import kr.jm.moalog.core.database.FixedCostApplyResult
import kr.jm.moalog.core.database.FixedCostPlanPreview
import kr.jm.moalog.core.database.FixedCostPlanPreviewRow
import kr.jm.moalog.core.model.FixedCostCheckItem
import kr.jm.moalog.core.model.FixedCostCheckSheet
import kr.jm.moalog.core.model.MonthlyPlanItem
import kr.jm.moalog.core.model.PlanItemStatus
import kr.jm.moalog.core.model.PlanItemType
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.plan.domain.FixedCostCheckRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class FixedCostCheckStateTest {
    @Test fun savePreservesSelectedMonthAndZeroAmount() = runTest {
        val month = YearMonthKey(2026, 5)
        val repo = FakeFixedCostRepository(month)
        val holder = FixedCostCheckStateHolder(FixedCostCheckArgs(month, listOf(0, 1)), repo, this)
        holder.start(); advanceUntilIdle()
        holder.onAction(FixedCostCheckAction.OpenEditor())
        holder.onAction(FixedCostCheckAction.ChangeName("관리비"))
        holder.onAction(FixedCostCheckAction.ChangeAmount("0"))
        holder.onAction(FixedCostCheckAction.Save); advanceUntilIdle()
        assertEquals(0L, repo.saved?.amountWon)
        assertEquals(month, repo.saved?.attributionMonth)
        assertEquals(month, repo.observedMonth)
        holder.close()
    }

    @Test fun conflictWaitsForExplicitPolicyThenApplies() = runTest {
        val month = YearMonthKey(2026, 5)
        val item = FixedCostCheckItem(7, attributionMonth = month, payerMemberOrder = null, name = "관리비", amountWon = 250_000)
        val repo = FakeFixedCostRepository(month, listOf(item)).apply { conflict = true }
        val holder = FixedCostCheckStateHolder(FixedCostCheckArgs(month, listOf(0, 1)), repo, this)
        holder.start(); advanceUntilIdle()
        holder.onAction(FixedCostCheckAction.RequestApply); advanceUntilIdle()
        assertEquals(1, holder.state.value.conflictPreview?.conflictCount)
        assertEquals(null, repo.lastAppliedPolicy)
        holder.onAction(FixedCostCheckAction.ConfirmApply(ExistingPlanPolicy.KeepExisting)); advanceUntilIdle()
        assertEquals(ExistingPlanPolicy.KeepExisting, repo.lastAppliedPolicy)
        holder.close()
    }

    @Test fun requestApplyAutoAppliesWhenNoConflictAndShowsResultMessage() = runTest {
        val month = YearMonthKey(2026, 5)
        val item = FixedCostCheckItem(11, attributionMonth = month, payerMemberOrder = 0, name = "정기요금", amountWon = 120_000)
        val repo = FakeFixedCostRepository(month, listOf(item))
        val holder = FixedCostCheckStateHolder(FixedCostCheckArgs(month, listOf(0, 1)), repo, this)

        holder.start(); advanceUntilIdle()
        holder.onAction(FixedCostCheckAction.RequestApply); advanceUntilIdle()

        assertEquals(1, repo.applyCalls)
        assertEquals(ExistingPlanPolicy.KeepExisting, repo.lastAppliedPolicy)
        assertEquals(item, repo.lastAppliedItems?.single())
        assertEquals(month, repo.lastAppliedMonth)
        assertEquals("1개 항목을 계획에 가져왔어요", holder.state.value.resultMessage)
        assertNull(holder.state.value.conflictPreview)
        holder.close()
    }

    @Test fun requestApplyRequiresAtLeastOneSelectedItem() = runTest {
        val month = YearMonthKey(2026, 5)
        val repo = FakeFixedCostRepository(month)
        val holder = FixedCostCheckStateHolder(FixedCostCheckArgs(month, listOf(0, 1)), repo, this)

        holder.start(); advanceUntilIdle()
        holder.onAction(FixedCostCheckAction.RequestApply); advanceUntilIdle()

        assertEquals("계획에 가져올 항목을 선택해 주세요", holder.state.value.error)
        assertEquals(0, repo.applyCalls)
        holder.close()
    }

    @Test fun selectedTotalDoesNotWrapWhenAmountsOverflow() {
        val month = YearMonthKey(2026, 5)
        val state = FixedCostCheckUiState(
            month = month,
            sheet = FixedCostCheckSheet(
                month,
                listOf(
                    FixedCostCheckItem(1, attributionMonth = month, payerMemberOrder = null, name = "A", amountWon = Long.MAX_VALUE),
                    FixedCostCheckItem(2, attributionMonth = month, payerMemberOrder = null, name = "B", amountWon = 1),
                ),
            ),
            selectedIds = setOf(1, 2),
        )

        assertNull(state.selectedKnownTotalWonOrNull)
        assertEquals(true, state.hasSelectedTotalOverflow)
    }

    @Test fun rapidSaveTapsOnlyReachRepositoryOnce() = runTest {
        val month=YearMonthKey(2026,5);val repo=FakeFixedCostRepository(month);val holder=FixedCostCheckStateHolder(FixedCostCheckArgs(month,listOf(0,1)),repo,this)
        holder.onAction(FixedCostCheckAction.OpenEditor())
        holder.onAction(FixedCostCheckAction.ChangeName("관리비"));holder.onAction(FixedCostCheckAction.ChangeAmount("100000"))
        holder.onAction(FixedCostCheckAction.Save);holder.onAction(FixedCostCheckAction.Save)
        advanceUntilIdle()
        assertEquals(1,repo.saveCalls)
        holder.close()
    }
}

private class FakeFixedCostRepository(month: YearMonthKey, items: List<FixedCostCheckItem> = emptyList()) : FixedCostCheckRepository {
    private val flow = MutableStateFlow(FixedCostCheckSheet(month, items))
    var observedMonth: YearMonthKey? = null
    var saved: FixedCostCheckItem? = null
    var saveCalls=0
    var conflict = false
    var applyCalls = 0
    var lastAppliedPolicy: ExistingPlanPolicy? = null
    var lastAppliedMonth: YearMonthKey? = null
    var lastAppliedItems: List<FixedCostCheckItem>? = null
    override fun observe(month: YearMonthKey): Flow<FixedCostCheckSheet> { observedMonth = month; return flow }
    override suspend fun find(id: Long) = flow.value.items.firstOrNull { it.id == id }
    override suspend fun save(value: FixedCostCheckItem): Long { saveCalls++;saved = value; return if (value.id == 0L) 10 else value.id }
    override suspend fun delete(id: Long) = Unit
    override suspend fun preview(month: YearMonthKey, items: List<FixedCostCheckItem>): FixedCostPlanPreview = FixedCostPlanPreview(month, items.map { source ->
        FixedCostPlanPreviewRow(source, if (conflict) MonthlyPlanItem(type = PlanItemType.FixedExpense, attributionMonth = month, name = source.name, amountWon = 1, category = "고정비", status = PlanItemStatus.Estimated, ownerMemberOrder = source.payerMemberOrder, memo = null) else null)
    })
    override suspend fun apply(month: YearMonthKey, items: List<FixedCostCheckItem>, policy: ExistingPlanPolicy): FixedCostApplyResult {
        applyCalls += 1
        lastAppliedPolicy = policy
        lastAppliedMonth = month
        lastAppliedItems = items
        return FixedCostApplyResult(items.size, 0, 0)
    }
}
