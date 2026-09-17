package kr.jm.moalog.feature.plan.presentation

import kr.jm.moalog.core.model.MonthlyPlanItem
import kr.jm.moalog.core.model.PlanCatalogItem
import kr.jm.moalog.core.model.PlanItemStatus
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class PlanEditorArgs(val itemId: Long?, val type: PlanItemType, val initialMonth: YearMonthKey)

data class PlanEditorErrors(
    val month: String? = null,
    val name: String? = null,
    val amount: String? = null,
    val category: String? = null,
) {
    val hasAny: Boolean get() = month != null || name != null || amount != null || category != null
}

enum class PlanEditorCompletionKind { Saved, SavedAndApply, Deleted }

data class PlanEditorCompletion(
    val kind: PlanEditorCompletionKind,
    val itemId: Long,
    val type: PlanItemType,
    val month: YearMonthKey,
)

/** Serializable, platform-neutral fields needed to survive host process recreation. */
data class PlanEditorDraftSnapshot(
    val type: PlanItemType,
    val month: String,
    val name: String,
    val amount: String,
    val category: String,
    val status: PlanItemStatus,
    val ownerMemberOrder: Int?,
    val memo: String,
    val includePurposeAccount: Boolean,
    val includeNetSavings: Boolean,
    val errors: PlanEditorErrors,
    val showDiscardConfirmation: Boolean,
    val hasUnsavedChanges: Boolean,
    val selectedCatalogId: Long? = null,
)

data class PlanEditorUiState(
    val itemId: Long? = null,
    val type: PlanItemType,
    val month: String,
    val name: String = "",
    val amount: String = "",
    val category: String = "",
    val status: PlanItemStatus = PlanItemStatus.Estimated,
    val ownerMemberOrder: Int? = null,
    val memo: String = "",
    val includePurposeAccount: Boolean = false,
    val includeNetSavings: Boolean = false,
    val errors: PlanEditorErrors = PlanEditorErrors(),
    val validationAttempt: Int = 0,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val persistenceError: String? = null,
    val initialLoadFailed: Boolean = false,
    val showDeleteConfirmation: Boolean = false,
    val showDiscardConfirmation: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
    val exitRequested: Boolean = false,
    val completed: Boolean = false,
    val savedItemId: Long? = null,
    val completion: PlanEditorCompletion? = null,
    val catalogItems: List<PlanCatalogItem> = emptyList(),
    val selectedCatalogId: Long? = null,
) {
    val isEditing: Boolean get() = itemId != null
    val selectedCatalogIsArchived: Boolean get() = selectedCatalogId != null &&
        catalogItems.any { it.id == selectedCatalogId && it.archived }
}

sealed interface PlanEditorAction {
    data class RestoreDraft(val snapshot: PlanEditorDraftSnapshot) : PlanEditorAction
    data class TypeChanged(val value: PlanItemType) : PlanEditorAction
    data class MonthChanged(val value: String) : PlanEditorAction
    data object PreviousMonth : PlanEditorAction
    data object NextMonth : PlanEditorAction
    data class NameChanged(val value: String) : PlanEditorAction
    data class AmountChanged(val value: String) : PlanEditorAction
    data class AddAmount(val deltaWon: Long) : PlanEditorAction
    data object ResetAmount : PlanEditorAction
    data class CategoryChanged(val value: String) : PlanEditorAction
    data class StatusChanged(val value: PlanItemStatus) : PlanEditorAction
    data class OwnerChanged(val value: Int?) : PlanEditorAction
    data class MemoChanged(val value: String) : PlanEditorAction
    data class PurposeChanged(val value: Boolean) : PlanEditorAction
    data class NetChanged(val value: Boolean) : PlanEditorAction
    data class CatalogSelected(val catalogId: Long?) : PlanEditorAction
    data object Save : PlanEditorAction
    data object SaveAndApply : PlanEditorAction
    data object RetryLoad : PlanEditorAction
    data object RequestBack : PlanEditorAction
    data object DismissDiscard : PlanEditorAction
    data object ConfirmDiscard : PlanEditorAction
    data object RequestDelete : PlanEditorAction
    data object DismissDelete : PlanEditorAction
    data object ConfirmDelete : PlanEditorAction
}

data class ValidPlanInput(val month: YearMonthKey, val name: String, val amount: Long, val category: String)

