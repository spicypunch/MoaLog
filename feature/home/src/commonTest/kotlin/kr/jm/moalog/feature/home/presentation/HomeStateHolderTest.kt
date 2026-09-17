package kr.jm.moalog.feature.home.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kr.jm.moalog.core.model.LedgerMember
import kr.jm.moalog.core.model.LedgerSetup
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.home.domain.HomeRepository
import kr.jm.moalog.feature.home.domain.HomeYearSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class HomeStateHolderTest {
    @Test
    fun goalEditorDistinguishesMissingFromEnteredZeroAndRejectsLongOverflow() = runTest {
        val repository = FakeHomeRepository(setup(target = null))
        val holder = HomeStateHolder(repository, YearMonthKey(2026, 5), backgroundScope)
        runCurrent()

        holder.onAction(HomeAction.OpenGoalEditor)
        holder.onAction(HomeAction.GoalInputChanged("0"))
        holder.onAction(HomeAction.SaveGoal)
        runCurrent()

        assertEquals(0L, repository.savedTargetByYear[2026])
        assertFalse(holder.state.value.isGoalEditorVisible)

        holder.onAction(HomeAction.OpenGoalEditor)
        holder.onAction(HomeAction.GoalInputChanged("9999999999999999999"))
        holder.onAction(HomeAction.SaveGoal)
        runCurrent()

        assertTrue(holder.state.value.isGoalEditorVisible)
        assertEquals("허용 범위 안의 금액을 입력해 주세요", holder.state.value.goalInputError)
        assertEquals(1, repository.saveCount)

        holder.onAction(HomeAction.GoalInputChanged("-1"))
        holder.onAction(HomeAction.SaveGoal)
        runCurrent()

        assertEquals("허용 범위 안의 금액을 입력해 주세요", holder.state.value.goalInputError)
        assertEquals(1, repository.saveCount)

        holder.onAction(HomeAction.GoalInputChanged("천만원"))
        holder.onAction(HomeAction.SaveGoal)
        runCurrent()

        assertEquals("허용 범위 안의 금액을 입력해 주세요", holder.state.value.goalInputError)
        assertEquals(1, repository.saveCount)

        holder.onAction(HomeAction.GoalInputChanged(""))
        holder.onAction(HomeAction.SaveGoal)
        runCurrent()

        assertNull(repository.savedTargetByYear[2026])
        assertEquals(2, repository.saveCount)
    }

    @Test
    fun goalEditorCanSaveAndClearTargetForSelectedYearSeparatelyFromBaseYear() = runTest {
        val repository = FakeHomeRepository(
            setup(
                targetByYear = mapOf(
                    2026 to 1_000_000L,
                    2027 to null,
                ),
            ),
        )
        val holder = HomeStateHolder(repository, YearMonthKey(2026, 5), backgroundScope)
        runCurrent()

        assertEquals(1_000_000L, holder.state.value.annualSavingsTargetWon)

        holder.onAction(HomeAction.NextYear)
        runCurrent()

        holder.onAction(HomeAction.OpenGoalEditor)
        holder.onAction(HomeAction.GoalInputChanged("250000"))
        holder.onAction(HomeAction.SaveGoal)
        runCurrent()

        assertEquals(250_000L, repository.savedTargetByYear[2027])
        assertEquals(250_000L, holder.state.value.annualSavingsTargetWon)

        holder.onAction(HomeAction.OpenGoalEditor)
        holder.onAction(HomeAction.GoalInputChanged(""))
        holder.onAction(HomeAction.SaveGoal)
        runCurrent()

        assertNull(repository.savedTargetByYear[2027])
        assertEquals(2, repository.saveCount)
        assertEquals(1_000_000L, repository.savedTargetByYear[2026])
    }

    @Test
    fun nextYearKeepsSelectedMonthForMonthlySummary() = runTest {
        val repository = FakeHomeRepository(setup(target = null))
        val holder = HomeStateHolder(repository, YearMonthKey(2026, 5), backgroundScope)
        runCurrent()

        holder.onAction(HomeAction.NextYear)
        runCurrent()

        assertEquals(2027, holder.state.value.selectedYear)
        assertEquals(5, holder.state.value.selectedMonth.month)
        assertEquals(2027, holder.state.value.monthlySummary.year)
        assertEquals(5, holder.state.value.monthlySummary.month)
    }

    private class FakeHomeRepository(initialSetup: LedgerSetup) : HomeRepository {
        private val setup = MutableStateFlow(initialSetup)
        val savedTargetByYear = initialSetup.annualSavingsTargetsWon.toMutableMap()
        var saveCount = 0

        override fun observeYear(year: Int): Flow<HomeYearSnapshot> = flow {
            setup.collect { current ->
                emit(
                    HomeYearSnapshot(
                        current,
                        (1..12).map { month ->
                            HomeAnnualMonthSnapshot(YearMonthKey(year, month), emptyList(), emptyList())
                        },
                    ),
                )
            }
        }

        override suspend fun updateAnnualSavingsTarget(targetWon: Long?) {
            updateAnnualSavingsTarget(setup.value.baseYear, targetWon)
        }

        override suspend fun updateAnnualSavingsTarget(year: Int, targetWon: Long?) {
            saveCount += 1
            if (targetWon == null) {
                savedTargetByYear.remove(year)
            } else {
                savedTargetByYear[year] = targetWon
            }
            val currentTargets = savedTargetByYear.toMap()
            setup.value = currentTargets.let {
                setup.value.copy(
                    annualSavingsTargetWon = it[setup.value.baseYear],
                    annualSavingsTargetsWon = it,
                )
            }
        }
    }

    private fun setup(target: Long? = null, targetByYear: Map<Int, Long?> = mapOf(2026 to target)) = LedgerSetup(
        ledgerName = "우리 가계부",
        members = listOf(LedgerMember("수아", 0), LedgerMember("종민", 1)),
        baseYear = 2026,
        annualSavingsTargetWon = targetByYear[2026],
        annualSavingsTargetsWon = targetByYear.filterValues { it != null }.mapValues { it.value as Long },
    )
}
