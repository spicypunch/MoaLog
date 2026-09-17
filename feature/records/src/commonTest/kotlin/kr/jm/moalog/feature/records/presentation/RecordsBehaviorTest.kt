package kr.jm.moalog.feature.records.presentation

import kr.jm.moalog.core.database.DefaultExpenseCategories
import kr.jm.moalog.core.model.ExpenseCategory
import kr.jm.moalog.core.model.ExpenseRecord
import kr.jm.moalog.core.model.LocalDateKey
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.records.domain.CurrentMonthProvider
import kr.jm.moalog.feature.records.domain.CurrentDateProvider
import kr.jm.moalog.feature.records.domain.ExpenseMonth
import kr.jm.moalog.feature.records.domain.ExpenseRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordsBehaviorTest {
    private val january = YearMonthKey(2027, 1)
    private val february = YearMonthKey(2027, 2)
    private val living = ExpenseCategory("living", "생활비 초과", 0)
    private val date = ExpenseCategory("date", "데이트비", 1)

    @Test
    fun categoryAndFilteredSumsDoNotReplaceFullMonthTotals() {
        val snapshot = ExpenseMonth(
            categories = listOf(living, date),
            records = listOf(
                record(1, living, 120_000, overspent = true),
                record(2, living, 30_000),
                record(3, date, 80_000),
            ),
        )
        val state = buildRecordsState(january, snapshot, ExpenseFilters(categoryIds = setOf(living.id)))

        assertEquals(230_000, state.fullMonthTotalWon)
        assertEquals(120_000, state.fullMonthOverspentWon)
        assertEquals(150_000, state.categoryTotalsWon[living.id])
        assertEquals(150_000, state.filteredSubtotalWon)
        assertTrue(state.filters.hasSubtotalFilter)
    }

    @Test
    fun missingCategoryHasZeroTotalAndOverallCanBeRecoveredAfterFilterSelection() {
        val snapshot = ExpenseMonth(
            categories = listOf(living, date),
            records = listOf(record(1, date, 20_000), record(2, date, 5_000)),
        )

        val filtered = buildRecordsState(january, snapshot, ExpenseFilters(categoryIds = setOf(living.id)))
        assertTrue(filtered.filters.hasSubtotalFilter)
        assertEquals(0, filtered.filteredSubtotalWon)
        assertEquals(0, filtered.categoryTotalsWon[living.id])
        assertEquals(25_000, filtered.categoryTotalsWon[date.id])

        val cleared = buildRecordsState(january, snapshot, ExpenseFilters())
        assertFalse(cleared.filters.hasSubtotalFilter)
        assertEquals(25_000, cleared.filteredSubtotalWon)
    }

    @Test
    fun attributionMonthIsIndependentFromActualDateAndOverspendIsManual() {
        val expense = record(
            id = 1,
            category = living,
            amount = 363_000,
            overspent = true,
            actualDate = LocalDateKey(2027, 2, 3),
        )
        val januaryState = buildRecordsState(
            january,
            ExpenseMonth(listOf(living), listOf(expense)),
            ExpenseFilters(),
        )
        assertEquals(363_000, januaryState.fullMonthTotalWon)
        assertEquals(363_000, januaryState.fullMonthOverspentWon)
        assertNotEquals(expense.actualDate?.yearMonth, expense.attributionMonth)

        val toggled = expense.copy(overspent = false)
        val toggledState = buildRecordsState(
            january,
            ExpenseMonth(listOf(living), listOf(toggled)),
            ExpenseFilters(),
        )
        assertEquals(363_000, toggledState.fullMonthTotalWon)
        assertEquals(0, toggledState.fullMonthOverspentWon)
    }

    @Test
    fun missingAmountDiffersFromZeroAndInvalidValuesAreRejected() {
        assertIs<RequiredWonResult.Missing>(parseRequiredWon(""))
        assertEquals(RequiredWonResult.Valid(0), parseRequiredWon("0"))
        assertEquals(RequiredWonResult.Valid(1_200_000), parseRequiredWon("1,200,000"))
        assertEquals(RequiredWonResult.Valid(1_200_000), parseRequiredWon(" 1,200,000 "))
        assertIs<RequiredWonResult.Invalid>(parseRequiredWon("-1"))
        assertIs<RequiredWonResult.Invalid>(parseRequiredWon("999999999999999999999999"))
        assertNull(parseYearMonth("2027-13"))
        assertNull(parseLocalDate("2027-02-29"))
        assertEquals(LocalDateKey(2028, 2, 29), parseLocalDate("2028-02-29"))
    }

    @Test
    fun amountInputNormalizationAndDisplayFormattingRemainConsistent() {
        assertEquals("120000", normalizeAmountInput("120000"))
        assertEquals("120000", normalizeAmountInput("120,000"))
        assertEquals("1200000", normalizeAmountInput("1a2b0c0d0-0e0"))
        assertEquals("", normalizeAmountInput(""))

        assertEquals("120,000", formatAmountInput("120000"))
        assertEquals("1,234,567", formatAmountInput("1234567"))
        assertEquals("120,000", formatAmountInput("120,000"))
        assertEquals("", formatAmountInput(""))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun editorValidationCollectsExpectedErrorCount() = runTest {
        val repository = FakeExpenseRepository(january, listOf(living))
        val holder = ExpenseEditorStateHolder(ExpenseEditorArgs(null, january), repository, this)
        holder.start()
        advanceUntilIdle()

        holder.onAction(ExpenseEditorAction.AmountChanged(""))
        holder.onAction(ExpenseEditorAction.AttributionMonthChanged("2027-13"))
        holder.onAction(ExpenseEditorAction.ActualDateChanged("20270101"))
        holder.onAction(ExpenseEditorAction.Save)
        advanceUntilIdle()
        holder.close()

        val errors = holder.state.value.errors
        assertEquals(4, errors.count)
        assertEquals("금액을 입력해 주세요", errors.amount)
        assertEquals("카테고리를 선택해 주세요", errors.category)
        assertEquals("귀속월을 YYYY-MM 형식으로 입력해 주세요", errors.attributionMonth)
        assertEquals("실제 지출일을 YYYY-MM-DD 형식으로 입력해 주세요", errors.actualDate)
        assertTrue(holder.state.value.persistenceError == null)
        holder.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun doubleTappingSaveRunsOnlyOnePersistenceRequest() = runTest {
        val repository = FakeExpenseRepository(january, listOf(living))
        val holder = ExpenseEditorStateHolder(ExpenseEditorArgs(null, january), repository, this)
        holder.start()
        advanceUntilIdle()

        holder.onAction(ExpenseEditorAction.AmountChanged("120000"))
        holder.onAction(ExpenseEditorAction.CategorySelected(living.id))
        holder.onAction(ExpenseEditorAction.Save)
        holder.onAction(ExpenseEditorAction.Save)
        advanceUntilIdle()
        holder.close()

        assertEquals(1, repository.saveCallCount)
        assertEquals(1, repository.snapshot().records.size)
        assertTrue(holder.state.value.completed)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun newEntryOffersOnlyActiveCategories() = runTest {
        val archived = ExpenseCategory("archived", "이전 생활비", 2, archived = true)
        val repository = FakeExpenseRepository(january, listOf(living, archived))
        val holder = ExpenseEditorStateHolder(ExpenseEditorArgs(null, january), repository, this)

        holder.start()
        advanceUntilIdle()

        assertEquals(listOf(living.id), holder.state.value.categories.map { it.id })
        holder.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun existingEntryKeepsItsArchivedCategoryEditable() = runTest {
        val archived = ExpenseCategory("archived", "이전 생활비", 2, archived = true)
        val repository = FakeExpenseRepository(january, listOf(living, archived))
        val recordId = repository.saveRecord(record(0, archived, 12_000))
        val holder = ExpenseEditorStateHolder(ExpenseEditorArgs(recordId, january), repository, this)

        holder.start()
        advanceUntilIdle()

        assertEquals(archived.id, holder.state.value.categoryId)
        assertTrue(holder.state.value.categories.any { it.id == archived.id && it.archived })
        holder.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun saveFailurePreservesInputThenCanRetryOrDismiss() = runTest {
        val repository = FakeExpenseRepository(january, listOf(living, date))
        repository.failSave = true
        val holder = ExpenseEditorStateHolder(ExpenseEditorArgs(null, january), repository, this)
        holder.start()
        advanceUntilIdle()
        holder.onAction(ExpenseEditorAction.AmountChanged("120000"))
        holder.onAction(ExpenseEditorAction.CategorySelected(date.id))
        holder.onAction(ExpenseEditorAction.ActualDateChanged("2027-02-03"))
        holder.onAction(ExpenseEditorAction.DetailChanged("저녁 식사"))
        holder.onAction(ExpenseEditorAction.OverspentChanged(true))
        holder.onAction(ExpenseEditorAction.Save)
        advanceUntilIdle()

        assertEquals("120000", holder.state.value.amount)
        assertEquals(date.id, holder.state.value.categoryId)
        assertEquals("2027-02-03", holder.state.value.actualDate)
        assertEquals("저녁 식사", holder.state.value.detail)
        assertEquals(0, repository.snapshot().records.size)
        assertEquals("저장하지 못했어요. 다시 시도해 주세요.", holder.state.value.persistenceError)
        assertEquals(ExpensePersistenceOperation.Save, holder.state.value.failedOperation)

        holder.onAction(ExpenseEditorAction.DismissPersistenceError)
        assertNull(holder.state.value.persistenceError)
        assertNull(holder.state.value.failedOperation)
        holder.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun saveFailureCanRetryAndPersistAfterFix() = runTest {
        val repository = FakeExpenseRepository(january, listOf(living, date))
        repository.failSave = true
        val holder = ExpenseEditorStateHolder(ExpenseEditorArgs(null, january), repository, this)
        holder.start()
        advanceUntilIdle()
        holder.onAction(ExpenseEditorAction.AmountChanged("120000"))
        holder.onAction(ExpenseEditorAction.CategorySelected(date.id))
        holder.onAction(ExpenseEditorAction.ActualDateChanged("2027-02-03"))
        holder.onAction(ExpenseEditorAction.DetailChanged("저녁 식사"))
        holder.onAction(ExpenseEditorAction.OverspentChanged(true))
        holder.onAction(ExpenseEditorAction.Save)
        advanceUntilIdle()

        repository.failSave = false
        holder.onAction(ExpenseEditorAction.RetryPersistence)
        advanceUntilIdle()

        assertNull(holder.state.value.persistenceError)
        assertNull(holder.state.value.failedOperation)
        assertEquals("120000", holder.state.value.amount)
        assertEquals(1, repository.snapshot().records.size)
        assertTrue(holder.state.value.completed)
        holder.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun deleteRequestSuccessAndFailureRetriesWithSameUiFlow() = runTest {
        val repository = FakeExpenseRepository(january, listOf(living, date))
        val id = repository.saveRecord(record(0, date, 45_000, detail = "삭제 대상"))

        val successHolder = ExpenseEditorStateHolder(ExpenseEditorArgs(id, january), repository, this)
        successHolder.start()
        advanceUntilIdle()

        successHolder.onAction(ExpenseEditorAction.RequestDelete)
        successHolder.onAction(ExpenseEditorAction.ConfirmDelete)
        advanceUntilIdle()
        successHolder.close()
        assertEquals(1, repository.deleteCallCount)
        assertEquals(1, repository.saveCallCount)
        assertTrue(successHolder.state.value.completed)
        assertNull(successHolder.state.value.persistenceError)
        assertEquals(0, repository.snapshot().records.size)

        val failedRepository = FakeExpenseRepository(january, listOf(living, date))
        val failedId = failedRepository.saveRecord(record(0, living, 50_000))
        failedRepository.failDelete = true
        val failedHolder = ExpenseEditorStateHolder(ExpenseEditorArgs(failedId, january), failedRepository, this)
        failedHolder.start()
        advanceUntilIdle()

        failedHolder.onAction(ExpenseEditorAction.RequestDelete)
        failedHolder.onAction(ExpenseEditorAction.ConfirmDelete)
        advanceUntilIdle()
        assertEquals(1, failedRepository.deleteCallCount)
        assertEquals("삭제하지 못했어요. 다시 시도해 주세요.", failedHolder.state.value.persistenceError)
        assertEquals(ExpensePersistenceOperation.Delete, failedHolder.state.value.failedOperation)
        assertEquals(1, failedRepository.snapshot().records.size)

        failedRepository.failDelete = false
        failedHolder.onAction(ExpenseEditorAction.RetryPersistence)
        advanceUntilIdle()
        failedHolder.close()
        assertEquals(2, failedRepository.deleteCallCount)
        assertNull(failedHolder.state.value.persistenceError)
        assertNull(failedHolder.state.value.failedOperation)
        assertTrue(failedHolder.state.value.completed)
        assertEquals(0, failedRepository.snapshot().records.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun resetFormReturnsToOriginalValuesAndClearsUiState() = runTest {
        val repository = FakeExpenseRepository(january, listOf(living, date))
        val id = repository.saveRecord(record(0, living, 12_000, actualDate = LocalDateKey(2027, 1, 8), detail = "초기 메모"))
        val holder = ExpenseEditorStateHolder(ExpenseEditorArgs(id, january), repository, this)
        holder.start()
        advanceUntilIdle()

        holder.onAction(ExpenseEditorAction.AmountChanged("25000"))
        holder.onAction(ExpenseEditorAction.DetailChanged("수정 메모"))
        holder.onAction(ExpenseEditorAction.CategorySelected(date.id))
        holder.onAction(ExpenseEditorAction.ActualDateChanged("2027-01-09"))
        holder.onAction(ExpenseEditorAction.OverspentChanged(true))
        assertTrue(holder.state.value.hasUnsavedChanges)

        holder.onAction(ExpenseEditorAction.ResetForm)
        advanceUntilIdle()

        assertEquals("12000", holder.state.value.amount)
        assertEquals(living.id, holder.state.value.categoryId)
        assertEquals("2027-01-08", holder.state.value.actualDate)
        assertEquals("초기 메모", holder.state.value.detail)
        assertEquals("2027-01", holder.state.value.attributionMonth)
        assertFalse(holder.state.value.overspent)
        assertFalse(holder.state.value.hasUnsavedChanges)
        assertEquals(ExpenseEditorErrors(), holder.state.value.errors)
        assertFalse(holder.state.value.isSaving)
        assertNull(holder.state.value.persistenceError)
        holder.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellationExceptionDuringSaveIsNotMappedToPersistenceError() = runTest {
        val repository = FakeExpenseRepository(january, listOf(living, date))
        repository.throwOnSaveCancellation = true
        val holder = ExpenseEditorStateHolder(ExpenseEditorArgs(null, january), repository, this)
        holder.start()
        advanceUntilIdle()

        holder.onAction(ExpenseEditorAction.AmountChanged("50000"))
        holder.onAction(ExpenseEditorAction.CategorySelected(living.id))
        holder.onAction(ExpenseEditorAction.Save)
        advanceUntilIdle()

        assertEquals(1, repository.saveCallCount)
        assertNull(holder.state.value.persistenceError)
        assertNull(holder.state.value.failedOperation)
        holder.close()
    }

    @Test
    fun defaultCategoriesHaveExactNamesAndStableDistinctIds() {
        assertEquals(
            listOf("생활비 초과", "데이트비", "여행비", "경조사비", "효도비", "기타 예산"),
            DefaultExpenseCategories.map(ExpenseCategory::name),
        )
        assertEquals(DefaultExpenseCategories.size, DefaultExpenseCategories.map(ExpenseCategory::id).toSet().size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun editorUpdatesOnlyTheExistingRecordAndKeepsAttributionMonth() = runTest {
        val repository = FakeExpenseRepository(january, listOf(living))
        val id = repository.saveRecord(
            record(0, living, 363_000, overspent = true, actualDate = LocalDateKey(2027, 2, 3)),
        )
        val holder = ExpenseEditorStateHolder(ExpenseEditorArgs(id, january), repository, this)
        holder.start()
        advanceUntilIdle()

        holder.onAction(ExpenseEditorAction.AmountChanged("400000"))
        holder.onAction(ExpenseEditorAction.OverspentChanged(false))
        holder.onAction(ExpenseEditorAction.Save)
        advanceUntilIdle()
        holder.close()

        val saved = repository.snapshot().records.single()
        assertEquals(400_000, saved.amountWon)
        assertEquals(january, saved.attributionMonth)
        assertEquals(LocalDateKey(2027, 2, 3), saved.actualDate)
        assertFalse(saved.overspent)
        assertTrue(holder.state.value.completed)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun requestBackExitsImmediatelyForCleanFormAndConfirmsOnEdits() = runTest {
        val repository = FakeExpenseRepository(january, listOf(living))
        val recordId = repository.saveRecord(record(0, living, 12_000))

        val cleanFormHolder = ExpenseEditorStateHolder(ExpenseEditorArgs(recordId, january), repository, this)
        cleanFormHolder.start()
        advanceUntilIdle()

        cleanFormHolder.onAction(ExpenseEditorAction.RequestBack)
        advanceUntilIdle()
        assertTrue(cleanFormHolder.state.value.exitRequested)
        assertFalse(cleanFormHolder.state.value.showDiscardConfirmation)
        cleanFormHolder.close()

        val editedHolder = ExpenseEditorStateHolder(ExpenseEditorArgs(recordId, january), repository, this)
        editedHolder.start()
        advanceUntilIdle()

        editedHolder.onAction(ExpenseEditorAction.DismissDiscard)
        assertFalse(editedHolder.state.value.showDiscardConfirmation)

        editedHolder.onAction(ExpenseEditorAction.AmountChanged("13000"))
        editedHolder.onAction(ExpenseEditorAction.RequestBack)
        advanceUntilIdle()
        assertFalse(editedHolder.state.value.exitRequested)
        assertTrue(editedHolder.state.value.showDiscardConfirmation)

        editedHolder.onAction(ExpenseEditorAction.DismissDiscard)
        advanceUntilIdle()
        assertFalse(editedHolder.state.value.showDiscardConfirmation)
        assertFalse(editedHolder.state.value.exitRequested)
        assertEquals("13000", editedHolder.state.value.amount)
        assertTrue(editedHolder.state.value.hasUnsavedChanges)

        editedHolder.onAction(ExpenseEditorAction.ConfirmDiscard)
        advanceUntilIdle()
        assertFalse(editedHolder.state.value.showDiscardConfirmation)
        assertTrue(editedHolder.state.value.exitRequested)
        editedHolder.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun duplicateSaveEditDeltaRenameAndFailurePreserveDomainContracts() = runTest {
        val repository = FakeExpenseRepository(january, listOf(living, date))
        val duplicate = record(0, date, 50_000, detail = "저녁")
        val firstId = repository.saveRecord(duplicate)
        val secondId = repository.saveRecord(duplicate)
        assertNotEquals(firstId, secondId)
        assertEquals(100_000, repository.snapshot().records.sumOf(ExpenseRecord::amountWon))

        repository.saveRecord(requireNotNull(repository.findRecord(firstId)).copy(amountWon = 70_000))
        assertEquals(120_000, repository.snapshot().records.sumOf(ExpenseRecord::amountWon))

        repository.renameCategory(date.id, "함께 쓰는 돈")
        assertTrue(repository.snapshot().records.all { it.categoryId == date.id })
        assertTrue(repository.snapshot().records.all { it.categoryName == "함께 쓰는 돈" })

        repository.failSave = true
        val holder = ExpenseEditorStateHolder(ExpenseEditorArgs(null, january), repository, this)
        holder.start()
        advanceUntilIdle()
        holder.onAction(ExpenseEditorAction.AmountChanged("120000"))
        holder.onAction(ExpenseEditorAction.CategorySelected(date.id))
        holder.onAction(ExpenseEditorAction.ActualDateChanged("2027-02-03"))
        holder.onAction(ExpenseEditorAction.OverspentChanged(true))
        holder.onAction(ExpenseEditorAction.Save)
        advanceUntilIdle()
        holder.close()

        assertEquals("120000", holder.state.value.amount)
        assertEquals("2027-01", holder.state.value.attributionMonth)
        assertEquals("2027-02-03", holder.state.value.actualDate)
        assertTrue(holder.state.value.overspent)
        assertFalse(holder.state.value.isSaving)
        assertTrue(holder.state.value.persistenceError != null)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun stateFiltersAreRecoveredAfterMonthNavigationAndDoNotLeakAcrossMonths() = runTest {
        val repository = MonthAwareExpenseRepository(
            listOf(january, february),
            listOf(living, date),
            mapOf(
                january to listOf(record(1, date, 50_000), record(2, date, 15_000, overspent = true)),
                february to listOf(record(3, date, 12_000)),
            ),
        )
        val stateHolder = RecordsStateHolder(repository, providerFor(january), this)
        stateHolder.start()
        try {
            advanceUntilIdle()

            stateHolder.onAction(RecordsAction.CategoryToggled(date.id))
            stateHolder.onAction(RecordsAction.OverspentOnlyToggled)

            val filtered = stateHolder.state.value
            assertEquals(setOf(date.id), filtered.filters.categoryIds)
            assertTrue(filtered.filters.overspentOnly)
            assertEquals(15_000, filtered.filteredSubtotalWon)

            stateHolder.onAction(RecordsAction.SelectMonth(february))
            advanceUntilIdle()
            assertEquals(february, stateHolder.state.value.month)

            stateHolder.onAction(RecordsAction.SelectMonth(january))
            advanceUntilIdle()

            stateHolder.onAction(RecordsAction.NextMonth)
            advanceUntilIdle()
            assertEquals(february, stateHolder.state.value.month)
            assertEquals(ExpenseFilters(categoryIds = setOf(date.id), overspentOnly = true), stateHolder.state.value.filters)
            assertEquals(0, stateHolder.state.value.filteredSubtotalWon)

            stateHolder.onAction(RecordsAction.PreviousMonth)
            advanceUntilIdle()
            assertEquals(january, stateHolder.state.value.month)
            assertEquals(ExpenseFilters(categoryIds = setOf(date.id), overspentOnly = true), stateHolder.state.value.filters)
            assertEquals(15_000, stateHolder.state.value.filteredSubtotalWon)
        } finally {
            stateHolder.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun filtersInApplyActionAreNormalizedAndClearFiltersResetsEverything() = runTest {
        val repository = FakeExpenseRepository(january, listOf(living, date))
        repository.saveRecord(record(1, living, 12_000))
        repository.saveRecord(record(2, date, 18_000, overspent = true))
        repository.saveRecord(record(3, date, 25_000))
        val stateHolder = RecordsStateHolder(repository, providerFor(january), this)
        stateHolder.start()
        advanceUntilIdle()

        stateHolder.onAction(
            RecordsAction.FiltersApplied(
                ExpenseFilters(
                    categoryIds = setOf(living.id, "ghost"),
                    overspentOnly = true,
                    sort = ExpenseSort.Oldest,
                ),
            ),
        )
        advanceUntilIdle()
        assertEquals(ExpenseFilters(categoryIds = setOf(living.id), overspentOnly = true, sort = ExpenseSort.Oldest), stateHolder.state.value.filters)
        assertEquals(0, stateHolder.state.value.filteredSubtotalWon)

        stateHolder.onAction(RecordsAction.FiltersApplied(ExpenseFilters(categoryIds = setOf(living.id, date.id), overspentOnly = true)))
        advanceUntilIdle()
        assertEquals(ExpenseFilters(categoryIds = setOf(living.id, date.id), overspentOnly = true), stateHolder.state.value.filters)

        stateHolder.onAction(RecordsAction.ClearFilters)
        advanceUntilIdle()
        assertEquals(ExpenseFilters(), stateHolder.state.value.filters)
        assertTrue(stateHolder.state.value.filters.categoryIds.isEmpty())

        stateHolder.close()
    }

    @Test
    fun categoryFiltersApplyAsOrAndOverspentFiltersApplyAsAnd() {
        val snapshot = ExpenseMonth(
            categories = listOf(living, date),
            records = listOf(
                record(1, living, 10_000, overspent = true, actualDate = LocalDateKey(2027, 1, 2)),
                record(2, living, 20_000, actualDate = LocalDateKey(2027, 1, 3)),
                record(3, date, 30_000, overspent = true, actualDate = LocalDateKey(2027, 1, 4)),
                record(4, date, 40_000, actualDate = LocalDateKey(2027, 1, 5)),
            ),
        )
        val state = buildRecordsState(
            january,
            snapshot,
            ExpenseFilters(categoryIds = setOf(living.id, date.id), overspentOnly = true),
        )

        assertContentEquals(listOf(3L, 1L), state.visibleRecords.map(ExpenseRecord::id))
        assertEquals(40_000, state.filteredSubtotalWon)
    }

    @Test
    fun latestAndOldestSortOrderRespectsDateThenIdAndKeepsUndatedAtEnd() {
        val snapshot = ExpenseMonth(
            categories = listOf(living, date),
            records = listOf(
                record(1, living, 10_000, actualDate = LocalDateKey(2027, 1, 1)),
                record(2, living, 20_000, actualDate = LocalDateKey(2027, 1, 2)),
                record(3, date, 30_000, actualDate = LocalDateKey(2027, 1, 2)),
                record(4, date, 40_000, actualDate = null),
            ),
        )
        val latestState = buildRecordsState(january, snapshot, ExpenseFilters(sort = ExpenseSort.Latest))
        val oldestState = buildRecordsState(january, snapshot, ExpenseFilters(sort = ExpenseSort.Oldest))

        assertContentEquals(listOf(3L, 2L, 1L, 4L), latestState.visibleRecords.map(ExpenseRecord::id))
        assertContentEquals(listOf(1L, 2L, 3L, 4L), oldestState.visibleRecords.map(ExpenseRecord::id))
    }

    @Test
    fun filterEmptyDescriptionAndRecordMetadataHelpersReflectFiltersAndMismatchDates() {
        val noFilters = buildRecordsState(
            january,
            ExpenseMonth(
                categories = listOf(living, date),
                records = emptyList(),
            ),
            ExpenseFilters(),
        )
        assertEquals("선택한 조건에 해당하는 기록이 이번 달에는 없네요.", filterEmptyDescription(noFilters))

        val withFilters = buildRecordsState(
            january,
            ExpenseMonth(
                categories = listOf(living, date),
                records = emptyList(),
            ),
            ExpenseFilters(categoryIds = setOf(living.id, date.id), overspentOnly = true),
        )
        assertEquals(
            "설정하신 '생활비 초과', '데이트비', '과소비' 필터에 해당하는 기록이 이번 달에는 없네요.",
            filterEmptyDescription(withFilters),
        )

        assertEquals(
            "2027년 2월 3일 발생 · 2027년 1월 귀속",
            recordMonthMetadata(record(1, living, 10_000, actualDate = LocalDateKey(2027, 2, 3))),
        )
        assertEquals("실제 발생일 미입력", recordMonthMetadata(record(2, living, 10_000, actualDate = null)))
        assertEquals("토요일", koreanWeekday(LocalDateKey(2027, 1, 2)))
    }

    @Test
    fun checkedTotalsExposeOverflowForMonthFilterCategoryAndDateGroup() {
        val records = listOf(record(1, living, Long.MAX_VALUE), record(2, living, 1))
        val state = buildRecordsState(
            january,
            ExpenseMonth(listOf(living), records),
            ExpenseFilters(categoryIds = setOf(living.id)),
        )

        assertTrue(state.hasFullMonthTotalOverflow)
        assertTrue(state.hasFilteredSubtotalOverflow)
        assertTrue(living.id in state.categoryTotalOverflowIds)
        assertNull(expenseGroupTotalWon(records))
    }

    @Test
    fun budgetRatioUsesOnlyTheSelectedMonthsPlannedAmount() {
        val state = buildRecordsState(
            january,
            ExpenseMonth(listOf(living), listOf(record(1, living, 600_000)), plannedVariableExpenseWon = 800_000),
            ExpenseFilters(),
        )

        assertEquals(75.0, state.budgetUsagePercent)
        assertEquals(800_000, state.plannedVariableExpenseWon)
    }

    @Test
    fun currentMonthComparisonUsesSameDayAndExcludesUndatedRecords() {
        val august = YearMonthKey(2027, 8)
        val september = YearMonthKey(2027, 9)
        fun expense(id: Long, month: YearMonthKey, day: Int?, amount: Long) =
            record(id, living, amount, actualDate = day?.let { LocalDateKey(month.year, month.month, it) }).copy(attributionMonth = month)
        val comparison = buildExpenseComparison(
            selectedMonth = september,
            selectedRecords = listOf(expense(1, september, 10, 100), expense(2, september, 13, 1_000), expense(3, september, null, 5_000)),
            priorRecords = listOf(expense(4, august, 12, 80), expense(5, august, 13, 2_000), expense(6, august, null, 9_000)),
            today = LocalDateKey(2027, 9, 12),
        )

        assertEquals(ExpenseComparisonPeriod.SameDayPriorMonth, comparison?.period)
        assertEquals(100, comparison?.selectedPeriodTotalWon)
        assertEquals(80, comparison?.priorPeriodTotalWon)
        assertEquals(20, comparison?.differenceWon)
        assertTrue(requireNotNull(comparison).selectedSpentMore)
    }

    @Test
    fun pastMonthComparisonUsesCompleteMonthsIncludingUndatedRecords() {
        val comparison = buildExpenseComparison(
            selectedMonth = january,
            selectedRecords = listOf(record(1, living, 100, actualDate = null)),
            priorRecords = listOf(record(2, living, 70, actualDate = null).copy(attributionMonth = YearMonthKey(2026, 12))),
            today = LocalDateKey(2027, 9, 12),
        )

        assertEquals(ExpenseComparisonPeriod.FullPriorMonth, comparison?.period)
        assertEquals(30, comparison?.differenceWon)
    }

    @Test
    fun currentMonthComparisonIsUnavailableWhenItsRecordsHaveNoComparableDates() {
        val september = YearMonthKey(2027, 9)
        val comparison = buildExpenseComparison(
            selectedMonth = september,
            selectedRecords = listOf(record(1, living, 120_000, actualDate = null).copy(attributionMonth = september)),
            priorRecords = emptyList(),
            today = LocalDateKey(2027, 9, 12),
        )

        assertNull(comparison)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun editorOffersTodayForCurrentAttributionMonthAndRestoresDraft() = runTest {
        val today = LocalDateKey(2027, 1, 19)
        val repository = FakeExpenseRepository(january, listOf(living))
        val holder = ExpenseEditorStateHolder(ExpenseEditorArgs(null, january), repository, this, CurrentDateProvider { today })
        holder.start()
        advanceUntilIdle()

        assertEquals(today, holder.state.value.suggestedActualDate)
        holder.onAction(ExpenseEditorAction.UseSuggestedActualDate)
        assertEquals(today.toString(), holder.state.value.actualDate)
        holder.onAction(ExpenseEditorAction.RestoreDraft(ExpenseEditorDraftSnapshot("25000", living.id, "2027-01", "", "복원", true)))

        assertEquals("25000", holder.state.value.amount)
        assertEquals("복원", holder.state.value.detail)
        assertTrue(holder.state.value.overspent)
        assertTrue(holder.state.value.hasUnsavedChanges)
        holder.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun loadFailureCanRetryAndValidationErrorsDisableSaveUntilCorrected() = runTest {
        val repository = FakeExpenseRepository(january, listOf(living)).apply { failPrepare = true }
        val holder = ExpenseEditorStateHolder(ExpenseEditorArgs(null, january), repository, this)
        holder.start()
        advanceUntilIdle()
        assertEquals(ExpensePersistenceOperation.Load, holder.state.value.failedOperation)

        repository.failPrepare = false
        holder.onAction(ExpenseEditorAction.RetryPersistence)
        advanceUntilIdle()
        assertFalse(holder.state.value.isLoading)
        assertNull(holder.state.value.persistenceError)

        holder.onAction(ExpenseEditorAction.Save)
        assertFalse(holder.state.value.canSave)
        holder.onAction(ExpenseEditorAction.AmountChanged("1000"))
        assertFalse(holder.state.value.canSave)
        holder.onAction(ExpenseEditorAction.CategorySelected(living.id))
        assertTrue(holder.state.value.canSave)
        holder.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun categoryFilterSelectedWhileNewMonthLoadsIsAppliedAfterTheSnapshotArrives() = runTest {
        val repository = MonthAwareExpenseRepository(
            months = listOf(january, february),
            categories = listOf(living, date),
            initialRecords = mapOf(february to listOf(record(2, date, 40_000))),
        )
        val holder = RecordsStateHolder(repository, providerFor(january), this)
        holder.start()
        advanceUntilIdle()

        holder.onAction(RecordsAction.SelectMonth(february))
        holder.onAction(RecordsAction.CategoryToggled(date.id))
        advanceUntilIdle()

        assertEquals(setOf(date.id), holder.state.value.filters.categoryIds)
        assertEquals(listOf(2L), holder.state.value.visibleRecords.map { it.id })
        holder.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun refreshCurrentDateRecomputesSameDayComparisonWithoutRepositoryEmission() = runTest {
        val december = YearMonthKey(2026, 12)
        var today = LocalDateKey(2027, 1, 1)
        val repository = MonthAwareExpenseRepository(
            months = listOf(december, january),
            categories = listOf(living),
            initialRecords = mapOf(
                january to listOf(
                    record(1, living, 10, actualDate = LocalDateKey(2027, 1, 1)),
                    record(2, living, 20, actualDate = LocalDateKey(2027, 1, 2)),
                ),
                december to listOf(
                    record(3, living, 5, actualDate = LocalDateKey(2026, 12, 1)),
                    record(4, living, 7, actualDate = LocalDateKey(2026, 12, 2)),
                ),
            ),
        )
        val holder = RecordsStateHolder(repository, providerFor(january), CurrentDateProvider { today }, this)
        holder.start()
        advanceUntilIdle()
        assertEquals(5, holder.state.value.comparison?.differenceWon)

        today = LocalDateKey(2027, 1, 2)
        holder.onAction(RecordsAction.RefreshCurrentDate)

        assertEquals(18, holder.state.value.comparison?.differenceWon)
        holder.close()
    }

    private fun record(
        id: Long,
        category: ExpenseCategory,
        amount: Long,
        overspent: Boolean = false,
        actualDate: LocalDateKey? = LocalDateKey(2027, 1, id.coerceAtLeast(1).toInt()),
        detail: String? = null,
    ) = ExpenseRecord(
        id = id,
        categoryId = category.id,
        categoryName = category.name,
        attributionMonth = january,
        actualDate = actualDate,
        detail = detail,
        amountWon = amount,
        overspent = overspent,
    )

    private fun providerFor(month: YearMonthKey): CurrentMonthProvider = CurrentMonthProvider { month }
}

    private class FakeExpenseRepository(
    private val month: YearMonthKey,
    categories: List<ExpenseCategory>,
) : ExpenseRepository {
    private val flow = MutableStateFlow(ExpenseMonth(categories, emptyList()))
    var failSave: Boolean = false
    var failDelete: Boolean = false
    var failPrepare: Boolean = false
    var throwOnSaveCancellation: Boolean = false
    var throwOnDeleteCancellation: Boolean = false
    var saveCallCount = 0
    var deleteCallCount = 0
    private var nextId = 1L

    override suspend fun prepareCategories() { if (failPrepare) error("expected load failure") }
    override fun observeMonth(month: YearMonthKey): Flow<ExpenseMonth> = flow
    override suspend fun findRecord(id: Long): ExpenseRecord? = flow.value.records.firstOrNull { it.id == id }
    override suspend fun saveRecord(record: ExpenseRecord): Long {
        saveCallCount += 1
        if (throwOnSaveCancellation) throw CancellationException()
        if (failSave) error("expected save failure")
        val id = record.id.takeUnless { it == 0L } ?: nextId++
        val stored = record.copy(id = id, attributionMonth = record.attributionMonth)
        flow.value = flow.value.copy(records = flow.value.records.filterNot { it.id == id } + stored)
        return id
    }
    override suspend fun deleteRecord(id: Long) {
        deleteCallCount += 1
        if (throwOnDeleteCancellation) throw CancellationException()
        if (failDelete) error("expected delete failure")
        flow.value = flow.value.copy(records = flow.value.records.filterNot { it.id == id })
    }
    override suspend fun renameCategory(categoryId: String, name: String) {
        flow.value = flow.value.copy(
            categories = flow.value.categories.map { if (it.id == categoryId) it.copy(name = name) else it },
            records = flow.value.records.map { if (it.categoryId == categoryId) it.copy(categoryName = name) else it },
        )
    }
    fun snapshot(): ExpenseMonth = flow.value
}

private class MonthAwareExpenseRepository(
    months: List<YearMonthKey>,
    categories: List<ExpenseCategory>,
    initialRecords: Map<YearMonthKey, List<ExpenseRecord>>,
) : ExpenseRepository {
    private val monthSnapshots: Map<YearMonthKey, MutableStateFlow<ExpenseMonth>> = months.associateWith { month ->
        val monthRecords = initialRecords[month].orEmpty().map { it.copy(attributionMonth = month) }
        MutableStateFlow(ExpenseMonth(categories = categories, records = monthRecords))
    }

    override suspend fun prepareCategories() = Unit
    override fun observeMonth(month: YearMonthKey): Flow<ExpenseMonth> =
        monthSnapshots[month]?.asStateFlow() ?: flowOf(ExpenseMonth(emptyList(), emptyList()))

    override suspend fun findRecord(id: Long): ExpenseRecord? =
        monthSnapshots.values
            .flatMap { it.value.records }
            .firstOrNull { it.id == id }

    override suspend fun saveRecord(record: ExpenseRecord): Long = record.id

    override suspend fun deleteRecord(id: Long) {
        monthSnapshots.values.forEach { snapshot ->
            snapshot.update { it.copy(records = it.records.filterNot { record -> record.id == id }) }
        }
    }

    override suspend fun renameCategory(categoryId: String, name: String) {
        monthSnapshots.values.forEach { snapshot ->
            snapshot.update {
                it.copy(
                    categories = it.categories.map { category -> if (category.id == categoryId) category.copy(name = name) else category },
                    records = it.records.map { record -> if (record.categoryId == categoryId) record.copy(categoryName = name) else record },
                )
            }
        }
    }
}