fun validatePlanEditor(state: PlanEditorUiState): Pair<PlanEditorErrors, ValidPlanInput?> {
    val month = parsePlanMonth(state.month)
    val normalizedAmount = state.amount.trim().replace(",", "")
    val amount = normalizedAmount.toLongOrNull()
    val validAmount = amount != null && (state.type == PlanItemType.Savings || amount >= 0)
    val errors = PlanEditorErrors(
        month = if (month == null) "귀속월을 선택해 주세요" else null,
        name = if (state.name.isBlank()) "항목명을 입력해 주세요" else null,
        amount = when {
            normalizedAmount.isEmpty() -> "월 금액을 입력해 주세요"
            !validAmount -> if (state.type == PlanItemType.Savings) "금액을 숫자로 입력해 주세요" else "0원 이상의 금액을 입력해 주세요"
            else -> null
        },
        category = if (state.category.isBlank()) "카테고리를 선택해 주세요" else null,
    )
    return if (errors.hasAny) errors to null else errors to ValidPlanInput(
        month = requireNotNull(month),
        name = state.name.trim(),
        amount = requireNotNull(amount),
        category = state.category,
    )
}

private fun parsePlanMonth(value: String): YearMonthKey? {
    val parts = value.split('-')
    if (parts.size != 2) return null
    return runCatching { YearMonthKey(parts[0].toInt(), parts[1].toInt()) }.getOrNull()
}

