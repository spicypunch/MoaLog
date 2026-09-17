package kr.jm.moalog.di

import kr.jm.moalog.core.model.ExpenseCategory
import kr.jm.moalog.core.model.ExpenseRecord
import kr.jm.moalog.core.model.LocalDateKey
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.records.domain.CurrentMonthProvider
import kr.jm.moalog.feature.records.domain.ExpenseMonth
import kr.jm.moalog.feature.records.domain.ExpenseRepository
import kr.jm.moalog.feature.records.presentation.ExpenseEditorArgs
import kr.jm.moalog.feature.records.presentation.ExpenseEditorStateHolder
import kr.jm.moalog.feature.records.presentation.ExpenseSort
import kr.jm.moalog.feature.records.presentation.RecordsStateHolder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosStage3StoresTest {
    @Test
    fun recordsStoreRestoresMonthMultipleFiltersAndOldestSort() = runBlocking {
        val month = YearMonthKey(1900, 1)
        val food = ExpenseCategory("food", "식비", 0)
        val cafe = ExpenseCategory("cafe", "카페", 1)
        val records = listOf(
            expense(2, food, month, 2, overspent = true),
            expense(1, cafe, month, 1, overspent = true),
        )
        val repository = Stage3ExpenseRepository(ExpenseMonth(listOf(food, cafe), records))
        val store = IosRecordsStore(RecordsStateHolder(repository, CurrentMonthProvider { YearMonthKey(2026, 9) }, this))

        store.restoreSelection(1900, 1, listOf(food.id, cafe.id), overspentOnly = true, oldestFirst = true)
        settle()

        assertEquals(month, store.currentState.month)
        assertEquals(setOf(food.id, cafe.id), store.currentState.filters.categoryIds)
        assertTrue(store.currentState.filters.overspentOnly)
        assertEquals(ExpenseSort.Oldest, store.currentState.filters.sort)
        assertEquals(listOf(1L, 2L), store.currentState.visibleRecords.map { it.id })
        store.close()
    }

    @Test
    fun editorStoreRestoresDraftAndPersistsIndependentDates() = runBlocking {
        val month = YearMonthKey(2026, 5)
        val food = ExpenseCategory("food", "생활비", 0)
        val repository = Stage3ExpenseRepository(ExpenseMonth(listOf(food), emptyList()))
        val store = IosExpenseEditorStore(ExpenseEditorStateHolder(ExpenseEditorArgs(null, month), repository, this))
        settle()

        store.restoreDraft(
            amount = "120000",
            categoryId = food.id,
            attributionMonth = "2026-05",
            actualDate = "2026-04-30",
            detail = "장보기",
            overspent = true,
        )
        store.save()
        settle()

        val saved = repository.saved
        assertEquals(120_000L, saved?.amountWon)
        assertEquals(month, saved?.attributionMonth)
        assertEquals(LocalDateKey(2026, 4, 30), saved?.actualDate)
        assertEquals("장보기", saved?.detail)
        assertTrue(saved?.overspent == true)
        assertTrue(store.currentState.completed)
        store.close()
    }

    @Test
    fun editorStoreExposesValidationAndDeletesOnlyAfterConfirmation() = runBlocking {
        val month = YearMonthKey(2026, 5)
        val category = ExpenseCategory("food", "생활비", 0)
        val existing = expense(7, category, month, 14, overspent = false)
        val repository = Stage3ExpenseRepository(ExpenseMonth(listOf(category), listOf(existing)), existing)
        val createStore = IosExpenseEditorStore(ExpenseEditorStateHolder(ExpenseEditorArgs(null, month), repository, this))
        settle()

        createStore.save()
        assertTrue(createStore.currentState.errors.hasAny)
        assertFalse(createStore.currentState.canSave)
        assertFalse(createStore.currentState.completed)
        createStore.close()

        val editStore = IosExpenseEditorStore(ExpenseEditorStateHolder(ExpenseEditorArgs(existing.id, month), repository, this))
        settle()
        editStore.requestDelete()
        assertTrue(editStore.currentState.showDeleteConfirmation)
        assertNull(repository.deletedId)
        editStore.confirmDelete()
        settle()
        assertEquals(existing.id, repository.deletedId)
        assertTrue(editStore.currentState.completed)
        editStore.close()
    }

    private fun expense(
        id: Long,
        category: ExpenseCategory,
        month: YearMonthKey,
        day: Int,
        overspent: Boolean,
    ) = ExpenseRecord(
        id = id,
        categoryId = category.id,
        categoryName = category.name,
        attributionMonth = month,
        actualDate = LocalDateKey(month.year, month.month, day),
        detail = null,
        amountWon = 10_000L * id,
        overspent = overspent,
    )

    private suspend fun settle() { repeat(20) { yield() } }
}

private class Stage3ExpenseRepository(
    private val snapshot: ExpenseMonth,
    private val existing: ExpenseRecord? = null,
) : ExpenseRepository {
    var saved: ExpenseRecord? = null
    var deletedId: Long? = null
    override suspend fun prepareCategories() = Unit
    override fun observeMonth(month: YearMonthKey): Flow<ExpenseMonth> = flowOf(snapshot)
    override suspend fun findRecord(id: Long): ExpenseRecord? = existing?.takeIf { it.id == id }
    override suspend fun saveRecord(record: ExpenseRecord): Long { saved = record; return record.id.takeIf { it != 0L } ?: 9L }
    override suspend fun deleteRecord(id: Long) { deletedId = id }
    override suspend fun renameCategory(categoryId: String, name: String) = Unit
}
