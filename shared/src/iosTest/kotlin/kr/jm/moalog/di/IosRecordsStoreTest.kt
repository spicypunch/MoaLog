package kr.jm.moalog.di

import kr.jm.moalog.core.model.ExpenseCategory
import kr.jm.moalog.core.model.ExpenseRecord
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.records.domain.CurrentMonthProvider
import kr.jm.moalog.feature.records.domain.ExpenseMonth
import kr.jm.moalog.feature.records.domain.ExpenseRepository
import kr.jm.moalog.feature.records.presentation.RecordsStateHolder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosRecordsStoreTest {
    @Test
    fun restoresMonthCategoryAndOverspentFilters() = runBlocking {
        val selected = YearMonthKey(2027, 3)
        val category = ExpenseCategory("living", "생활", 0)
        val record = ExpenseRecord(
            categoryId = category.id,
            categoryName = category.name,
            attributionMonth = selected,
            actualDate = null,
            detail = null,
            amountWon = 120_000,
            overspent = true,
        )
        val repository = FakeExpenseRepository(ExpenseMonth(listOf(category), listOf(record)))
        val holder = RecordsStateHolder(repository, CurrentMonthProvider { YearMonthKey(2026, 9) }, this)
        val store = IosRecordsStore(holder)

        store.applySelection(2027, 3, category.id, overspentOnly = true)
        repeat(10) { yield() }

        assertEquals(selected, store.currentState.month)
        assertEquals(setOf(category.id), store.currentState.filters.categoryIds)
        assertTrue(store.currentState.filters.overspentOnly)
        assertEquals(listOf(record), store.currentState.visibleRecords)
        store.close()
    }

    private class FakeExpenseRepository(private val snapshot: ExpenseMonth) : ExpenseRepository {
        override suspend fun prepareCategories() = Unit
        override fun observeMonth(month: YearMonthKey): Flow<ExpenseMonth> = flowOf(snapshot)
        override suspend fun findRecord(id: Long): ExpenseRecord? = null
        override suspend fun saveRecord(record: ExpenseRecord): Long = record.id
        override suspend fun deleteRecord(id: Long) = Unit
        override suspend fun renameCategory(categoryId: String, name: String) = Unit
    }
}
