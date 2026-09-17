package kr.jm.moalog.di

import kr.jm.moalog.core.model.LedgerMember
import kr.jm.moalog.core.model.LedgerSetup
import kr.jm.moalog.feature.home.domain.HomeRepository
import kr.jm.moalog.feature.home.domain.HomeYearSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosGoalEditStoreTest {
    @Test
    fun validatesAndRemovesOnlyTheSelectedYearsGoal() = runBlocking {
        val initial = LedgerSetup(
            ledgerName = "모아로그",
            members = listOf(LedgerMember("수아", 0), LedgerMember("종민", 1)),
            baseYear = 2026,
            annualSavingsTargetWon = 50_000_000,
            annualSavingsTargetsWon = mapOf(2026 to 50_000_000, 2027 to 60_000_000),
        )
        val repository = FakeHomeRepository(initial)
        val store = IosGoalEditStore(initial, 2027, repository)

        assertEquals(2027, store.currentState.year)
        assertEquals("60000000", store.currentState.targetInput)

        store.changeTarget("-1")
        store.save()
        assertEquals("0원 이상의 금액을 숫자로 입력해 주세요", store.currentState.inputError)
        assertNull(repository.savedTarget)

        store.changeTarget("")
        store.save()
        repeat(10) { yield() }

        assertTrue(store.currentState.didSave)
        assertFalse(store.currentState.isSaving)
        assertEquals(2027 to null, repository.savedTarget)
        store.close()
    }

    private class FakeHomeRepository(private val initial: LedgerSetup) : HomeRepository {
        var savedTarget: Pair<Int, Long?>? = null
        override fun observeYear(year: Int): Flow<HomeYearSnapshot> =
            flowOf(HomeYearSnapshot(initial, emptyList()))
        override suspend fun updateAnnualSavingsTarget(targetWon: Long?) = Unit
        override suspend fun updateAnnualSavingsTarget(year: Int, targetWon: Long?) {
            savedTarget = year to targetWon
        }
    }
}
