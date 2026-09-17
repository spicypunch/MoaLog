package kr.jm.moalog.feature.plan.presentation

import kr.jm.moalog.core.database.PlanCopyPreview
import kr.jm.moalog.core.database.PlanCopyRequest
import kr.jm.moalog.core.database.PlanCopyResult
import kr.jm.moalog.core.model.MonthlyPlanItem
import kr.jm.moalog.core.model.PlanItemStatus
import kr.jm.moalog.core.model.PlanItemType
import kr.jm.moalog.core.model.PlanCatalogItem
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.plan.domain.PlanMonth
import kr.jm.moalog.feature.plan.domain.PlanRepository
import kr.jm.moalog.feature.plan.domain.PlanYearMonth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlanEditorStateTest {
    @Test
    fun activeCatalogCanBeSelectedAndArchivedCatalogCannotBeAttachedToNewMonth() = runTest {
        val repository = FakePlanRepository(
            catalog = listOf(
                PlanCatalogItem(7, type = PlanItemType.Income, classification = "급여", name = "월급", ownerMemberOrder = 1),
                PlanCatalogItem(8, type = PlanItemType.Income, classification = "기타", name = "보관 수입", ownerMemberOrder = null, archived = true),
            ),
        )
        val holder = holder(repository)
        holder.start()
        advanceUntilIdle()

        holder.onAction(PlanEditorAction.CatalogSelected(8))
        assertEquals(null, holder.state.value.selectedCatalogId)
        holder.onAction(PlanEditorAction.CatalogSelected(7))
        assertEquals(7, holder.state.value.selectedCatalogId)
        assertEquals("월급", holder.state.value.name)
        assertEquals("급여", holder.state.value.category)
        assertEquals(1, holder.state.value.ownerMemberOrder)
        holder.onAction(PlanEditorAction.AmountChanged("3300000"))
        holder.onAction(PlanEditorAction.Save)
        advanceUntilIdle()

        assertEquals(7, repository.saved?.catalogId)
        holder.close()
    }

    @Test
    fun matchingActiveCatalogIsReusedForDirectInput() = runTest {
        val repository = FakePlanRepository(
            catalog = listOf(PlanCatalogItem(9, type = PlanItemType.Income, classification = "급여", name = "월급", ownerMemberOrder = null)),
        )
        val holder = holder(repository)
        holder.start()
        advanceUntilIdle()
        fillRequired(holder)

        holder.onAction(PlanEditorAction.Save)
        advanceUntilIdle()

        assertEquals(9, repository.saved?.catalogId)
        holder.close()
    }

    @Test
    fun selectedSavingsCatalogOwnerSurvivesDraftRestoration() = runTest {
        val catalog = PlanCatalogItem(
            12,
            type = PlanItemType.Savings,
            classification = "예적금",
            name = "여행 적금",
            ownerMemberOrder = 2,
            includePurposeAccount = true,
        )
        val repository = FakePlanRepository(catalog = listOf(catalog))
        val holder = holder(repository, type = PlanItemType.Savings)
        holder.onAction(PlanEditorAction.RestoreDraft(PlanEditorDraftSnapshot(
            type = PlanItemType.Savings,
            month = "2026-05",
            name = catalog.name,
            amount = "100000",
            category = catalog.classification,
            status = PlanItemStatus.Estimated,
            ownerMemberOrder = 2,
            memo = "",
            includePurposeAccount = true,
            includeNetSavings = false,
            errors = PlanEditorErrors(),
            showDiscardConfirmation = false,
            hasUnsavedChanges = true,
            selectedCatalogId = catalog.id,
        )))
        holder.start()
        advanceUntilIdle()

        assertEquals(2, holder.state.value.ownerMemberOrder)
        holder.onAction(PlanEditorAction.Save)
        advanceUntilIdle()
        assertEquals(2, repository.saved?.ownerMemberOrder)
        assertEquals(12, repository.saved?.catalogId)
        holder.close()
    }

    @Test
    fun archivedHistoricalCatalogCannotUseSaveAndApply() = runTest {
        val catalog = PlanCatalogItem(15, type = PlanItemType.Income, classification = "급여", name = "월급", ownerMemberOrder = 0, archived = true)
        val existing = item(id = 51, type = PlanItemType.Income, name = "월급", amount = 3_000_000, category = "급여", owner = 0, catalogId = 15)
        val repository = FakePlanRepository(found = existing, catalog = listOf(catalog))
        val holder = holder(repository, itemId = 51)
        holder.start()
        advanceUntilIdle()

        holder.onAction(PlanEditorAction.SaveAndApply)

        assertEquals(0, repository.saveCalls)
        assertTrue(holder.state.value.selectedCatalogIsArchived)
        assertTrue(holder.state.value.persistenceError.orEmpty().contains("보관된"))
        holder.close()
    }

    @Test
    fun saveAndApplyClampsMemoGuardsDuplicatesAndReturnsSavedRouteMetadata() = runTest {
        val repository = FakePlanRepository(saveResult = 71, saveGate = CompletableDeferred())
        val holder = holder(repository)
        holder.start()
        advanceUntilIdle()

        holder.onAction(PlanEditorAction.TypeChanged(PlanItemType.Savings))
        holder.onAction(PlanEditorAction.NameChanged("주택청약"))
        holder.onAction(PlanEditorAction.AmountChanged("500,000"))
        holder.onAction(PlanEditorAction.CategoryChanged("청약"))
        holder.onAction(PlanEditorAction.MemoChanged("가".repeat(120)))
        holder.onAction(PlanEditorAction.PurposeChanged(true))
        holder.onAction(PlanEditorAction.NetChanged(true))
        holder.onAction(PlanEditorAction.SaveAndApply)
        holder.onAction(PlanEditorAction.SaveAndApply)
        runCurrent()

        assertEquals(1, repository.saveCalls)
        assertEquals(100, holder.state.value.memo.length)
        assertTrue(holder.state.value.isSaving)
        repository.saveGate?.complete(Unit)
        advanceUntilIdle()

        val completion = assertNotNull(holder.state.value.completion)
        assertEquals(PlanEditorCompletionKind.SavedAndApply, completion.kind)
        assertEquals(71, completion.itemId)
        assertEquals(PlanItemType.Savings, completion.type)
        assertEquals(YearMonthKey(2026, 5), completion.month)
        assertEquals(71, holder.state.value.savedItemId)
        assertTrue(holder.state.value.completed)
        assertFalse(holder.state.value.hasUnsavedChanges)
        assertEquals("가".repeat(100), repository.saved?.memo)
        holder.close()
    }

    @Test
    fun existingItemLoadsAndUpdatesWithItsPersistedType() = runTest {
        val existing = item(
            id = 42,
            type = PlanItemType.FixedExpense,
            month = YearMonthKey(2026, 6),
            name = "관리비",
            amount = 240_000,
            category = "주거/통신",
            owner = 2,
        )
        val repository = FakePlanRepository(found = existing, saveResult = 42)
        val holder = holder(repository, itemId = 42, type = PlanItemType.Income)
        holder.start()
        advanceUntilIdle()

        assertEquals(PlanItemType.FixedExpense, holder.state.value.type)
        assertEquals("관리비", holder.state.value.name)
        holder.onAction(PlanEditorAction.AmountChanged("250000"))
        holder.onAction(PlanEditorAction.Save)
        advanceUntilIdle()

        assertEquals(42, repository.saved?.id)
        assertEquals(250_000, repository.saved?.amountWon)
        assertEquals(PlanEditorCompletionKind.Saved, holder.state.value.completion?.kind)
        holder.close()
    }

    @Test
    fun saveFailurePreservesInputAndCanBeRetried() = runTest {
        val repository = FakePlanRepository(failSave = true, saveResult = 9)
        val holder = holder(repository)
        holder.start()
        advanceUntilIdle()
        fillRequired(holder)

        holder.onAction(PlanEditorAction.Save)
        advanceUntilIdle()
        assertEquals("월급", holder.state.value.name)
        assertEquals("3300000", holder.state.value.amount)
        assertNotNull(holder.state.value.persistenceError)
        assertFalse(holder.state.value.completed)

        repository.failSave = false
        holder.onAction(PlanEditorAction.Save)
        advanceUntilIdle()
        assertEquals(2, repository.saveCalls)
        assertEquals(9, holder.state.value.savedItemId)
        holder.close()
    }

    @Test
    fun discardAndDeleteConfirmationsAreExplicitAndDeleteIsGuarded() = runTest {
        val existing = item(id = 5, type = PlanItemType.Income, name = "월급", amount = 1, category = "급여")
        val repository = FakePlanRepository(found = existing, deleteGate = CompletableDeferred())
        val holder = holder(repository, itemId = 5)
        holder.start()
        advanceUntilIdle()

        holder.onAction(PlanEditorAction.NameChanged("바뀐 월급"))
        holder.onAction(PlanEditorAction.RequestBack)
        assertTrue(holder.state.value.showDiscardConfirmation)
        assertFalse(holder.state.value.exitRequested)
        holder.onAction(PlanEditorAction.DismissDiscard)
        holder.onAction(PlanEditorAction.RequestBack)
        holder.onAction(PlanEditorAction.ConfirmDiscard)
        assertTrue(holder.state.value.exitRequested)

        val deleteHolder = holder(repository, itemId = 5)
        deleteHolder.start()
        advanceUntilIdle()
        deleteHolder.onAction(PlanEditorAction.RequestDelete)
        assertTrue(deleteHolder.state.value.showDeleteConfirmation)
        deleteHolder.onAction(PlanEditorAction.ConfirmDelete)
        deleteHolder.onAction(PlanEditorAction.ConfirmDelete)
        runCurrent()
        assertEquals(1, repository.deleteCalls)
        repository.deleteGate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(PlanEditorCompletionKind.Deleted, deleteHolder.state.value.completion?.kind)
        assertEquals(5, deleteHolder.state.value.completion?.itemId)
        holder.close()
        deleteHolder.close()
    }

    @Test
    fun typeMonthAndAmountCommandsRespectEditorRulesAndBoundaries() = runTest {
        val holder = holder(FakePlanRepository(), month = YearMonthKey(YearMonthKey.MIN_YEAR, 1))
        holder.start()
        advanceUntilIdle()
        holder.onAction(PlanEditorAction.PreviousMonth)
        assertEquals("${YearMonthKey.MIN_YEAR}-01", holder.state.value.month)
        holder.onAction(PlanEditorAction.AddAmount(100_000))
        assertEquals("100000", holder.state.value.amount)
        holder.onAction(PlanEditorAction.ResetAmount)
        assertEquals("", holder.state.value.amount)
        holder.onAction(PlanEditorAction.CategoryChanged("급여"))
        holder.onAction(PlanEditorAction.OwnerChanged(1))
        holder.onAction(PlanEditorAction.TypeChanged(PlanItemType.Savings))
        assertEquals("", holder.state.value.category)
        assertEquals(1, holder.state.value.ownerMemberOrder)
        holder.close()
    }

    @Test
    fun missingOrFailedInitialEditLoadBlocksMutationSaveAndDeleteUntilRetrySucceeds() = runTest {
        val missingRepository = FakePlanRepository(found = null)
        val missingHolder = holder(missingRepository, itemId = 44)
        missingHolder.start()
        advanceUntilIdle()
        assertTrue(missingHolder.state.value.initialLoadFailed)
        fillRequired(missingHolder)
        missingHolder.onAction(PlanEditorAction.Save)
        missingHolder.onAction(PlanEditorAction.RequestDelete)
        advanceUntilIdle()
        assertEquals(0, missingRepository.saveCalls)
        assertEquals(0, missingRepository.deleteCalls)
        assertFalse(missingHolder.state.value.showDeleteConfirmation)

        val recovered = item(id = 44, type = PlanItemType.Income, name = "월급", amount = 10, category = "급여")
        missingRepository.found = recovered
        missingHolder.onAction(PlanEditorAction.RetryLoad)
        advanceUntilIdle()
        assertFalse(missingHolder.state.value.initialLoadFailed)
        assertEquals("월급", missingHolder.state.value.name)
        missingHolder.close()

        val throwingRepository = FakePlanRepository(found = recovered, failFind = true)
        val throwingHolder = holder(throwingRepository, itemId = 44)
        throwingHolder.start()
        advanceUntilIdle()
        assertTrue(throwingHolder.state.value.initialLoadFailed)
        throwingHolder.onAction(PlanEditorAction.Save)
        advanceUntilIdle()
        assertEquals(0, throwingRepository.saveCalls)
        throwingRepository.failFind = false
        throwingHolder.onAction(PlanEditorAction.RetryLoad)
        advanceUntilIdle()
        assertFalse(throwingHolder.state.value.initialLoadFailed)
        throwingHolder.close()
    }

    @Test
    fun queuedDraftRestoresOnlyAfterRepositoryLoadWithoutSavingOrCompleting() = runTest {
        val repository = FakePlanRepository(
            found = item(id = 12, type = PlanItemType.Income, name = "저장소 월급", amount = 100, category = "급여"),
        )
        val holder = holder(repository, itemId = 12)
        val snapshot = PlanEditorDraftSnapshot(
            type = PlanItemType.FixedExpense,
            month = "2026-07",
            name = "복원 관리비",
            amount = "240000",
            category = "주거/통신",
            status = PlanItemStatus.Confirmed,
            ownerMemberOrder = 2,
            memo = "복원된 메모",
            includePurposeAccount = true,
            includeNetSavings = true,
            errors = PlanEditorErrors(amount = "확인할 금액 오류"),
            showDiscardConfirmation = true,
            hasUnsavedChanges = true,
        )

        holder.onAction(PlanEditorAction.RestoreDraft(snapshot))
        assertTrue(holder.state.value.isLoading)
        assertEquals("", holder.state.value.name)
        holder.start()
        advanceUntilIdle()

        val restored = holder.state.value
        assertEquals(PlanItemType.FixedExpense, restored.type)
        assertEquals("2026-07", restored.month)
        assertEquals("복원 관리비", restored.name)
        assertEquals("240000", restored.amount)
        assertEquals(2, restored.ownerMemberOrder)
        assertFalse(restored.includePurposeAccount)
        assertFalse(restored.includeNetSavings)
        assertEquals("확인할 금액 오류", restored.errors.amount)
        assertTrue(restored.showDiscardConfirmation)
        assertTrue(restored.hasUnsavedChanges)
        assertFalse(restored.completed)
        assertEquals(0, repository.saveCalls)
        holder.close()
    }

    @Test
    fun deleteRequestIsIgnoredWhileExistingItemIsStillLoading() = runTest {
        val holder = holder(
            repository = FakePlanRepository(
                found = item(id = 19, type = PlanItemType.Income, name = "월급", amount = 10, category = "급여"),
            ),
            itemId = 19,
        )

        holder.onAction(PlanEditorAction.RequestDelete)

        assertTrue(holder.state.value.isLoading)
        assertFalse(holder.state.value.showDeleteConfirmation)
        holder.close()
    }

    @Test
    fun validationAttemptAdvancesOnlyForFailedSubmitNotForFieldErrorClearing() = runTest {
        val holder = holder(FakePlanRepository())
        holder.start()
        advanceUntilIdle()

        holder.onAction(PlanEditorAction.Save)
        assertEquals(1, holder.state.value.validationAttempt)
        assertNotNull(holder.state.value.errors.name)

        holder.onAction(PlanEditorAction.NameChanged("월급"))
        assertEquals(1, holder.state.value.validationAttempt)
        assertEquals(null, holder.state.value.errors.name)

        holder.onAction(PlanEditorAction.SaveAndApply)
        assertEquals(2, holder.state.value.validationAttempt)
        holder.close()
    }

    private fun kotlinx.coroutines.test.TestScope.holder(
        repository: PlanRepository,
        itemId: Long? = null,
        type: PlanItemType = PlanItemType.Income,
        month: YearMonthKey = YearMonthKey(2026, 5),
    ) = PlanEditorStateHolder(PlanEditorArgs(itemId, type, month), repository, this)

    private fun fillRequired(holder: PlanEditorStateHolder) {
        holder.onAction(PlanEditorAction.NameChanged("월급"))
        holder.onAction(PlanEditorAction.AmountChanged("3300000"))
        holder.onAction(PlanEditorAction.CategoryChanged("급여"))
    }

    private fun item(
        id: Long,
        type: PlanItemType,
        month: YearMonthKey = YearMonthKey(2026, 5),
        name: String,
        amount: Long,
        category: String,
        owner: Int? = null,
        catalogId: Long = 0,
    ) = MonthlyPlanItem(
        id = id,
        type = type,
        attributionMonth = month,
        name = name,
        amountWon = amount,
        category = category,
        status = PlanItemStatus.Estimated,
        ownerMemberOrder = owner,
        memo = null,
        catalogId = catalogId,
    )

    private class FakePlanRepository(
        var found: MonthlyPlanItem? = null,
        private val saveResult: Long = 1,
        var failSave: Boolean = false,
        var failFind: Boolean = false,
        val saveGate: CompletableDeferred<Unit>? = null,
        val deleteGate: CompletableDeferred<Unit>? = null,
        val catalog: List<PlanCatalogItem> = emptyList(),
    ) : PlanRepository {
        var saved: MonthlyPlanItem? = null
        var saveCalls = 0
        var deleteCalls = 0

        override fun observeMonth(month: YearMonthKey): Flow<PlanMonth> = flowOf(PlanMonth(emptyList(), 0))
        override fun observeYear(year: Int): Flow<List<PlanYearMonth>> = flowOf(emptyList())
        override fun observeCatalog(): Flow<List<PlanCatalogItem>> = flowOf(catalog)
        override suspend fun previewCopy(request: PlanCopyRequest): PlanCopyPreview = error("unused")
        override suspend fun copyToMonths(request: PlanCopyRequest): PlanCopyResult = error("unused")
        override suspend fun find(id: Long): MonthlyPlanItem? {
            if (failFind) error("find failed")
            return found
        }
        override suspend fun save(item: MonthlyPlanItem): Long {
            saveCalls++
            saveGate?.await()
            if (failSave) error("save failed")
            saved = item
            return saveResult
        }
        override suspend fun delete(id: Long) {
            deleteCalls++
            deleteGate?.await()
        }
    }
}
