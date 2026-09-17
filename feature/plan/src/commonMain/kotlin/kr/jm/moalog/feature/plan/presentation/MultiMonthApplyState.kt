package kr.jm.moalog.feature.plan.presentation

import kr.jm.moalog.core.database.ExistingPlanPolicy
import kr.jm.moalog.core.database.PlanCopyPreview
import kr.jm.moalog.core.database.PlanCopyRequest
import kr.jm.moalog.core.database.PlanCopyResult
import kr.jm.moalog.core.model.PlanItemType
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.plan.domain.PlanRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MultiMonthApplyArgs(
    val sourceMonth: YearMonthKey,
    val type: PlanItemType,
    val sourceItemId: Long? = null,
)

enum class MultiMonthApplyStep { SelectMonths, Preview, Complete }

data class MultiMonthApplyUiState(
    val args: MultiMonthApplyArgs,
    val displayedYear: Int = args.sourceMonth.year,
    val selectedMonths: Set<YearMonthKey> = emptySet(),
    val step: MultiMonthApplyStep = MultiMonthApplyStep.SelectMonths,
    val policy: ExistingPlanPolicy = ExistingPlanPolicy.KeepExisting,
    val sourceItemName: String? = null,
    val preview: PlanCopyPreview? = null,
    val result: PlanCopyResult? = null,
    val isSourceLoading: Boolean = args.sourceItemId != null,
    val initialLoadFailed: Boolean = false,
    val isLoading: Boolean = false,
    val showApplyConfirmation: Boolean = false,
    val error: String? = null,
) {
    val canContinue: Boolean get() =
        step == MultiMonthApplyStep.SelectMonths &&
            selectedMonths.isNotEmpty() &&
            !isSourceLoading &&
            !initialLoadFailed &&
            !isLoading

    val canApply: Boolean get() =
        step == MultiMonthApplyStep.Preview &&
            preview != null &&
            selectedMonths.isNotEmpty() &&
            !isLoading
}

data class MultiMonthApplyDraftSnapshot(
    val displayedYear: Int,
    val selectedMonths: List<YearMonthKey>,
    val policy: ExistingPlanPolicy,
    val previewRequested: Boolean,
)

sealed interface MultiMonthApplyAction {
    data object PreviousYear : MultiMonthApplyAction
    data object NextYear : MultiMonthApplyAction
    data class ToggleMonth(val month: YearMonthKey) : MultiMonthApplyAction
    data object SelectDisplayedYear : MultiMonthApplyAction
    data object ClearSelection : MultiMonthApplyAction
    data object Continue : MultiMonthApplyAction
    data object PreviousStep : MultiMonthApplyAction
    data class SelectPolicy(val policy: ExistingPlanPolicy) : MultiMonthApplyAction
    data object RequestApply : MultiMonthApplyAction
    data object DismissApply : MultiMonthApplyAction
    data object ConfirmApply : MultiMonthApplyAction
    data object Retry : MultiMonthApplyAction
    data class RestoreDraft(val snapshot: MultiMonthApplyDraftSnapshot) : MultiMonthApplyAction
}

