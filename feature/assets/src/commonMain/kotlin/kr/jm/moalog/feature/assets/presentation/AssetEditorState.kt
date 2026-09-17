package kr.jm.moalog.feature.assets.presentation

import kr.jm.moalog.core.model.AssetGrowthRule
import kr.jm.moalog.core.model.AssetItem
import kr.jm.moalog.core.model.AssetKind
import kr.jm.moalog.core.model.AssetPortfolio
import kr.jm.moalog.core.model.AssetType
import kr.jm.moalog.core.model.AssetValuation
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.core.model.resolveValue
import kr.jm.moalog.feature.assets.domain.AssetRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AssetEditorMode { NewAsset, MonthlyValue, GrowthRule }
enum class AssetEditorOperation { Load, Save }

data class AssetEditorArgs(
    val assetId: Long?,
    val month: YearMonthKey,
    val mode: AssetEditorMode,
    val purposeAccount: Boolean = false,
)

data class AssetEditorDraftSnapshot(
    val selectedMode: AssetEditorMode,
    val name: String,
    val type: AssetType,
    val ownerMemberOrder: Int?,
    val memo: String,
    val kind: AssetKind,
    val month: YearMonthKey,
    val amount: String,
    val duration: String,
    val baseAmount: String,
    val monthlyIncrease: String,
)

data class AssetEditorUiState(
    val args: AssetEditorArgs,
    val selectedMode: AssetEditorMode = args.mode,
    val name: String = "",
    val type: AssetType = if (args.purposeAccount) AssetType.Deposit else AssetType.Cash,
    val ownerMemberOrder: Int? = null,
    val memo: String = "",
    val kind: AssetKind = if (args.purposeAccount) AssetKind.PurposeAccount else AssetKind.Ordinary,
    val month: YearMonthKey = args.month,
    val amount: String = "",
    val duration: String = "12",
    val baseAmount: String = "",
    val monthlyIncrease: String = "",
    val isLoading: Boolean = args.assetId != null,
    val isSaving: Boolean = false,
    val error: String? = null,
    val failedOperation: AssetEditorOperation? = null,
    val saved: Boolean = false,
    val savedAssetId: Long? = null,
    val savedMonth: YearMonthKey? = null,
    val isPreviewVisible: Boolean = false,
    val isDirty: Boolean = false,
) {
    val canSave: Boolean get() = !isLoading && !isSaving && error == null
    fun toDraftSnapshot() = AssetEditorDraftSnapshot(
        selectedMode, name, type, ownerMemberOrder, memo, kind, month, amount, duration, baseAmount, monthlyIncrease,
    )
}

sealed interface AssetEditorAction {
    data class SelectMode(val value: AssetEditorMode) : AssetEditorAction
    data class ChangeName(val value: String) : AssetEditorAction
    data class ChangeType(val value: AssetType) : AssetEditorAction
    data class ChangeOwner(val value: Int?) : AssetEditorAction
    data class ChangeMemo(val value: String) : AssetEditorAction
    data class ChangeKind(val value: AssetKind) : AssetEditorAction
    data class ChangeMonth(val value: YearMonthKey) : AssetEditorAction
    data class ChangeAmount(val value: String) : AssetEditorAction
    data class ChangeDuration(val value: String) : AssetEditorAction
    data class ChangeBaseAmount(val value: String) : AssetEditorAction
    data class ChangeMonthlyIncrease(val value: String) : AssetEditorAction
    data class RestoreDraft(val value: AssetEditorDraftSnapshot) : AssetEditorAction
    data object TogglePreview : AssetEditorAction
    data object Save : AssetEditorAction
    data object Retry : AssetEditorAction
}

