package kr.jm.moalog.feature.home.presentation

import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.home.domain.HomeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface HomeAction {
    data object PreviousYear : HomeAction
    data object NextYear : HomeAction
    data object Retry : HomeAction
    data object OpenGoalEditor : HomeAction
    data object DismissGoalEditor : HomeAction
    data class GoalInputChanged(val value: String) : HomeAction
    data object SaveGoal : HomeAction
    data object RemoveGoal : HomeAction
}

class HomeStateHolder(
    private val repository: HomeRepository,
    private val initialMonth: YearMonthKey,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(
        HomeUiState(
            ledgerName = "",
            memberNames = "",
            baseYear = initialMonth.year,
            selectedYear = initialMonth.year,
            annualSavingsTargetWon = null,
            monthlySummary = HomeMonthlySummary(initialMonth.year, initialMonth.month, isLoading = true),
            annualSummary = HomeAnnualSummary(initialMonth.year, isLoading = true),
            isLoading = true,
        ),
    )
    val state: StateFlow<HomeUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var saveJob: Job? = null

    init {
        load(initialMonth.year)
    }

    fun onAction(action: HomeAction) {
        when (action) {
            HomeAction.PreviousYear -> selectYear(mutableState.value.selectedYear - 1)
            HomeAction.NextYear -> selectYear(mutableState.value.selectedYear + 1)
            HomeAction.Retry -> load(mutableState.value.selectedYear)
            HomeAction.OpenGoalEditor -> {
                val state = mutableState.value
                mutableState.value = state.copy(
                    isGoalEditorVisible = true,
                    goalInput = state.annualSavingsTargetWon?.toString().orEmpty(),
                    goalInputError = null,
                    goalSaveError = null,
                )
            }
            HomeAction.DismissGoalEditor -> if (!mutableState.value.isSavingGoal) {
                mutableState.value = mutableState.value.copy(isGoalEditorVisible = false, goalInputError = null, goalSaveError = null)
            }
            is HomeAction.GoalInputChanged -> {
                mutableState.value = mutableState.value.copy(
                    goalInput = action.value.take(20),
                    goalInputError = null,
                    goalSaveError = null,
                )
            }
            HomeAction.SaveGoal -> saveGoal(remove = false)
            HomeAction.RemoveGoal -> saveGoal(remove = true)
        }
    }

    fun close() {
        loadJob?.cancel()
        saveJob?.cancel()
    }

    private fun selectYear(year: Int) {
        if (year !in 1900..9999 || year == mutableState.value.selectedYear) return
        load(year)
    }

    private fun load(year: Int) {
        loadJob?.cancel()
        val previous = mutableState.value
        mutableState.value = previous.copy(
            selectedYear = year,
            selectedMonth = YearMonthKey(year, initialMonth.month),
            isLoading = true,
            loadError = null,
            annualSummary = HomeAnnualSummary(year = year, isLoading = true),
            isGoalEditorVisible = false,
        )
        loadJob = scope.launch {
            try {
                repository.observeYear(year).collect { snapshot ->
                    val setup = snapshot.setup
                    if (setup == null) {
                        mutableState.value = mutableState.value.copy(isLoading = false, loadError = "가계부 설정을 찾을 수 없어요")
                        return@collect
                    }
                    val annual = calculateHomeAnnualSummary(setup, year, snapshot.months)
                    val selectedMonth = YearMonthKey(year, initialMonth.month)
                    val month = snapshot.months.firstOrNull { it.month == selectedMonth }
                    val monthly = if (month == null) {
                        HomeMonthlySummary(selectedMonth.year, selectedMonth.month)
                    } else {
                        HomeMonthlySummary(
                            year = month.month.year,
                            month = month.month.month,
                            expenseTotalWon = month.variableExpenses.map { it.amountWon }.checkedMoneySum(),
                            overspentTotalWon = month.variableExpenses.filter { it.overspent }.map { it.amountWon }.checkedMoneySum(),
                            overspentCount = month.variableExpenses.count { it.overspent },
                        )
                    }
                    val editorState = mutableState.value
                    mutableState.value = setup.toHomeUiState(monthly, year, annual).copy(
                        isLoading = false,
                        isGoalEditorVisible = editorState.isGoalEditorVisible,
                        goalInput = editorState.goalInput,
                        goalInputError = editorState.goalInputError,
                        isSavingGoal = editorState.isSavingGoal,
                        goalSaveError = editorState.goalSaveError,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(isLoading = false, loadError = "홈 정보를 불러오지 못했어요")
            }
        }
    }

    private fun saveGoal(remove: Boolean) {
        val state = mutableState.value
        if (state.isSavingGoal) return
        val target = when {
            remove -> null
            state.goalInput.isBlank() -> null
            else -> state.goalInput.toLongOrNull()?.takeIf { it >= 0L } ?: run {
                mutableState.value = state.copy(goalInputError = "허용 범위 안의 금액을 입력해 주세요")
                return
            }
        }
        saveJob?.cancel()
        mutableState.value = state.copy(isSavingGoal = true, goalInputError = null, goalSaveError = null)
        saveJob = scope.launch {
            try {
                repository.updateAnnualSavingsTarget(state.selectedYear, target)
                mutableState.value = mutableState.value.copy(isSavingGoal = false, isGoalEditorVisible = false)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(isSavingGoal = false, goalSaveError = "저축 목표를 저장하지 못했어요")
            }
        }
    }
}
