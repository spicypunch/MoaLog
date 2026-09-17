package kr.jm.moalog.feature.maintenance.domain

import kr.jm.moalog.core.model.MaintenanceFeeMonth
import kr.jm.moalog.core.model.YearMonthKey
import kotlinx.coroutines.flow.Flow

interface MaintenanceRepository {
    fun observeMonth(month: YearMonthKey): Flow<MaintenanceFeeMonth>
    fun observeRange(startInclusive: YearMonthKey, endInclusive: YearMonthKey): Flow<List<MaintenanceFeeMonth>>
    suspend fun saveMonth(month: MaintenanceFeeMonth)
}
