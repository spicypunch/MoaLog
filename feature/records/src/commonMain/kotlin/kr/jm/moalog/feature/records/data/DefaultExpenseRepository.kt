package kr.jm.moalog.feature.records.data

import kr.jm.moalog.core.database.ExpenseLocalDataSource
import kr.jm.moalog.core.model.ExpenseRecord
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.records.domain.ExpenseRepository
import kr.jm.moalog.feature.records.domain.ExpenseMonth
import kotlinx.coroutines.flow.map

internal class DefaultExpenseRepository(
    private val localDataSource: ExpenseLocalDataSource,
) : ExpenseRepository {
    override suspend fun prepareCategories() = localDataSource.ensureDefaultCategories()
    override fun observeMonth(month: YearMonthKey) = localDataSource.observeMonth(month).map { snapshot ->
        ExpenseMonth(snapshot.categories, snapshot.records)
    }
    override suspend fun findRecord(id: Long) = localDataSource.findRecord(id)
    override suspend fun saveRecord(record: ExpenseRecord) = localDataSource.saveRecord(record)
    override suspend fun deleteRecord(id: Long) = localDataSource.deleteRecord(id)
    override suspend fun renameCategory(categoryId: String, name: String) = localDataSource.renameCategory(categoryId, name)
}
