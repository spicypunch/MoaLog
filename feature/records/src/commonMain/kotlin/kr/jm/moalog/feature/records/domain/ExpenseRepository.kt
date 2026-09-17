package kr.jm.moalog.feature.records.domain

import kr.jm.moalog.core.model.ExpenseCategory
import kr.jm.moalog.core.model.ExpenseRecord
import kr.jm.moalog.core.model.YearMonthKey
import kotlinx.coroutines.flow.Flow

interface ExpenseRepository {
    suspend fun prepareCategories()
    fun observeMonth(month: YearMonthKey): Flow<ExpenseMonth>
    suspend fun findRecord(id: Long): ExpenseRecord?
    suspend fun saveRecord(record: ExpenseRecord): Long
    suspend fun deleteRecord(id: Long)
    suspend fun renameCategory(categoryId: String, name: String)
}

data class ExpenseMonth(
    val categories: List<ExpenseCategory>,
    val records: List<ExpenseRecord>,
    /** Planned variable-expense amount for this exact month, when a planning source supplies one. */
    val plannedVariableExpenseWon: Long? = null,
) {
    init {
        require(plannedVariableExpenseWon == null || plannedVariableExpenseWon >= 0)
    }
}
