package kr.jm.moalog.di

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
import kr.jm.moalog.feature.plan.presentation.PlanEditorArgs
import kr.jm.moalog.feature.plan.presentation.PlanEditorCompletionKind
import kr.jm.moalog.feature.plan.presentation.PlanEditorStateHolder
import kr.jm.moalog.feature.plan.presentation.PlanEditorUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosPlanEditorStoreTest {
    @Test
    fun exposesEditorCommandsAndSaveResultWithoutSealedActions() = runBlocking {
        val repository = FakeRepository()
        val store = IosPlanEditorStore(
            PlanEditorStateHolder(
                args = PlanEditorArgs(null, PlanItemType.Income, YearMonthKey(2026, 5)),
                repository = repository,
                parentScope = this,
            ),
        )
        val observed = mutableListOf<PlanEditorUiState>()
        val observation = store.observe { observed += it }
        repeat(10) { yield() }

        store.selectType(PlanItemType.Savings)
        store.selectCatalog(42)
        assertEquals("주택청약", store.currentState.name)
        assertEquals("청약", store.currentState.category)
        store.nextMonth()
        store.changeName("주택청약")
        store.changeAmount("400000")
        store.addAmount(100_000)
        store.changeCategory("청약")
        store.changeStatus(PlanItemStatus.Confirmed)
        store.changeMemo("장기 목표")
        store.changePurposeAccount(true)
        store.changeNetSavings(true)
        store.saveAndApply()
        repeat(20) { yield() }

        assertEquals(PlanEditorCompletionKind.SavedAndApply, store.currentState.completion?.kind)
        assertEquals(88, store.currentState.savedItemId)
        assertEquals(YearMonthKey(2026, 6), store.currentState.completion?.month)
        assertEquals(500_000, repository.saved?.amountWon)
        assertTrue(repository.saved?.includePurposeAccount == true)
        assertTrue(repository.saved?.includeNetSavings == true)
        assertEquals(42L, repository.saved?.catalogId)
        assertTrue(observed.any { it.completed })

        observation.cancel()
        val observedCount = observed.size
        store.close()
        store.close()
        store.previousMonth()
        assertEquals("2026-06", store.currentState.month)
        repeat(5) { yield() }
        assertEquals(observedCount, observed.size)
    }

    private class FakeRepository : PlanRepository {
        var saved: MonthlyPlanItem? = null
        override fun observeMonth(month: YearMonthKey): Flow<PlanMonth> = flowOf(PlanMonth(emptyList(), 0))
        override fun observeYear(year: Int): Flow<List<PlanYearMonth>> = flowOf(emptyList())
        override fun observeCatalog(): Flow<List<PlanCatalogItem>> = flowOf(
            listOf(
                PlanCatalogItem(
                    id = 42,
                    type = PlanItemType.Savings,
                    classification = "청약",
                    name = "주택청약",
                    ownerMemberOrder = null,
                    includePurposeAccount = true,
                    includeNetSavings = true,
                ),
            ),
        )
        override suspend fun previewCopy(request: PlanCopyRequest): PlanCopyPreview = error("unused")
        override suspend fun copyToMonths(request: PlanCopyRequest): PlanCopyResult = error("unused")
        override suspend fun find(id: Long): MonthlyPlanItem? = null
        override suspend fun save(item: MonthlyPlanItem): Long {
            saved = item
            return 88
        }
        override suspend fun delete(id: Long) = Unit
    }
}
