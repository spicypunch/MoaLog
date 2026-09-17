package kr.jm.moalog.feature.records.presentation

import kr.jm.moalog.core.model.ExpenseCategory
import kr.jm.moalog.core.model.ExpenseRecord
import kr.jm.moalog.core.model.LocalDateKey
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.records.domain.ExpenseRepository
import kr.jm.moalog.feature.records.domain.CurrentDateProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExpenseEditorArgs(val recordId: Long?, val initialMonth: YearMonthKey)

data class ExpenseEditorErrors(
    val amount: String? = null,
    val category: String? = null,
    val attributionMonth: String? = null,
    val actualDate: String? = null,
) {
    val hasAny: Boolean get() = amount != null || category != null || attributionMonth != null || actualDate != null
    val count: Int get() = listOf(amount, category, attributionMonth, actualDate).count { it != null }
}

enum class ExpensePersistenceOperation { Load, Save, Delete }

data class ExpenseEditorDraftSnapshot(
    val amount: String,
    val categoryId: String?,
    val attributionMonth: String,
    val actualDate: String,
    val detail: String,
    val overspent: Boolean,
)

data class ExpenseEditorUiState(
    val recordId: Long? = null,
    val amount: String = "",
    val categoryId: String? = null,
    val attributionMonth: String,
    val actualDate: String = "",
    val suggestedActualDate: LocalDateKey? = null,
    val detail: String = "",
    val overspent: Boolean = false,
    val categories: List<ExpenseCategory> = emptyList(),
    val errors: ExpenseEditorErrors = ExpenseEditorErrors(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val persistenceError: String? = null,
    val failedOperation: ExpensePersistenceOperation? = null,
    val showDeleteConfirmation: Boolean = false,
    val showDiscardConfirmation: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
    val exitRequested: Boolean = false,
    val completed: Boolean = false,
) {
    val isEditing: Boolean get() = recordId != null
    val canSave: Boolean get() = !isLoading && !isSaving && !errors.hasAny

    fun toDraftSnapshot() = ExpenseEditorDraftSnapshot(
        amount, categoryId, attributionMonth, actualDate, detail, overspent,
    )
}

sealed interface ExpenseEditorAction {
    data class AmountChanged(val value: String) : ExpenseEditorAction
    data class CategorySelected(val categoryId: String) : ExpenseEditorAction
    data class AttributionMonthChanged(val value: String) : ExpenseEditorAction
    data class ActualDateChanged(val value: String) : ExpenseEditorAction
    data object UseSuggestedActualDate : ExpenseEditorAction
    data class DetailChanged(val value: String) : ExpenseEditorAction
    data class OverspentChanged(val value: Boolean) : ExpenseEditorAction
    data object Save : ExpenseEditorAction
    data object RetryPersistence : ExpenseEditorAction
    data object DismissPersistenceError : ExpenseEditorAction
    data object ResetForm : ExpenseEditorAction
    data object RequestDelete : ExpenseEditorAction
    data object DismissDelete : ExpenseEditorAction
    data object ConfirmDelete : ExpenseEditorAction
    data object RequestBack : ExpenseEditorAction
    data object DismissDiscard : ExpenseEditorAction
    data object ConfirmDiscard : ExpenseEditorAction
    data class RestoreDraft(val snapshot: ExpenseEditorDraftSnapshot) : ExpenseEditorAction
}

sealed interface RequiredWonResult {
    data class Valid(val value: Long) : RequiredWonResult
    data object Missing : RequiredWonResult
    data object Invalid : RequiredWonResult
}

fun parseRequiredWon(input: String): RequiredWonResult {
    val normalized = input.trim().replace(",", "")
    if (normalized.isEmpty()) return RequiredWonResult.Missing
    if (normalized.any { !it.isDigit() }) return RequiredWonResult.Invalid
    return normalized.toLongOrNull()?.let(RequiredWonResult::Valid) ?: RequiredWonResult.Invalid
}

fun parseYearMonth(input: String): YearMonthKey? {
    val match = Regex("^(\\d{4})-(\\d{2})$").matchEntire(input.trim()) ?: return null
    val year = match.groupValues[1].toIntOrNull() ?: return null
    val month = match.groupValues[2].toIntOrNull() ?: return null
    return runCatching { YearMonthKey(year, month) }.getOrNull()
}

fun parseLocalDate(input: String): LocalDateKey? {
    val match = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").matchEntire(input.trim()) ?: return null
    return runCatching {
        LocalDateKey(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
        )
    }.getOrNull()
}

private data class ValidExpenseInput(
    val amount: Long,
    val category: ExpenseCategory,
    val attributionMonth: YearMonthKey,
    val actualDate: LocalDateKey?,
)

private fun validateEditor(state: ExpenseEditorUiState): Pair<ExpenseEditorErrors, ValidExpenseInput?> {
    val amount = parseRequiredWon(state.amount)
    val category = state.categories.firstOrNull { it.id == state.categoryId }
    val month = parseYearMonth(state.attributionMonth)
    val date = state.actualDate.trim().takeIf(String::isNotEmpty)?.let(::parseLocalDate)
    val errors = ExpenseEditorErrors(
        amount = when (amount) {
            RequiredWonResult.Missing -> "금액을 입력해 주세요"
            RequiredWonResult.Invalid -> "0원 이상의 금액을 숫자로 입력해 주세요"
            is RequiredWonResult.Valid -> null
        },
        category = if (category == null) "카테고리를 선택해 주세요" else null,
        attributionMonth = if (month == null) "귀속월을 YYYY-MM 형식으로 입력해 주세요" else null,
        actualDate = if (state.actualDate.isNotBlank() && date == null) "실제 지출일을 YYYY-MM-DD 형식으로 입력해 주세요" else null,
    )
    if (errors.hasAny) return errors to null
    return errors to ValidExpenseInput(
        amount = (amount as RequiredWonResult.Valid).value,
        category = requireNotNull(category),
        attributionMonth = requireNotNull(month),
        actualDate = date,
    )
}

class ExpenseEditorStateHolder(
    private val args: ExpenseEditorArgs,
    private val repository: ExpenseRepository,
    parentScope: CoroutineScope,
    private val currentDateProvider: CurrentDateProvider = CurrentDateProvider {
        LocalDateKey(args.initialMonth.year, args.initialMonth.month, 1)
    },
) {
    private val holderJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + holderJob)
    private val mutableState = MutableStateFlow(
        ExpenseEditorUiState(recordId = args.recordId, attributionMonth = args.initialMonth.toString()),
    )
    val state: StateFlow<ExpenseEditorUiState> = mutableState.asStateFlow()
    private var originalForm = mutableState.value.toEditorForm()
    private var pendingRestoredDraft: ExpenseEditorDraftSnapshot? = null
    private var loadJob: Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        load()
    }

    fun close() {
        holderJob.cancel()
    }

    fun onAction(action: ExpenseEditorAction) {
        when (action) {
            is ExpenseEditorAction.AmountChanged -> edit { copy(amount = action.value, errors = errors.copy(amount = null)) }
            is ExpenseEditorAction.CategorySelected -> edit { copy(categoryId = action.categoryId, errors = errors.copy(category = null)) }
            is ExpenseEditorAction.AttributionMonthChanged -> edit {
                copy(
                    attributionMonth = action.value,
                    suggestedActualDate = suggestedDateFor(action.value),
                    errors = errors.copy(attributionMonth = null),
                )
            }
            is ExpenseEditorAction.ActualDateChanged -> edit { copy(actualDate = action.value, errors = errors.copy(actualDate = null)) }
            ExpenseEditorAction.UseSuggestedActualDate -> mutableState.value.suggestedActualDate?.let { date ->
                edit { copy(actualDate = date.toString(), errors = errors.copy(actualDate = null)) }
            }
            is ExpenseEditorAction.DetailChanged -> edit { copy(detail = action.value) }
            is ExpenseEditorAction.OverspentChanged -> edit { copy(overspent = action.value) }
            ExpenseEditorAction.Save -> save()
            ExpenseEditorAction.RetryPersistence -> when (mutableState.value.failedOperation) {
                ExpensePersistenceOperation.Load -> load()
                ExpensePersistenceOperation.Save -> save()
                ExpensePersistenceOperation.Delete -> delete()
                null -> Unit
            }
            ExpenseEditorAction.DismissPersistenceError -> mutableState.update {
                it.copy(persistenceError = null, failedOperation = null)
            }
            ExpenseEditorAction.ResetForm -> resetForm()
            ExpenseEditorAction.RequestDelete -> if (mutableState.value.isEditing) {
                mutableState.update { it.copy(showDeleteConfirmation = true) }
            }
            ExpenseEditorAction.DismissDelete -> mutableState.update { it.copy(showDeleteConfirmation = false) }
            ExpenseEditorAction.ConfirmDelete -> delete()
            ExpenseEditorAction.RequestBack -> requestBack()
            ExpenseEditorAction.DismissDiscard -> mutableState.update { it.copy(showDiscardConfirmation = false) }
            ExpenseEditorAction.ConfirmDiscard -> mutableState.update {
                it.copy(showDiscardConfirmation = false, exitRequested = true)
            }
            is ExpenseEditorAction.RestoreDraft -> restoreDraft(action.snapshot)
        }
    }

    private fun edit(transform: ExpenseEditorUiState.() -> ExpenseEditorUiState) {
        if (!mutableState.value.isSaving) mutableState.update {
            val edited = it.transform().copy(persistenceError = null, failedOperation = null)
            edited.copy(hasUnsavedChanges = edited.toEditorForm() != originalForm)
        }
    }

    private fun load() {
        if (loadJob?.isActive == true || mutableState.value.isSaving) return
        mutableState.update { it.copy(isLoading = true, persistenceError = null, failedOperation = null) }
        loadJob = scope.launch {
            try {
                repository.prepareCategories()
                val initialSnapshot = repository.observeMonth(args.initialMonth).first()
                val existing = args.recordId?.let { repository.findRecord(it) }
                if (args.recordId != null && existing == null) {
                    mutableState.update {
                        it.copy(
                            categories = initialSnapshot.categories,
                            isLoading = false,
                            persistenceError = "지출 기록을 찾지 못했어요. 다시 시도해 주세요.",
                            failedOperation = ExpensePersistenceOperation.Load,
                        )
                    }
                    return@launch
                }
                val editorCategories = if (existing == null) {
                    initialSnapshot.categories.filterNot { it.archived }
                } else {
                    val visible = initialSnapshot.categories.filter { !it.archived || it.id == existing.categoryId }
                    if (visible.any { it.id == existing.categoryId }) visible else visible + ExpenseCategory(
                        id = existing.categoryId,
                        name = existing.categoryName,
                        displayOrder = Int.MAX_VALUE,
                        archived = true,
                        ledgerId = existing.ledgerId,
                    )
                }
                mutableState.update { current ->
                    if (existing == null) {
                        current.copy(
                            categories = editorCategories,
                            suggestedActualDate = suggestedDateFor(current.attributionMonth),
                            isLoading = false,
                        )
                    } else {
                        current.copy(
                            amount = existing.amountWon.toString(),
                            categoryId = existing.categoryId,
                            attributionMonth = existing.attributionMonth.toString(),
                            actualDate = existing.actualDate?.toString().orEmpty(),
                            detail = existing.detail.orEmpty(),
                            overspent = existing.overspent,
                            categories = editorCategories,
                            isLoading = false,
                        )
                    }
                }
                originalForm = mutableState.value.toEditorForm()
                val restored = pendingRestoredDraft
                pendingRestoredDraft = null
                if (restored == null) mutableState.update { it.copy(hasUnsavedChanges = false) }
                else restoreDraft(restored)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                mutableState.update {
                    it.copy(
                        isLoading = false,
                        persistenceError = "입력 화면을 준비하지 못했어요. 다시 시도해 주세요.",
                        failedOperation = ExpensePersistenceOperation.Load,
                    )
                }
            }
        }
    }

    private fun suggestedDateFor(attributionMonth: String): LocalDateKey? {
        val today = currentDateProvider.currentDate()
        return today.takeIf { parseYearMonth(attributionMonth) == it.yearMonth }
    }

    private fun restoreDraft(snapshot: ExpenseEditorDraftSnapshot) {
        if (mutableState.value.isLoading) {
            pendingRestoredDraft = snapshot
            return
        }
        if (mutableState.value.completed || mutableState.value.exitRequested) return
        mutableState.update {
            it.copy(
                amount = snapshot.amount,
                categoryId = snapshot.categoryId,
                attributionMonth = snapshot.attributionMonth,
                actualDate = snapshot.actualDate,
                detail = snapshot.detail,
                overspent = snapshot.overspent,
                suggestedActualDate = suggestedDateFor(snapshot.attributionMonth),
                errors = ExpenseEditorErrors(),
                persistenceError = null,
                failedOperation = null,
                hasUnsavedChanges = snapshot != originalForm.toDraftSnapshot(),
            )
        }
    }

    private fun requestBack() {
        if (mutableState.value.isSaving) return
        mutableState.update {
            if (it.hasUnsavedChanges) it.copy(showDiscardConfirmation = true)
            else it.copy(exitRequested = true)
        }
    }

    private fun resetForm() {
        if (mutableState.value.isSaving || mutableState.value.isLoading) return
        mutableState.update {
            it.copy(
                amount = originalForm.amount,
                categoryId = originalForm.categoryId,
                attributionMonth = originalForm.attributionMonth,
                actualDate = originalForm.actualDate,
                suggestedActualDate = suggestedDateFor(originalForm.attributionMonth),
                detail = originalForm.detail,
                overspent = originalForm.overspent,
                errors = ExpenseEditorErrors(),
                persistenceError = null,
                failedOperation = null,
                hasUnsavedChanges = false,
            )
        }
    }

    private fun save() {
        if (mutableState.value.isSaving || mutableState.value.isLoading || mutableState.value.errors.hasAny) return
        val (errors, valid) = validateEditor(mutableState.value)
        if (valid == null) {
            mutableState.update { it.copy(errors = errors, persistenceError = null) }
            return
        }
        val current = mutableState.value
        mutableState.update { it.copy(errors = errors, isSaving = true, persistenceError = null, failedOperation = null) }
        scope.launch {
            try {
                repository.saveRecord(
                    ExpenseRecord(
                        id = current.recordId ?: 0,
                        categoryId = valid.category.id,
                        categoryName = valid.category.name,
                        attributionMonth = valid.attributionMonth,
                        actualDate = valid.actualDate,
                        detail = current.detail.trim().ifEmpty { null },
                        amountWon = valid.amount,
                        overspent = current.overspent,
                    ),
                )
                mutableState.update { it.copy(isSaving = false, completed = true) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                mutableState.update {
                    it.copy(
                        isSaving = false,
                        persistenceError = "저장하지 못했어요. 다시 시도해 주세요.",
                        failedOperation = ExpensePersistenceOperation.Save,
                    )
                }
            }
        }
    }

    private fun delete() {
        val id = mutableState.value.recordId ?: return
        if (mutableState.value.isSaving || mutableState.value.isLoading) return
        mutableState.update {
            it.copy(showDeleteConfirmation = false, isSaving = true, persistenceError = null, failedOperation = null)
        }
        scope.launch {
            try {
                repository.deleteRecord(id)
                mutableState.update { it.copy(isSaving = false, completed = true) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                mutableState.update {
                    it.copy(
                        isSaving = false,
                        persistenceError = "삭제하지 못했어요. 다시 시도해 주세요.",
                        failedOperation = ExpensePersistenceOperation.Delete,
                    )
                }
            }
        }
    }
}

private data class EditorForm(
    val amount: String,
    val categoryId: String?,
    val attributionMonth: String,
    val actualDate: String,
    val detail: String,
    val overspent: Boolean,
)

private fun EditorForm.toDraftSnapshot() = ExpenseEditorDraftSnapshot(
    amount, categoryId, attributionMonth, actualDate, detail, overspent,
)

private fun ExpenseEditorUiState.toEditorForm() = EditorForm(
    amount = amount,
    categoryId = categoryId,
    attributionMonth = attributionMonth,
    actualDate = actualDate,
    detail = detail,
    overspent = overspent,
)