class AssetEditorStateHolder(
    private val args: AssetEditorArgs,
    private val repository: AssetRepository,
    parentScope: CoroutineScope,
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val mutableState = MutableStateFlow(AssetEditorUiState(args))
    val state: StateFlow<AssetEditorUiState> = mutableState.asStateFlow()
    private var asset: AssetItem? = null
    private var portfolio: AssetPortfolio? = null
    private var growthRule: AssetGrowthRule? = null
    private var observation: Job? = null
    private var originalDraft = mutableState.value.toDraftSnapshot()
    private var pendingDraft: AssetEditorDraftSnapshot? = null

    init {
        if (args.assetId == null) {
            mutableState.value = mutableState.value.copy(isLoading = false)
            originalDraft = mutableState.value.toDraftSnapshot()
        } else load()
    }

    fun close() = job.cancel()

    fun onAction(action: AssetEditorAction) {
        when (action) {
            is AssetEditorAction.SelectMode -> selectMode(action.value)
            is AssetEditorAction.ChangeName -> edit { copy(name = action.value) }
            is AssetEditorAction.ChangeType -> edit { copy(type = action.value) }
            is AssetEditorAction.ChangeOwner -> edit { copy(ownerMemberOrder = action.value) }
            is AssetEditorAction.ChangeMemo -> edit { copy(memo = action.value) }
            is AssetEditorAction.ChangeKind -> edit { copy(kind = action.value) }
            is AssetEditorAction.ChangeMonth -> changeMonth(action.value)
            is AssetEditorAction.ChangeAmount -> edit { copy(amount = digits(action.value)) }
            is AssetEditorAction.ChangeDuration -> edit { copy(duration = digits(action.value)) }
            is AssetEditorAction.ChangeBaseAmount -> edit { copy(baseAmount = digits(action.value), isPreviewVisible = false) }
            is AssetEditorAction.ChangeMonthlyIncrease -> edit { copy(monthlyIncrease = signedDigits(action.value), isPreviewVisible = false) }
            is AssetEditorAction.RestoreDraft -> restoreDraft(action.value)
            AssetEditorAction.TogglePreview -> edit(clearError = false) { copy(isPreviewVisible = !isPreviewVisible) }
            AssetEditorAction.Save -> save()
            AssetEditorAction.Retry -> when (mutableState.value.failedOperation) {
                AssetEditorOperation.Load -> load()
                AssetEditorOperation.Save -> {
                    mutableState.value = mutableState.value.copy(error = null, failedOperation = null)
                    save()
                }
                null -> if (args.assetId != null && asset == null) load() else Unit
            }
        }
    }

    private fun edit(clearError: Boolean = true, transform: AssetEditorUiState.() -> AssetEditorUiState) {
        val current = mutableState.value
        if (current.isLoading || current.isSaving || current.saved) return
        val changed = current.transform().let { if (clearError) it.copy(error = null, failedOperation = null) else it }
        mutableState.value = changed.copy(isDirty = changed.toDraftSnapshot() != originalDraft)
    }

    private fun load() {
        if (observation?.isActive == true || mutableState.value.isSaving) return
        mutableState.value = mutableState.value.copy(isLoading = true, error = null, failedOperation = null)
        observation = scope.launch {
            try {
                repository.observePortfolio().collect { portfolio ->
                    this@AssetEditorStateHolder.portfolio = portfolio
                    val found = portfolio.assets.firstOrNull { it.id == args.assetId }
                    if (found == null) {
                        mutableState.value = mutableState.value.copy(
                            isLoading = false,
                            error = "자산 항목을 찾을 수 없어요. 다시 시도해 주세요",
                            failedOperation = AssetEditorOperation.Load,
                        )
                    } else {
                        asset = found
                        val value = portfolio.resolveValue(found.id, args.month)
                        val rule = portfolio.growthRules.firstOrNull { it.assetId == found.id }
                        growthRule = rule
                        mutableState.value = mutableState.value.copy(
                            name = found.name,
                            type = found.type,
                            ownerMemberOrder = found.ownerMemberOrder,
                            memo = found.memo.orEmpty(),
                            kind = found.kind,
                            amount = value.amountWon?.toString().orEmpty(),
                            duration = rule?.durationMonths?.toString() ?: "12",
                            baseAmount = rule?.baseAmountWon?.toString() ?: value.amountWon?.toString().orEmpty(),
                            monthlyIncrease = rule?.monthlyIncreaseWon?.toString().orEmpty(),
                            month = if (mutableState.value.selectedMode == AssetEditorMode.GrowthRule) rule?.startMonth ?: args.month else args.month,
                            isLoading = false,
                            error = null,
                            failedOperation = null,
                        )
                        originalDraft = mutableState.value.toDraftSnapshot()
                        val restored = pendingDraft
                        pendingDraft = null
                        if (restored == null) mutableState.value = mutableState.value.copy(isDirty = false)
                        else restoreDraft(restored)
                        observation?.cancel()
                    }
                }
            } catch (c: CancellationException) { throw c }
            catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    error = "기록을 불러오지 못했어요. 다시 시도해 주세요",
                    failedOperation = AssetEditorOperation.Load,
                )
            }
        }
    }

    private fun restoreDraft(draft: AssetEditorDraftSnapshot) {
        if (mutableState.value.isLoading) {
            pendingDraft = draft
            return
        }
        if (mutableState.value.isSaving || mutableState.value.saved) return
        mutableState.value = mutableState.value.copy(
            selectedMode = draft.selectedMode,
            name = draft.name,
            type = draft.type,
            ownerMemberOrder = draft.ownerMemberOrder,
            memo = draft.memo,
            kind = draft.kind,
            month = draft.month,
            amount = draft.amount,
            duration = draft.duration,
            baseAmount = draft.baseAmount,
            monthlyIncrease = draft.monthlyIncrease,
            error = null,
            failedOperation = null,
            isDirty = draft != originalDraft,
        )
    }

    private fun selectMode(mode: AssetEditorMode) {
        val current = mutableState.value
        if (current.isLoading || current.isSaving) return
        val selectedMonth = if (mode == AssetEditorMode.GrowthRule) growthRule?.startMonth ?: args.month else current.month
        val resolvedAmount = portfolio?.let { source -> args.assetId?.let { source.resolveValue(it, selectedMonth).amountWon } }
        edit {
            copy(
                selectedMode = mode,
                month = selectedMonth,
                amount = if (mode == AssetEditorMode.MonthlyValue || mode == AssetEditorMode.NewAsset) resolvedAmount?.toString().orEmpty() else amount,
                baseAmount = if (mode == AssetEditorMode.GrowthRule) growthRule?.baseAmountWon?.toString() ?: resolvedAmount?.toString().orEmpty() else baseAmount,
                monthlyIncrease = if (mode == AssetEditorMode.GrowthRule) growthRule?.monthlyIncreaseWon?.toString().orEmpty() else monthlyIncrease,
                duration = if (mode == AssetEditorMode.GrowthRule) growthRule?.durationMonths?.toString() ?: "12" else duration,
                isPreviewVisible = false,
            )
        }
    }

    private fun changeMonth(month: YearMonthKey) {
        val current = mutableState.value
        if (current.isLoading || current.isSaving) return
        val amount = if (current.selectedMode == AssetEditorMode.MonthlyValue || current.selectedMode == AssetEditorMode.NewAsset) {
            portfolio?.let { source -> args.assetId?.let { source.resolveValue(it, month).amountWon } }?.toString().orEmpty()
        } else current.amount
        edit { copy(month = month, amount = amount, isPreviewVisible = false) }
    }

    private fun save() {
        val state = mutableState.value
        if (state.isLoading || state.isSaving || state.saved || state.error != null) return
        val validation = validate(state)
        if (validation != null) {
            mutableState.value = state.copy(error = validation)
            return
        }
        mutableState.value = state.copy(isSaving = true, error = null, failedOperation = null)
        scope.launch {
            try {
                val savedId = when (state.selectedMode) {
                    AssetEditorMode.NewAsset -> {
                        val updatedAsset = AssetItem(
                            id = asset?.id ?: 0,
                            name = state.name.trim(),
                            type = state.type,
                            ownerMemberOrder = state.ownerMemberOrder,
                            memo = state.memo.trim().ifEmpty { null },
                            kind = state.kind,
                        )
                        if (asset == null) repository.addAsset(updatedAsset, state.month, state.amount.toLong())
                        else {
                            repository.updateAssetWithValuation(
                                updatedAsset,
                                AssetValuation(updatedAsset.id, state.month, state.amount.toLong()),
                            )
                            updatedAsset.id
                        }
                    }
                    AssetEditorMode.MonthlyValue -> {
                        repository.saveValuation(AssetValuation(requireNotNull(args.assetId), state.month, state.amount.toLong()))
                        requireNotNull(args.assetId)
                    }
                    AssetEditorMode.GrowthRule -> {
                        repository.saveGrowthRule(
                            AssetGrowthRule(
                                requireNotNull(args.assetId),
                                state.month,
                                state.duration.toInt(),
                                state.baseAmount.toLong(),
                                state.monthlyIncrease.toLong(),
                            ),
                        )
                        requireNotNull(args.assetId)
                    }
                }
                mutableState.value = mutableState.value.copy(
                    isSaving = false,
                    saved = true,
                    savedAssetId = savedId,
                    savedMonth = state.month,
                    isDirty = false,
                )
            } catch (c: CancellationException) { throw c }
            catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(
                    isSaving = false,
                    error = "저장하지 못했어요. 다시 시도해 주세요",
                    failedOperation = AssetEditorOperation.Save,
                )
            }
        }
    }
}

internal fun validate(state: AssetEditorUiState): String? = when (state.selectedMode) {
    AssetEditorMode.NewAsset -> when {
        state.name.isBlank() -> "자산 이름을 입력해 주세요"
        state.amount.toLongOrNull() == null -> "초기 금액을 입력해 주세요"
        else -> null
    }
    AssetEditorMode.MonthlyValue -> if (state.amount.toLongOrNull() == null) "금액을 입력해 주세요" else null
    AssetEditorMode.GrowthRule -> when {
        state.duration.toIntOrNull()?.let { it > 0 } != true -> "반복 기간을 입력해 주세요"
        state.baseAmount.toLongOrNull() == null -> "현재 기준 금액을 입력해 주세요"
        state.monthlyIncrease.toLongOrNull() == null -> "매월 예상 증가액을 입력해 주세요"
        else -> null
    }
}

private fun digits(value: String) = value.filter(Char::isDigit)

private fun signedDigits(value: String): String {
    val digits = value.filter(Char::isDigit)
    return if (value.trimStart().startsWith("-")) "-$digits" else digits
}
