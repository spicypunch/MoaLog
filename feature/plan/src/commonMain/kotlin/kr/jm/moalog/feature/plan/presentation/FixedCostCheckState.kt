package kr.jm.moalog.feature.plan.presentation

import kr.jm.moalog.core.database.ExistingPlanPolicy
import kr.jm.moalog.core.database.FixedCostPlanPreview
import kr.jm.moalog.core.model.FixedCostCheckItem
import kr.jm.moalog.core.model.FixedCostCheckSheet
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.core.model.sumWonOrNull
import kr.jm.moalog.feature.plan.domain.FixedCostCheckRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FixedCostCheckArgs(val month: YearMonthKey, val memberOrders: List<Int>)

data class FixedCostDraft(
    val id: Long = 0,
    val payerMemberOrder: Int? = null,
    val name: String = "",
    val amountInput: String = "",
    val displayOrder: Int = 0,
)

data class FixedCostCheckUiState(
    val month: YearMonthKey,
    val sheet: FixedCostCheckSheet = FixedCostCheckSheet(month, emptyList()),
    val selectedIds: Set<Long> = emptySet(),
    val selectionMode: Boolean = false,
    val editor: FixedCostDraft? = null,
    val conflictPreview: FixedCostPlanPreview? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
    val resultMessage: String? = null,
) {
    val selectedItems: List<FixedCostCheckItem> get() = sheet.items.filter { it.id in selectedIds }
    val selectedKnownTotalWonOrNull: Long? get() = selectedItems.mapNotNull(FixedCostCheckItem::amountWon).sumWonOrNull()
    val selectedKnownTotalWon: Long get() = selectedKnownTotalWonOrNull ?: Long.MAX_VALUE
    val hasSelectedTotalOverflow: Boolean get() = selectedKnownTotalWonOrNull == null
}

sealed interface FixedCostCheckAction {
    data object ToggleSelectionMode : FixedCostCheckAction
    data class ToggleItem(val id: Long) : FixedCostCheckAction
    data class TogglePayer(val memberOrder: Int?) : FixedCostCheckAction
    data object ToggleAll : FixedCostCheckAction
    data class OpenEditor(val item: FixedCostCheckItem? = null) : FixedCostCheckAction
    data object CloseEditor : FixedCostCheckAction
    data class ChangePayer(val memberOrder: Int?) : FixedCostCheckAction
    data class ChangeName(val value: String) : FixedCostCheckAction
    data class ChangeAmount(val value: String) : FixedCostCheckAction
    data object Save : FixedCostCheckAction
    data object Delete : FixedCostCheckAction
    data object RequestApply : FixedCostCheckAction
    data class ConfirmApply(val policy: ExistingPlanPolicy) : FixedCostCheckAction
    data object DismissConflict : FixedCostCheckAction
    data object ClearMessage : FixedCostCheckAction
}

