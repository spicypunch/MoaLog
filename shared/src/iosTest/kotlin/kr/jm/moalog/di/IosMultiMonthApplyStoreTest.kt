package kr.jm.moalog.di

import kr.jm.moalog.core.database.ExistingPlanPolicy
import kr.jm.moalog.core.database.PlanCopyPreview
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
import kr.jm.moalog.feature.plan.presentation.MultiMonthApplyArgs
import kr.jm.moalog.feature.plan.presentation.MultiMonthApplyStateHolder
import kr.jm.moalog.feature.plan.presentation.MultiMonthApplyStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosMultiMonthApplyStoreTest {
    @Test
    fun exposesSelectionPreviewPolicyAndApplyWithoutSealedActions() = runBlocking {
        val repository = FakeRepository()
        val store = IosMultiMonthApplyStore(
            MultiMonthApplyStateHolder(
                args = MultiMonthApplyArgs(YearMonthKey(2026, 5), PlanItemType.Income, 10),
                repository = repository,
                parentScope = this,
            ),
        )
        val observed = mutableListOf<MultiMonthApplyStep>()
        val observation = store.observe { observed += it.step }
        repeat(10) { yield() }

        store.toggleMonth(2026, 6)
        store.toggleMonth(YearMonthKey.MIN_YEAR - 1, 1)
        store.continueToPreview()
        repeat(20) { yield() }

        assertEquals(MultiMonthApplyStep.Preview, store.currentState.step)
        assertEquals(listOf(YearMonthKey(2026, 6)), repository.previewRequest?.targetMonths)

        store.overwrite()
        store.requestApply()
        assertTrue(store.currentState.showApplyConfirmation)
        store.confirmApply()
        repeat(20) { yield() }

        assertEquals(ExistingPlanPolicy.Overwrite, repository.applyRequest?.policy)
        assertEquals(MultiMonthApplyStep.Complete, store.currentState.step)
        assertTrue(observed.contains(MultiMonthApplyStep.Preview))
        assertTrue(observed.contains(MultiMonthApplyStep.Complete))

        observation.cancel()
        store.close()
        store.previousYear()
        assertEquals(2026, store.currentState.displayedYear)
    }

    @Test
    fun restoresSelectionPolicyAndPreviewThroughIosSnapshotBridge() = runBlocking {
        val repository = FakeRepository()
        val store = IosMultiMonthApplyStore(
            MultiMonthApplyStateHolder(
                args = MultiMonthApplyArgs(YearMonthKey(2026, 5), PlanItemType.Income, 10),
                repository = repository,
                parentScope = this,
            ),
        )
        repeat(20) { yield() }

        store.restoreDraft(
            displayedYear = 2027,
            selectedMonths = listOf(YearMonthKey(2026, 6), YearMonthKey(2027, 1)),
            overwrite = true,
            previewRequested = true,
        )
        repeat(30) { yield() }

        assertEquals(2027, store.currentState.displayedYear)
        assertEquals(ExistingPlanPolicy.Overwrite, store.currentState.policy)
        assertEquals(MultiMonthApplyStep.Preview, store.currentState.step)
        assertEquals(
            listOf(YearMonthKey(2026, 6), YearMonthKey(2027, 1)),
            repository.previewRequest?.targetMonths,
        )
        val snapshot = store.draftSnapshot()
        assertTrue(snapshot.previewRequested)
        assertEquals(2, snapshot.selectedMonths.size)
        store.close()
    }

    @Test
    fun resultSummaryMirrorsDaoInsertAndOverwriteCountsAndRecognizesAllSkipped() {
        val mixed = PlanCopyResult(
            targetMonthCount = 3,
            insertedItemCount = 3,
            overwrittenItemCount = 2,
            skippedConflictCount = 0,
        ).toIosMultiMonthApplyResultSummary()
        assertEquals(3, mixed.newlyInsertedItemCount)
        assertEquals(2, mixed.overwrittenItemCount)
        assertTrue(!mixed.allExistingKept)

        val allSkipped = PlanCopyResult(
            targetMonthCount = 3,
            insertedItemCount = 0,
            overwrittenItemCount = 0,
            skippedConflictCount = 3,
        ).toIosMultiMonthApplyResultSummary()
        assertEquals(0, allSkipped.newlyInsertedItemCount)
        assertEquals(3, allSkipped.keptItemCount)
        assertTrue(allSkipped.allExistingKept)
    }

    @Test
    fun previewRestoreFailureKeepsLaterSelectionEditsAsSelectionDraft() = runBlocking {
        val repository = FakeRepository().apply { failPreview = true }
        val store = IosMultiMonthApplyStore(
            MultiMonthApplyStateHolder(
                args = MultiMonthApplyArgs(YearMonthKey(2026, 5), PlanItemType.Income, 10),
                repository = repository,
                parentScope = this,
            ),
        )
        repeat(20) { yield() }

        store.restoreDraft(
            displayedYear = 2027,
            selectedMonths = listOf(YearMonthKey(2026, 6)),
            overwrite = true,
            previewRequested = true,
        )
        repeat(30) { yield() }

        assertEquals(MultiMonthApplyStep.SelectMonths, store.currentState.step)
        assertTrue(store.currentState.error != null)

        store.toggleMonth(2027, 2)
        val editedSnapshot = store.draftSnapshot()
        assertEquals(2027, editedSnapshot.displayedYear)
        assertEquals(ExistingPlanPolicy.Overwrite, editedSnapshot.policy)
        assertEquals(
            setOf(YearMonthKey(2026, 6), YearMonthKey(2027, 2)),
            editedSnapshot.selectedMonths.toSet(),
        )
        assertFalse(editedSnapshot.previewRequested)
        store.close()
    }

    private class FakeRepository : PlanRepository {
        var previewRequest: PlanCopyRequest? = null
        var applyRequest: PlanCopyRequest? = null
        var failPreview: Boolean = false

        private val item = MonthlyPlanItem(
            id = 10,
            type = PlanItemType.Income,
            attributionMonth = YearMonthKey(2026, 5),
            name = "월급",
            amountWon = 3_300_000,
            category = "급여",
            status = PlanItemStatus.Confirmed,
            ownerMemberOrder = null,
            memo = null,
        )

        override fun observeMonth(month: YearMonthKey): Flow<PlanMonth> = flowOf(PlanMonth(emptyList(), 0))
        override fun observeYear(year: Int): Flow<List<PlanYearMonth>> = flowOf(emptyList())
        override suspend fun previewCopy(request: PlanCopyRequest): PlanCopyPreview {
            previewRequest = request
            if (failPreview) error("preview failed")
            return PlanCopyPreview(listOf(item), request.targetMonths.map { PlanCopyTargetPreview(it, emptyList()) })
        }
        override suspend fun copyToMonths(request: PlanCopyRequest): PlanCopyResult {
            applyRequest = request
            return PlanCopyResult(request.targetMonths.size, request.targetMonths.size, 0, 0)
        }
        override suspend fun find(id: Long): MonthlyPlanItem? = item.takeIf { id == it.id }
        override suspend fun save(item: MonthlyPlanItem): Long = error("unused")
        override suspend fun delete(id: Long) = Unit
    }
}
