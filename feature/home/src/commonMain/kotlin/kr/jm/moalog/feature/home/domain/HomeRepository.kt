package kr.jm.moalog.feature.home.domain

import kr.jm.moalog.core.database.ExpenseLocalDataSource
import kr.jm.moalog.core.database.LedgerSetupLocalDataSource
import kr.jm.moalog.core.database.PlanLocalDataSource
import kr.jm.moalog.core.model.LedgerSetup
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.home.presentation.HomeAnnualMonthSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first

data class HomeYearSnapshot(
    val setup: LedgerSetup?,
    val months: List<HomeAnnualMonthSnapshot>,
)

interface HomeRepository {
    fun observeYear(year: Int): Flow<HomeYearSnapshot>
    suspend fun updateAnnualSavingsTarget(targetWon: Long?)
    suspend fun updateAnnualSavingsTarget(year: Int, targetWon: Long?) {
        updateAnnualSavingsTarget(targetWon)
    }
}

internal class DefaultHomeRepository(
    private val setupSource: LedgerSetupLocalDataSource,
    private val planSource: PlanLocalDataSource,
    private val expenseSource: ExpenseLocalDataSource,
) : HomeRepository {
    override fun observeYear(year: Int): Flow<HomeYearSnapshot> {
        val monthFlows = (1..12).map { monthNumber ->
            val month = YearMonthKey(year, monthNumber)
            combine(
                planSource.observeMonth(month),
                expenseSource.observeMonth(month),
            ) { planItems, expenseSnapshot ->
                HomeAnnualMonthSnapshot(month, planItems, expenseSnapshot.records)
            }
        }
        val yearFlow = combine(monthFlows) { snapshots -> snapshots.toList() }
        return flow {
            expenseSource.ensureDefaultCategories()
            emitAll(combine(setupSource.observe(), yearFlow) { setup, months ->
                HomeYearSnapshot(setup, months)
            })
        }
    }

    override suspend fun updateAnnualSavingsTarget(targetWon: Long?) {
        require(targetWon == null || targetWon >= 0L) { "저축 목표는 0원 이상이어야 해요" }
        val current = setupSource.observe().first()
            ?: error("가계부 설정을 찾을 수 없어요")
        setupSource.saveAnnualSavingsTarget(current.baseYear, targetWon)
    }

    override suspend fun updateAnnualSavingsTarget(year: Int, targetWon: Long?) {
        require(year in 1900..9999) { "저축 목표 연도가 올바르지 않아요" }
        require(targetWon == null || targetWon >= 0L) { "저축 목표는 0원 이상이어야 해요" }
        setupSource.saveAnnualSavingsTarget(year, targetWon)
    }
}