class FixedCostCheckStateHolder(
    private val args: FixedCostCheckArgs,
    private val repository: FixedCostCheckRepository,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val mutable = MutableStateFlow(FixedCostCheckUiState(args.month))
    val state: StateFlow<FixedCostCheckUiState> = mutable
    private var observation: Job? = null
    private var initializedSelection = false

    fun start() {
        if (observation != null) return
        observation = scope.launch {
            try {
                repository.observe(args.month).collectLatest { sheet ->
                    mutable.update { current ->
                        val ids = sheet.items.mapTo(mutableSetOf()) { it.id }
                        val selection = if (!initializedSelection) ids else current.selectedIds.intersect(ids)
                        initializedSelection = true
                        current.copy(sheet = sheet, selectedIds = selection, isLoading = false)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutable.update { it.copy(isLoading = false, error = error.message ?: "고정비를 불러오지 못했어요") }
            }
        }
    }

    fun close() { scope.coroutineContext[Job]?.cancel() }

    fun onAction(action: FixedCostCheckAction) {
        when (action) {
            FixedCostCheckAction.ToggleSelectionMode -> mutable.update { state -> state.copy(selectionMode = !state.selectionMode) }
            is FixedCostCheckAction.ToggleItem -> mutable.update { state -> state.copy(selectedIds = state.selectedIds.toggle(action.id), resultMessage = null) }
            is FixedCostCheckAction.TogglePayer -> mutable.update { state ->
                val groupIds = state.sheet.items.filter { it.payerMemberOrder == action.memberOrder }.mapTo(mutableSetOf()) { it.id }
                val allSelected = groupIds.isNotEmpty() && state.selectedIds.containsAll(groupIds)
                state.copy(selectedIds = if (allSelected) state.selectedIds - groupIds else state.selectedIds + groupIds, resultMessage = null)
            }
            FixedCostCheckAction.ToggleAll -> mutable.update { state ->
                val all = state.sheet.items.mapTo(mutableSetOf()) { it.id }
                state.copy(selectedIds = if (state.selectedIds.size == all.size) emptySet() else all, resultMessage = null)
            }
            is FixedCostCheckAction.OpenEditor -> mutable.update { state ->
                val item = action.item
                state.copy(editor = if (item == null) FixedCostDraft(displayOrder = state.sheet.items.size) else FixedCostDraft(item.id, item.payerMemberOrder, item.name, item.amountWon?.toString().orEmpty(), item.displayOrder), error = null)
            }
            FixedCostCheckAction.CloseEditor -> mutable.update { it.copy(editor = null, error = null) }
            is FixedCostCheckAction.ChangePayer -> edit { it.copy(payerMemberOrder = action.memberOrder) }
            is FixedCostCheckAction.ChangeName -> edit { it.copy(name = action.value) }
            is FixedCostCheckAction.ChangeAmount -> edit { it.copy(amountInput = action.value.filter(Char::isDigit)) }
            FixedCostCheckAction.Save -> save()
            FixedCostCheckAction.Delete -> delete()
            FixedCostCheckAction.RequestApply -> preview()
            is FixedCostCheckAction.ConfirmApply -> apply(action.policy)
            FixedCostCheckAction.DismissConflict -> mutable.update { it.copy(conflictPreview = null, error = null) }
            FixedCostCheckAction.ClearMessage -> mutable.update { it.copy(resultMessage = null) }
        }
    }

    private fun edit(change: (FixedCostDraft) -> FixedCostDraft) = mutable.update { state -> state.copy(editor = state.editor?.let(change), error = null) }

    private fun save() {
        val draft = state.value.editor ?: return
        if (draft.name.isBlank()) return fail("항목명을 입력해 주세요")
        if (draft.amountInput.isBlank()) return fail("월 금액을 입력해 주세요")
        val amount = draft.amountInput.takeIf(String::isNotBlank)?.toLongOrNull()
        if (draft.amountInput.isNotBlank() && amount == null) return fail("금액이 너무 커요")
        launchSaving {
            val id = repository.save(FixedCostCheckItem(draft.id, 1, args.month, draft.payerMemberOrder, draft.name.trim(), amount, draft.displayOrder))
            mutable.update { current -> current.copy(editor = null, selectedIds = current.selectedIds + id) }
        }
    }

    private fun delete() {
        val id = state.value.editor?.id ?: return
        if (id == 0L) return
        launchSaving {
            repository.delete(id)
            mutable.update { it.copy(editor = null, selectedIds = it.selectedIds - id) }
        }
    }

    private fun preview() {
        val items = state.value.selectedItems
        if (items.isEmpty()) return fail("계획에 가져올 항목을 선택해 주세요")
        launchSaving {
            val preview = repository.preview(args.month, items)
            if (preview.conflictCount == 0) {
                val result = repository.apply(args.month, items, ExistingPlanPolicy.KeepExisting)
                mutable.update { it.copy(resultMessage = "${result.insertedItemCount}개 항목을 계획에 가져왔어요") }
            } else {
                mutable.update { it.copy(conflictPreview = preview) }
            }
        }
    }

    private fun apply(policy: ExistingPlanPolicy) {
        val items = state.value.conflictPreview?.rows?.map { it.sourceItem } ?: return
        launchSaving {
            val result = repository.apply(args.month, items, policy)
            val message = if (policy == ExistingPlanPolicy.Overwrite) {
                "${result.insertedItemCount}개 추가, ${result.overwrittenItemCount}개 항목을 업데이트했어요"
            } else {
                "${result.insertedItemCount}개 추가, ${result.skippedConflictCount}개 기존 항목을 유지했어요"
            }
            mutable.update { it.copy(conflictPreview = null, resultMessage = message) }
        }
    }

    private fun launchSaving(block: suspend () -> Unit) {
        if (!claimSaving()) return
        scope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) { fail(error.message ?: "처리하지 못했어요") }
            finally { mutable.update { it.copy(isSaving = false) } }
        }
    }

    private fun claimSaving():Boolean { while(true){val current=mutable.value;if(current.isSaving)return false;if(mutable.compareAndSet(current,current.copy(isSaving=true,error=null)))return true} }

    private fun fail(message: String) { mutable.update { it.copy(error = message) } }
}

private fun Set<Long>.toggle(value: Long): Set<Long> = if (value in this) this - value else this + value
