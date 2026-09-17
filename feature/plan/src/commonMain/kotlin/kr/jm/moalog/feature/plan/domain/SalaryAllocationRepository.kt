package kr.jm.moalog.feature.plan.domain

import kr.jm.moalog.core.database.SalaryAllocationLocalDataSource
import kr.jm.moalog.core.model.SalaryAllocationCategory
import kr.jm.moalog.core.model.SalaryAllocationChild
import kr.jm.moalog.core.model.SalaryAllocationSheet
import kr.jm.moalog.core.model.SalaryAllocationGrandchild
import kr.jm.moalog.core.model.SalaryIncome
import kr.jm.moalog.core.model.YearMonthKey
import kotlinx.coroutines.flow.Flow

interface SalaryAllocationRepository {
    fun observe(month: YearMonthKey): Flow<SalaryAllocationSheet>
    suspend fun saveIncomes(values: List<SalaryIncome>)
    suspend fun findCategory(id: Long): SalaryAllocationCategory?
    suspend fun saveCategory(value: SalaryAllocationCategory): Long
    suspend fun deleteCategory(id: Long)
    suspend fun findChild(id: Long): SalaryAllocationChild?
    suspend fun saveChild(value: SalaryAllocationChild): Long
    suspend fun deleteChild(id: Long)
    suspend fun findGrandchild(id:Long):SalaryAllocationGrandchild?
    suspend fun saveGrandchild(value:SalaryAllocationGrandchild):Long
    suspend fun deleteGrandchild(id:Long)
}

internal class DefaultSalaryAllocationRepository(private val local: SalaryAllocationLocalDataSource) : SalaryAllocationRepository {
    override fun observe(month: YearMonthKey) = local.observe(month)
    override suspend fun saveIncomes(values: List<SalaryIncome>) = local.saveIncomes(values)
    override suspend fun findCategory(id: Long) = local.findCategory(id)
    override suspend fun saveCategory(value: SalaryAllocationCategory) = local.saveCategory(value)
    override suspend fun deleteCategory(id: Long) = local.deleteCategory(id)
    override suspend fun findChild(id: Long) = local.findChild(id)
    override suspend fun saveChild(value: SalaryAllocationChild) = local.saveChild(value)
    override suspend fun deleteChild(id: Long) = local.deleteChild(id)
    override suspend fun findGrandchild(id:Long)=local.findGrandchild(id)
    override suspend fun saveGrandchild(value:SalaryAllocationGrandchild)=local.saveGrandchild(value)
    override suspend fun deleteGrandchild(id:Long)=local.deleteGrandchild(id)
}