class MultiMonthApplyStateHolder(
    private val args: MultiMonthApplyArgs,
    private val repository: PlanRepository,
    parentScope: CoroutineScope,
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val mutableState = MutableStateFlow(MultiMonthApplyUiState(args))
    val state: StateFlow<MultiMonthApplyUiState> = mutableState.asStateFlow()
    private var work: Job? = null
    private var sourceLoad: Job? = null
    private var started = false
    private var restorePreviewWhenReady = false

    fun start() {
        if (started) return
        started = true
        if (args.sourceItemId == null) {
            edit { copy(isSourceLoading = false, initialLoadFailed = false) }
            if (restorePreviewWhenReady) loadPreview()
        } else loadSourceItem()
    }

    fun close() = job.cancel()

    fun onAction(action: MultiMonthApplyAction) {
        when (action) {
            MultiMonthApplyAction.PreviousYear -> if (canEditSelection() && mutableState.value.displayedYear > YearMonthKey.MIN_YEAR) edit { copy(displayedYear = displayedYear - 1) }
            MultiMonthApplyAction.NextYear -> if (canEditSelection() && mutableState.value.displayedYear < YearMonthKey.MAX_YEAR) edit { copy(displayedYear = displayedYear + 1) }
            is MultiMonthApplyAction.ToggleMonth -> edit {
                if (!canEditSelection() || action.month == args.sourceMonth) this else copy(
                    selectedMonths = if (action.month in selectedMonths) selectedMonths - action.month else selectedMonths + action.month,
                    preview = null,
                    error = null,
                )
            }
            MultiMonthApplyAction.SelectDisplayedYear -> edit {
                if (!canEditSelection()) return@edit this
                val months = (1..12).map { YearMonthKey(displayedYear, it) }.filter { it != args.sourceMonth }.toSet()
                copy(selectedMonths = selectedMonths + months, preview = null, error = null)
            }
            MultiMonthApplyAction.ClearSelection -> edit {
                if (!canEditSelection()) this else copy(selectedMonths = emptySet(), preview = null, error = null)
            }
            MultiMonthApplyAction.Continue -> loadPreview()
            MultiMonthApplyAction.Retry -> {
                if (mutableState.value.initialLoadFailed) loadSourceItem() else loadPreview()
            }
            MultiMonthApplyAction.PreviousStep -> {
                if (mutableState.value.step == MultiMonthApplyStep.Preview && !mutableState.value.isLoading) {
                    edit { copy(step = MultiMonthApplyStep.SelectMonths, preview = null, showApplyConfirmation = false, error = null) }
                }
            }
            is MultiMonthApplyAction.SelectPolicy -> edit {
                if (step != MultiMonthApplyStep.Preview || isLoading) this else copy(policy = action.policy, error = null)
            }
            MultiMonthApplyAction.RequestApply -> edit {
                if (!canApply) this else copy(showApplyConfirmation = true)
            }
            MultiMonthApplyAction.DismissApply -> edit { copy(showApplyConfirmation = false) }
            MultiMonthApplyAction.ConfirmApply -> apply()
            is MultiMonthApplyAction.RestoreDraft -> restore(action.snapshot)
        }
    }

    private fun loadSourceItem() {
        val sourceItemId = args.sourceItemId ?: return
        if (sourceLoad?.isActive == true || mutableState.value.step == MultiMonthApplyStep.Complete) return
        mutableState.value = mutableState.value.copy(
            isSourceLoading = true,
            initialLoadFailed = false,
            error = null,
        )
        sourceLoad = scope.launch {
            try {
                val item = repository.find(sourceItemId)
                check(item != null && item.attributionMonth == args.sourceMonth && item.type == args.type)
                mutableState.value = mutableState.value.copy(
                    sourceItemName = item.name,
                    isSourceLoading = false,
                    initialLoadFailed = false,
                    error = null,
                )
                if (restorePreviewWhenReady) loadPreview()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                restorePreviewWhenReady = false
                mutableState.value = mutableState.value.copy(
                    sourceItemName = null,
                    isSourceLoading = false,
                    initialLoadFailed = true,
                    error = "복사할 계획 항목을 불러오지 못했어요",
                )
            }
        }
    }

    private fun loadPreview() {
        val current = mutableState.value
        if (!current.canContinue || work?.isActive == true) return
        restorePreviewWhenReady = false
        mutableState.value = current.copy(isLoading = true, error = null)
        val request = current.request()
        work = scope.launch {
            try {
                val preview = repository.previewCopy(request)
                check(preview.targetMonths.map { it.month }.toSet() == request.targetMonths.toSet())
                mutableState.value = mutableState.value.copy(
                    step = MultiMonthApplyStep.Preview,
                    preview = preview,
                    isLoading = false,
                    error = null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(isLoading = false, error = "변경 내용을 불러오지 못했어요")
            }
        }
    }

    private fun apply() {
        val current = mutableState.value
        if (!current.showApplyConfirmation || !current.canApply || work?.isActive == true) return
        val previewMonths = current.preview?.targetMonths?.map { it.month }?.toSet()
        if (previewMonths != current.selectedMonths) {
            mutableState.value = current.copy(showApplyConfirmation = false, error = "선택한 달의 변경 내용을 다시 확인해 주세요")
            return
        }
        mutableState.value = current.copy(showApplyConfirmation = false, isLoading = true, error = null)
        val request = current.request()
        work = scope.launch {
            try {
                val result = repository.copyToMonths(request)
                mutableState.value = mutableState.value.copy(
                    step = MultiMonthApplyStep.Complete,
                    result = result,
                    isLoading = false,
                    error = null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(isLoading = false, error = "적용하지 못했어요. 변경 내용은 저장되지 않았습니다")
            }
        }
    }

    private fun restore(snapshot: MultiMonthApplyDraftSnapshot) {
        if (mutableState.value.step == MultiMonthApplyStep.Complete || mutableState.value.isLoading) return
        val restoredMonths = snapshot.selectedMonths
            .filter { it != args.sourceMonth }
            .toSet()
        val displayedYear = snapshot.displayedYear.coerceIn(YearMonthKey.MIN_YEAR, YearMonthKey.MAX_YEAR)
        mutableState.value = mutableState.value.copy(
            displayedYear = displayedYear,
            selectedMonths = restoredMonths,
            step = MultiMonthApplyStep.SelectMonths,
            policy = snapshot.policy,
            preview = null,
            result = null,
            showApplyConfirmation = false,
            error = null,
        )
        restorePreviewWhenReady = snapshot.previewRequested && restoredMonths.isNotEmpty()
        if (restorePreviewWhenReady && started && !mutableState.value.isSourceLoading && !mutableState.value.initialLoadFailed) {
            loadPreview()
        }
    }

    private fun canEditSelection(): Boolean {
        val current = mutableState.value
        return current.step == MultiMonthApplyStep.SelectMonths && !current.isLoading
    }

    private fun MultiMonthApplyUiState.request() = PlanCopyRequest(
        sourceMonth = args.sourceMonth,
        type = args.type,
        sourceItemId = args.sourceItemId,
        targetMonths = selectedMonths.sortedWith(compareBy(YearMonthKey::year, YearMonthKey::month)),
        policy = policy,
    )

    private inline fun edit(block: MultiMonthApplyUiState.() -> MultiMonthApplyUiState) {
        mutableState.value = mutableState.value.block()
    }
}

fun MultiMonthApplyUiState.toDraftSnapshot(): MultiMonthApplyDraftSnapshot = MultiMonthApplyDraftSnapshot(
    displayedYear = displayedYear,
    selectedMonths = selectedMonths.sortedWith(compareBy(YearMonthKey::year, YearMonthKey::month)),
    policy = policy,
    previewRequested = step == MultiMonthApplyStep.Preview,
)

fun multiMonthTypeLabel(type: PlanItemType): String = when (type) {
    PlanItemType.Income -> "수입"
    PlanItemType.FixedExpense -> "고정지출"
    PlanItemType.Savings -> "저축·투자"
}