class PlanEditorStateHolder(
    private val args: PlanEditorArgs,
    private val repository: PlanRepository,
    parentScope: CoroutineScope,
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val mutableState = MutableStateFlow(
        PlanEditorUiState(itemId = args.itemId, type = args.type, month = args.initialMonth.toString()),
    )
    val state: StateFlow<PlanEditorUiState> = mutableState.asStateFlow()
    private var original = mutableState.value.form()
    private var started = false
    private var pendingDraft: PlanEditorDraftSnapshot? = null

    fun start() {
        if (!started) {
            started = true
            scope.launch { load() }
        }
    }

    fun close() = job.cancel()

    fun onAction(action: PlanEditorAction) {
        when (action) {
            is PlanEditorAction.RestoreDraft -> restoreDraft(action.snapshot)
            is PlanEditorAction.TypeChanged -> changeType(action.value)
            is PlanEditorAction.MonthChanged -> edit { copy(month = action.value, errors = errors.copy(month = null)) }
            PlanEditorAction.PreviousMonth -> moveMonth(previous = true)
            PlanEditorAction.NextMonth -> moveMonth(previous = false)
            is PlanEditorAction.NameChanged -> edit { copy(name = action.value, errors = errors.copy(name = null)) }
            is PlanEditorAction.AmountChanged -> edit { copy(amount = action.value, errors = errors.copy(amount = null)) }
            is PlanEditorAction.AddAmount -> addAmount(action.deltaWon)
            PlanEditorAction.ResetAmount -> edit { copy(amount = "", errors = errors.copy(amount = null)) }
            is PlanEditorAction.CategoryChanged -> edit { copy(category = action.value, errors = errors.copy(category = null)) }
            is PlanEditorAction.StatusChanged -> edit { copy(status = action.value) }
            is PlanEditorAction.OwnerChanged -> edit { copy(ownerMemberOrder = action.value) }
            is PlanEditorAction.MemoChanged -> edit { copy(memo = action.value.take(MEMO_MAX_LENGTH)) }
            is PlanEditorAction.PurposeChanged -> edit {
                if (type == PlanItemType.Savings) copy(includePurposeAccount = action.value) else this
            }
            is PlanEditorAction.NetChanged -> edit {
                if (type == PlanItemType.Savings) copy(includeNetSavings = action.value) else this
            }
            is PlanEditorAction.CatalogSelected -> selectCatalog(action.catalogId)
            PlanEditorAction.Save -> save(PlanEditorCompletionKind.Saved)
            PlanEditorAction.SaveAndApply -> save(PlanEditorCompletionKind.SavedAndApply)
            PlanEditorAction.RetryLoad -> retryLoad()
            PlanEditorAction.RequestBack -> requestBack()
            PlanEditorAction.DismissDiscard -> mutableState.value = mutableState.value.copy(showDiscardConfirmation = false)
            PlanEditorAction.ConfirmDiscard -> mutableState.value = mutableState.value.copy(showDiscardConfirmation = false, exitRequested = true)
            PlanEditorAction.RequestDelete -> if (
                mutableState.value.isEditing &&
                !mutableState.value.isLoading &&
                !mutableState.value.isSaving &&
                !mutableState.value.initialLoadFailed
            ) {
                mutableState.value = mutableState.value.copy(showDeleteConfirmation = true)
            }
            PlanEditorAction.DismissDelete -> mutableState.value = mutableState.value.copy(showDeleteConfirmation = false)
            PlanEditorAction.ConfirmDelete -> delete()
        }
    }

    private fun requestBack() {
        val current = mutableState.value
        if (current.isSaving || current.completed) return
        mutableState.value = if (current.hasUnsavedChanges) current.copy(showDiscardConfirmation = true)
        else current.copy(exitRequested = true)
    }

    private fun restoreDraft(snapshot: PlanEditorDraftSnapshot) {
        val current = mutableState.value
        if (current.isSaving || current.completed) return
        if (current.isLoading || current.initialLoadFailed) {
            pendingDraft = snapshot
            return
        }
        applyDraft(snapshot)
    }

    private fun applyDraft(snapshot: PlanEditorDraftSnapshot) {
        val savings = snapshot.type == PlanItemType.Savings
        val selectedCatalogId = snapshot.selectedCatalogId?.takeIf { id ->
            mutableState.value.catalogItems.any { catalog ->
                catalog.id == id && catalog.type == snapshot.type && (!catalog.archived || mutableState.value.isEditing)
            }
        }
        mutableState.value = mutableState.value.copy(
            type = snapshot.type,
            month = snapshot.month,
            name = snapshot.name,
            amount = snapshot.amount,
            category = snapshot.category,
            status = snapshot.status,
            ownerMemberOrder = snapshot.ownerMemberOrder,
            memo = snapshot.memo.take(MEMO_MAX_LENGTH),
            includePurposeAccount = savings && snapshot.includePurposeAccount,
            includeNetSavings = savings && snapshot.includeNetSavings,
            errors = snapshot.errors,
            showDiscardConfirmation = snapshot.showDiscardConfirmation,
            hasUnsavedChanges = snapshot.hasUnsavedChanges,
            persistenceError = null,
            selectedCatalogId = selectedCatalogId,
        )
    }

    private fun selectCatalog(catalogId: Long?) {
        val current = mutableState.value
        if (current.isEditing || current.isSaving || current.completed) return
        if (catalogId == null) {
            edit { copy(selectedCatalogId = null) }
            return
        }
        val catalog = current.catalogItems.firstOrNull {
            it.id == catalogId && it.type == current.type && !it.archived
        } ?: return
        edit {
            copy(
                selectedCatalogId = catalog.id,
                name = catalog.name,
                category = catalog.classification,
                ownerMemberOrder = catalog.ownerMemberOrder,
                includePurposeAccount = catalog.includePurposeAccount,
                includeNetSavings = catalog.includeNetSavings,
                errors = errors.copy(name = null, category = null),
            )
        }
    }

    private fun retryLoad() {
        val current = mutableState.value
        if (!current.initialLoadFailed || current.isLoading || current.isSaving) return
        mutableState.value = current.copy(
            isLoading = true,
            initialLoadFailed = false,
            persistenceError = null,
        )
        scope.launch { load() }
    }

    private fun changeType(value: PlanItemType) {
        val current = mutableState.value
        if (current.isEditing || current.isSaving || current.type == value) return
        edit {
            copy(
                type = value,
                selectedCatalogId = null,
                category = "",
                includePurposeAccount = false,
                includeNetSavings = false,
                errors = errors.copy(category = null, amount = null),
            )
        }
    }

    private fun moveMonth(previous: Boolean) {
        val current = parsePlanMonth(mutableState.value.month) ?: return
        val moved = if (previous) previousPlanEditorMonth(current) else nextPlanEditorMonth(current)
        moved?.let { onAction(PlanEditorAction.MonthChanged(it.toString())) }
    }

    private fun addAmount(deltaWon: Long) {
        if (deltaWon <= 0) return
        val currentAmount = mutableState.value.amount.replace(",", "").toLongOrNull() ?: 0L
        val result = if (currentAmount > Long.MAX_VALUE - deltaWon) Long.MAX_VALUE else currentAmount + deltaWon
        edit { copy(amount = result.toString(), errors = errors.copy(amount = null)) }
    }

    private suspend fun load() {
        try {
            val catalogItems = repository.observeCatalog().first()
            val found = args.itemId?.let { repository.find(it) }
            if (args.itemId != null && found == null) {
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    initialLoadFailed = true,
                    persistenceError = "계획 항목을 찾지 못했어요",
                )
            } else {
                if (found != null) mutableState.value = mutableState.value.copy(
                    type = found.type,
                    month = found.attributionMonth.toString(),
                    name = found.name,
                    amount = found.amountWon?.toString().orEmpty(),
                    category = found.category,
                    status = found.status,
                    ownerMemberOrder = found.ownerMemberOrder,
                    memo = found.memo.orEmpty().take(MEMO_MAX_LENGTH),
                    includePurposeAccount = found.includePurposeAccount,
                    includeNetSavings = found.includeNetSavings,
                    selectedCatalogId = found.catalogId.takeIf { it > 0 },
                )
                mutableState.value = mutableState.value.copy(isLoading = false, catalogItems = catalogItems)
                original = mutableState.value.form()
                pendingDraft?.let { snapshot ->
                    pendingDraft = null
                    applyDraft(snapshot)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            mutableState.value = mutableState.value.copy(
                isLoading = false,
                initialLoadFailed = true,
                persistenceError = "입력 화면을 준비하지 못했어요",
            )
        }
    }

    private fun edit(block: PlanEditorUiState.() -> PlanEditorUiState) {
        val current = mutableState.value
        if (current.isSaving || current.completed || current.initialLoadFailed) return
        val changed = current.block().copy(persistenceError = null)
        mutableState.value = changed.copy(hasUnsavedChanges = changed.form() != original)
    }

    private fun save(kind: PlanEditorCompletionKind) {
        val beforeSave = mutableState.value
        if (beforeSave.isSaving || beforeSave.completed || beforeSave.isLoading || beforeSave.initialLoadFailed) return
        if (kind == PlanEditorCompletionKind.SavedAndApply && beforeSave.selectedCatalogIsArchived) {
            mutableState.value = beforeSave.copy(persistenceError = "보관된 관리 항목은 다른 달에 적용할 수 없어요")
            return
        }
        val (errors, valid) = validatePlanEditor(beforeSave)
        if (valid == null) {
            mutableState.value = beforeSave.copy(
                errors = errors,
                validationAttempt = beforeSave.validationAttempt + 1,
            )
            return
        }
        mutableState.value = beforeSave.copy(isSaving = true, errors = errors, persistenceError = null)
        scope.launch {
            try {
                val catalogId = beforeSave.selectedCatalogId
                    ?: beforeSave.catalogItems
                        .asSequence()
                        .filter { !it.archived && it.type == beforeSave.type }
                        .sortedWith(compareBy<PlanCatalogItem> { it.displayOrder }.thenBy { it.id })
                        .firstOrNull {
                            it.name.trim().equals(valid.name, ignoreCase = true) &&
                                it.classification.trim().equals(valid.category.trim(), ignoreCase = true) &&
                                it.ownerMemberOrder == beforeSave.ownerMemberOrder &&
                                it.includePurposeAccount == (beforeSave.type == PlanItemType.Savings && beforeSave.includePurposeAccount) &&
                                it.includeNetSavings == (beforeSave.type == PlanItemType.Savings && beforeSave.includeNetSavings)
                        }?.id
                val savedId = repository.save(MonthlyPlanItem(
                    id = beforeSave.itemId ?: 0,
                    type = beforeSave.type,
                    attributionMonth = valid.month,
                    name = valid.name,
                    amountWon = valid.amount,
                    category = valid.category,
                    status = beforeSave.status,
                    ownerMemberOrder = beforeSave.ownerMemberOrder,
                    memo = beforeSave.memo.trim().ifEmpty { null },
                    includePurposeAccount = beforeSave.type == PlanItemType.Savings && beforeSave.includePurposeAccount,
                    includeNetSavings = beforeSave.type == PlanItemType.Savings && beforeSave.includeNetSavings,
                    catalogId = catalogId ?: 0,
                ))
                mutableState.value = mutableState.value.copy(
                    itemId = savedId,
                    savedItemId = savedId,
                    isSaving = false,
                    hasUnsavedChanges = false,
                    completed = true,
                    completion = PlanEditorCompletion(kind, savedId, beforeSave.type, valid.month),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(isSaving = false, persistenceError = "저장하지 못했어요. 다시 시도해 주세요")
            }
        }
    }

    private fun delete() {
        val current = mutableState.value
        val id = current.itemId ?: return
        if (current.isSaving || current.completed || current.isLoading || current.initialLoadFailed) return
        val month = parsePlanMonth(current.month) ?: args.initialMonth
        mutableState.value = current.copy(showDeleteConfirmation = false, isSaving = true, persistenceError = null)
        scope.launch {
            try {
                repository.delete(id)
                mutableState.value = mutableState.value.copy(
                    isSaving = false,
                    hasUnsavedChanges = false,
                    completed = true,
                    completion = PlanEditorCompletion(PlanEditorCompletionKind.Deleted, id, current.type, month),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(isSaving = false, persistenceError = "삭제하지 못했어요")
            }
        }
    }

    private fun PlanEditorUiState.form() = listOf(
        type.name, month, name, amount, category, status.name, ownerMemberOrder?.toString(), memo,
        includePurposeAccount.toString(), includeNetSavings.toString(), selectedCatalogId?.toString(),
    )

    private companion object { const val MEMO_MAX_LENGTH = 100 }
}

fun PlanEditorUiState.toDraftSnapshot(): PlanEditorDraftSnapshot = PlanEditorDraftSnapshot(
    type = type,
    month = month,
    name = name,
    amount = amount,
    category = category,
    status = status,
    ownerMemberOrder = ownerMemberOrder,
    memo = memo,
    includePurposeAccount = includePurposeAccount,
    includeNetSavings = includeNetSavings,
    errors = errors,
    showDiscardConfirmation = showDiscardConfirmation,
    hasUnsavedChanges = hasUnsavedChanges,
    selectedCatalogId = selectedCatalogId,
)
