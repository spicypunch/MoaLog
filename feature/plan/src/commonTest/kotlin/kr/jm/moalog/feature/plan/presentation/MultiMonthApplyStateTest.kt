package kr.jm.moalog.feature.plan.presentation

import kr.jm.moalog.core.database.ExistingPlanPolicy
import kr.jm.moalog.core.database.PlanCopyPreview
import kr.jm.moalog.core.database.PlanCopyPreviewRow
import kr.jm.moalog.core.database.PlanCopyRequest
import kr.jm.moalog.core.database.PlanCopyResult
import kr.jm.moalog.core.database.PlanCopyTargetPreview
import kr.jm.moalog.core.model.MonthlyPlanItem
import kr.jm.moalog.core.model.PlanItemStatus
import kr.jm.moalog.core.model.PlanItemType
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.plan.domain.PlanMonth
import kr.jm.moalog.feature.plan.domain.PlanRepository
import kr.jm.moalog.feature.plan.domain.PlanYearMonth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MultiMonthApplyStateTest {
    @Test
    fun sourceMonthCannotBeSelectedAndYearBoundaryIsGuarded() = runTest {
        val holder = MultiMonthApplyStateHolder(args, FakeRepository(), this)

        holder.onAction(MultiMonthApplyAction.ToggleMonth(args.sourceMonth))
        assertTrue(holder.state.value.selectedMonths.isEmpty())

        holder.onAction(MultiMonthApplyAction.SelectDisplayedYear)
        assertEquals(11, holder.state.value.selectedMonths.size)
        assertFalse(args.sourceMonth in holder.state.value.selectedMonths)

        repeat(200) { holder.onAction(MultiMonthApplyAction.PreviousYear) }
        assertEquals(YearMonthKey.MIN_YEAR, holder.state.value.displayedYear)
        holder.close()
    }

    @Test
    fun previewPolicyAndPartialResultRemainExplicit() = runTest {
        val repository = FakeRepository()
        val holder = MultiMonthApplyStateHolder(args, repository, this)
        val target = YearMonthKey(2026, 6)

        holder.start()
        advanceUntilIdle()
        holder.onAction(MultiMonthApplyAction.ToggleMonth(target))
        holder.onAction(MultiMonthApplyAction.Continue)
        advanceUntilIdle()

        assertEquals(MultiMonthApplyStep.Preview, holder.state.value.step)
        assertEquals(1, holder.state.value.preview?.conflictCount)
        assertEquals(ExistingPlanPolicy.KeepExisting, holder.state.value.policy)

        holder.onAction(MultiMonthApplyAction.RequestApply)
        assertTrue(holder.state.value.showApplyConfirmation)
        holder.onAction(MultiMonthApplyAction.ConfirmApply)
        advanceUntilIdle()

        assertEquals(MultiMonthApplyStep.Complete, holder.state.value.step)
        assertEquals(1, holder.state.value.result?.skippedConflictCount)
        assertEquals(0, holder.state.value.result?.insertedItemCount)
        assertEquals(ExistingPlanPolicy.KeepExisting, repository.lastRequest?.policy)
        holder.close()
    }

    @Test
    fun missingSourceItemBlocksPreviewAndRetryLoadsItAgain() = runTest {
        val repository = FakeRepository().apply { findResult = null }
        val holder = MultiMonthApplyStateHolder(args, repository, this)
        val target = YearMonthKey(2026, 6)

        holder.start()
        advanceUntilIdle()
        holder.onAction(MultiMonthApplyAction.ToggleMonth(target))
        holder.onAction(MultiMonthApplyAction.Continue)

        assertTrue(holder.state.value.initialLoadFailed)
        assertFalse(holder.state.value.canContinue)
        assertEquals(0, repository.previewCalls)

        repository.findResult = source
        holder.onAction(MultiMonthApplyAction.Retry)
        advanceUntilIdle()

        assertFalse(holder.state.value.initialLoadFailed)
        assertEquals("관리비", holder.state.value.sourceItemName)
        assertTrue(holder.state.value.canContinue)
        assertEquals(2, repository.findCalls)
        holder.close()
    }

    @Test
    fun actionsFromWrongStepAndDuplicateRequestsAreIgnored() = runTest {
        val repository = FakeRepository()
        val previewGate = CompletableDeferred<Unit>()
        repository.previewGate = previewGate
        val holder = MultiMonthApplyStateHolder(args.copy(sourceItemId = null), repository, this)
        val target = YearMonthKey(2026, 6)
        holder.start()
        holder.onAction(MultiMonthApplyAction.ToggleMonth(target))

        holder.onAction(MultiMonthApplyAction.SelectPolicy(ExistingPlanPolicy.Overwrite))
        holder.onAction(MultiMonthApplyAction.RequestApply)
        holder.onAction(MultiMonthApplyAction.ConfirmApply)
        assertEquals(ExistingPlanPolicy.KeepExisting, holder.state.value.policy)
        assertEquals(0, repository.applyCalls)

        holder.onAction(MultiMonthApplyAction.Continue)
        holder.onAction(MultiMonthApplyAction.Continue)
        runCurrent()
        assertEquals(1, repository.previewCalls)
        previewGate.complete(Unit)
        advanceUntilIdle()

        holder.onAction(MultiMonthApplyAction.RequestApply)
        holder.onAction(MultiMonthApplyAction.ConfirmApply)
        holder.onAction(MultiMonthApplyAction.ConfirmApply)
        advanceUntilIdle()
        assertEquals(1, repository.applyCalls)
        holder.close()
    }

    @Test
    fun restoredPreviewReloadsAndSanitizesSourceMonth() = runTest {
        val repository = FakeRepository()
        val holder = MultiMonthApplyStateHolder(args, repository, this)
        val target = YearMonthKey(2027, 2)

        holder.onAction(
            MultiMonthApplyAction.RestoreDraft(
                MultiMonthApplyDraftSnapshot(
                    displayedYear = 10_500,
                    selectedMonths = listOf(args.sourceMonth, target),
                    policy = ExistingPlanPolicy.Overwrite,
                    previewRequested = true,
                ),
            ),
        )
        holder.start()
        advanceUntilIdle()

        assertEquals(9999, holder.state.value.displayedYear)
        assertEquals(setOf(target), holder.state.value.selectedMonths)
        assertEquals(ExistingPlanPolicy.Overwrite, holder.state.value.policy)
        assertEquals(MultiMonthApplyStep.Preview, holder.state.value.step)
        assertEquals(1, repository.previewCalls)
        holder.close()
    }

    @Test
    fun previewFailureKeepsSelectionAndDoesNotAllowApply() = runTest {
        val repository = FakeRepository().apply { previewFailure = true }
        val holder = MultiMonthApplyStateHolder(args.copy(sourceItemId = null), repository, this)
        val target = YearMonthKey(2026, 6)
        holder.start()
        holder.onAction(MultiMonthApplyAction.ToggleMonth(target))
        holder.onAction(MultiMonthApplyAction.Continue)
        advanceUntilIdle()

        assertEquals(MultiMonthApplyStep.SelectMonths, holder.state.value.step)
        assertEquals(setOf(target), holder.state.value.selectedMonths)
        assertNull(holder.state.value.preview)
        assertFalse(holder.state.value.canApply)
        assertEquals("변경 내용을 불러오지 못했어요", holder.state.value.error)
        holder.close()
    }

    private class FakeRepository : PlanRepository {
        var lastRequest: PlanCopyRequest? = null
        var findResult: MonthlyPlanItem? = source
        var findCalls = 0
        var previewCalls = 0
        var applyCalls = 0
        var previewFailure = false
        var previewGate: CompletableDeferred<Unit>? = null
        override fun observeMonth(month: YearMonthKey): Flow<PlanMonth> = flowOf(PlanMonth(emptyList(), 0))
        override fun observeYear(year: Int): Flow<List<PlanYearMonth>> = flowOf(emptyList())
        override suspend fun find(id: Long): MonthlyPlanItem? {
            findCalls += 1
            return findResult
        }
        override suspend fun save(item: MonthlyPlanItem): Long = item.id
        override suspend fun delete(id: Long) = Unit
        override suspend fun previewCopy(request: PlanCopyRequest): PlanCopyPreview {
            previewCalls += 1
            previewGate?.await()
            if (previewFailure) error("preview failed")
            lastRequest = request
            return PlanCopyPreview(
                sourceItems = listOf(source),
                targetMonths = request.targetMonths.map { month ->
                    PlanCopyTargetPreview(month, listOf(PlanCopyPreviewRow(source, source.copy(id = 2, attributionMonth = month, amountWon = 900))))
                },
            )
        }
        override suspend fun copyToMonths(request: PlanCopyRequest): PlanCopyResult {
            applyCalls += 1
            lastRequest = request
            return PlanCopyResult(request.targetMonths.size, 0, 0, 1)
        }
    }

    companion object {
        private val args = MultiMonthApplyArgs(YearMonthKey(2026, 5), PlanItemType.FixedExpense, 1L)
        private val source = MonthlyPlanItem(
            id = 1,
            type = PlanItemType.FixedExpense,
            attributionMonth = args.sourceMonth,
            name = "관리비",
            amountWon = 1000,
            category = "주거",
            status = PlanItemStatus.Estimated,
            ownerMemberOrder = null,
            memo = null,
        )
    }
}
