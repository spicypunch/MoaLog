package kr.jm.moalog.feature.maintenance.data

import kr.jm.moalog.core.database.MaintenanceFeeLocalDataSource
import kr.jm.moalog.core.model.MaintenanceFeeMonth
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.maintenance.domain.MaintenanceRepository

internal class DefaultMaintenanceRepository(
    private val localDataSource: MaintenanceFeeLocalDataSource,
) : MaintenanceRepository {
    override fun observeMonth(month: YearMonthKey) = localDataSource.observeMonth(month)

    override fun observeRange(startInclusive: YearMonthKey, endInclusive: YearMonthKey) =
        localDataSource.observeRange(startInclusive, endInclusive)

    override suspend fun saveMonth(month: MaintenanceFeeMonth) = localDataSource.saveMonth(month)
}
