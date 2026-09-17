package kr.jm.moalog.feature.plan.presentation

import kr.jm.moalog.core.model.DefaultSavingsClassifications
import kr.jm.moalog.core.model.ExpenseCategory
import kr.jm.moalog.core.model.PlanCatalogItem
import kr.jm.moalog.core.model.PlanItemType
import kr.jm.moalog.feature.plan.domain.ItemManagementRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

enum class ManagedItemType { Income, FixedExpense, VariableExpense, Savings }

data class ManagedItem(
    val stableId: String,
    val catalogId: Long? = null,
    val expenseCategoryId: String? = null,
    val type: ManagedItemType,
    val name: String,
    val classification: String,
    val ownerMemberOrder: Int?,
    val includePurposeAccount: Boolean,
    val includeNetSavings: Boolean,
    val displayOrder: Int,
    val archived: Boolean,
)

data class ItemManagementDraft(
    val stableId: String? = null,
    val name: String = "",
    val classification: String = "",
    val ownerMemberOrder: Int? = null,
    val includePurposeAccount: Boolean = false,
    val includeNetSavings: Boolean = false,
)

data class ItemManagementUiState(
    val selectedType: ManagedItemType = ManagedItemType.VariableExpense,
    val showArchived: Boolean = false,
    val allItems: List<ManagedItem> = emptyList(),
    val editor: ItemManagementDraft? = null,
    val pendingArchiveId: String? = null,
    val isLoading: Boolean = true,
    val isMutating: Boolean = false,
    val error: String? = null,
    val editorError: String? = null,
    val resultAnnouncement: String? = null,
) {
    val items: List<ManagedItem> get() = allItems
        .filter { it.type == selectedType && it.archived == showArchived }
        .sortedWith(compareBy(ManagedItem::displayOrder, ManagedItem::stableId))
    val canReorder: Boolean get() = !showArchived && !isLoading && !isMutating && editor == null
    val savingsClassifications: List<String> get() = DefaultSavingsClassifications
}

sealed interface ItemManagementAction {
    data class SelectType(val type: ManagedItemType) : ItemManagementAction
    data class ShowArchived(val archived: Boolean) : ItemManagementAction
    data object Add : ItemManagementAction
    data class Edit(val stableId: String) : ItemManagementAction
    data class RestoreDraft(val draft: ItemManagementDraft) : ItemManagementAction
    data class NameChanged(val value: String) : ItemManagementAction
    data class ClassificationChanged(val value: String) : ItemManagementAction
    data class OwnerChanged(val value: Int?) : ItemManagementAction
    data class PurposeChanged(val value: Boolean) : ItemManagementAction
    data class NetSavingsChanged(val value: Boolean) : ItemManagementAction
    data object DismissEditor : ItemManagementAction
    data object Save : ItemManagementAction
    data class RequestArchive(val stableId: String) : ItemManagementAction
    data object DismissArchive : ItemManagementAction
    data object ConfirmArchive : ItemManagementAction
    data class Restore(val stableId: String) : ItemManagementAction
    data class Move(val stableId: String, val direction: Int) : ItemManagementAction
    data object Retry : ItemManagementAction
    data object ClearResultAnnouncement : ItemManagementAction
}

class ItemManagementStateHolder(
    private val repository: ItemManagementRepository,
    parentScope: CoroutineScope,
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val mutableState = MutableStateFlow(ItemManagementUiState())
    val state: StateFlow<ItemManagementUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var mutationJob: Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        load()
    }

    fun close() = job.cancel()

    fun onAction(action: ItemManagementAction) {
        when (action) {
            is ItemManagementAction.SelectType -> edit {
                if (isMutating) this else copy(selectedType = action.type, editor = null, editorError = null, pendingArchiveId = null)
            }
            is ItemManagementAction.ShowArchived -> edit {
                if (isMutating) this else copy(showArchived = action.archived, editor = null, editorError = null, pendingArchiveId = null)
            }
            ItemManagementAction.Add -> edit {
                if (isLoading || isMutating || showArchived) this
                else copy(editor = ItemManagementDraft(classification = defaultClassification(selectedType)), editorError = null)
            }
            is ItemManagementAction.Edit -> edit {
                if (isLoading || isMutating) this
                else allItems.firstOrNull { it.stableId == action.stableId && !it.archived }?.let { item ->
                    copy(editor = item.toDraft(), editorError = null)
                } ?: this
            }
            is ItemManagementAction.RestoreDraft -> edit {
                if (isMutating) this else copy(editor = action.draft, editorError = null)
            }
            is ItemManagementAction.NameChanged -> updateDraft { copy(name = action.value) }
            is ItemManagementAction.ClassificationChanged -> updateDraft { copy(classification = action.value) }
            is ItemManagementAction.OwnerChanged -> updateDraft { copy(ownerMemberOrder = action.value) }
            is ItemManagementAction.PurposeChanged -> updateDraft {
                if (mutableState.value.selectedType == ManagedItemType.Savings) copy(includePurposeAccount = action.value) else this
            }
            is ItemManagementAction.NetSavingsChanged -> updateDraft {
                if (mutableState.value.selectedType == ManagedItemType.Savings) copy(includeNetSavings = action.value) else this
            }
            ItemManagementAction.DismissEditor -> edit { if (isMutating) this else copy(editor = null, editorError = null) }
            ItemManagementAction.Save -> save()
            is ItemManagementAction.RequestArchive -> edit {
                if (isLoading || isMutating || showArchived) this
                else copy(pendingArchiveId = action.stableId)
            }
            ItemManagementAction.DismissArchive -> edit { copy(pendingArchiveId = null) }
            ItemManagementAction.ConfirmArchive -> mutableState.value.pendingArchiveId?.let { setArchived(it, true) }
            is ItemManagementAction.Restore -> setArchived(action.stableId, false)
            is ItemManagementAction.Move -> move(action.stableId, action.direction)
            ItemManagementAction.Retry -> load()
            ItemManagementAction.ClearResultAnnouncement -> edit { copy(resultAnnouncement = null) }
        }
    }

    private fun load() {
        loadJob?.cancel()
        mutableState.value = mutableState.value.copy(isLoading = true, error = null)
        loadJob = scope.launch {
            try {
                combine(repository.observePlanCatalog(), repository.observeExpenseCategories()) { plans, categories ->
                    plans.map(PlanCatalogItem::toManaged) + categories.map(ExpenseCategory::toManaged)
                }.collect { items ->
                    mutableState.value = mutableState.value.copy(allItems = items, isLoading = false, error = null)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(isLoading = false, error = "항목 목록을 불러오지 못했어요")
            }
        }
    }

    private fun save() {
        val current = mutableState.value
        val draft = current.editor ?: return
        if (current.isLoading || current.isMutating || mutationJob?.isActive == true) return
        val name = draft.name.trim()
        val classification = draft.classification.trim()
        val existing = draft.stableId?.let { id -> current.allItems.firstOrNull { it.stableId == id } }
        val keepsExistingName = existing != null && existing.name.trim().equals(name, ignoreCase = true)
        val duplicate = !keepsExistingName && current.allItems.any {
            it.type == current.selectedType && it.stableId != draft.stableId && it.name.trim().lowercase() == name.lowercase()
        }
        val validation = when {
            name.isEmpty() -> "항목 이름을 입력해 주세요"
            duplicate -> "같은 이름의 항목이 이미 있어요"
            current.selectedType != ManagedItemType.VariableExpense && classification.isEmpty() -> "분류를 입력해 주세요"
            else -> null
        }
        if (validation != null) {
            mutableState.value = current.copy(editorError = validation)
            return
        }
        mutableState.value = current.copy(isMutating = true, editorError = null, error = null)
        mutationJob = scope.launch {
            try {
                if (current.selectedType == ManagedItemType.VariableExpense) {
                    val id = draft.stableId?.removePrefix("expense:")
                    if (id == null) repository.createExpenseCategory(name)
                    else repository.renameExpenseCategory(id, name)
                } else {
                    repository.savePlanItem(
                        PlanCatalogItem(
                            id = existing?.catalogId ?: 0,
                            type = current.selectedType.toPlanType(),
                            classification = classification,
                            name = name,
                            ownerMemberOrder = draft.ownerMemberOrder,
                            includePurposeAccount = current.selectedType == ManagedItemType.Savings && draft.includePurposeAccount,
                            includeNetSavings = current.selectedType == ManagedItemType.Savings && draft.includeNetSavings,
                            displayOrder = existing?.displayOrder ?: 0,
                            archived = false,
                        ),
                    )
                }
                mutableState.value = mutableState.value.copy(
                    isMutating = false,
                    editor = null,
                    resultAnnouncement = if (draft.stableId == null) "항목을 추가했어요" else "항목을 저장했어요",
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(isMutating = false, error = "항목을 저장하지 못했어요")
            }
        }
    }

    private fun setArchived(stableId: String, archived: Boolean) {
        val current = mutableState.value
        if (current.isLoading || current.isMutating || mutationJob?.isActive == true) return
        val item = current.allItems.firstOrNull { it.stableId == stableId } ?: return
        mutableState.value = current.copy(isMutating = true, pendingArchiveId = null, error = null)
        mutationJob = scope.launch {
            try {
                item.catalogId?.let { repository.setPlanItemArchived(it, archived) }
                    ?: repository.setExpenseCategoryArchived(requireNotNull(item.expenseCategoryId), archived)
                mutableState.value = mutableState.value.copy(
                    isMutating = false,
                    resultAnnouncement = if (archived) "항목을 보관했어요. 기존 기록은 유지돼요" else "항목을 복원했어요",
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(isMutating = false, error = if (archived) "항목을 보관하지 못했어요" else "항목을 복원하지 못했어요")
            }
        }
    }

    private fun move(stableId: String, direction: Int) {
        val current = mutableState.value
        if (!current.canReorder || direction !in setOf(-1, 1) || mutationJob?.isActive == true) return
        val items = current.items.toMutableList()
        val from = items.indexOfFirst { it.stableId == stableId }
        val to = from + direction
        if (from < 0 || to !in items.indices) return
        val moved = items.removeAt(from)
        items.add(to, moved)
        mutableState.value = current.copy(isMutating = true, error = null)
        mutationJob = scope.launch {
            try {
                if (current.selectedType == ManagedItemType.VariableExpense) {
                    repository.reorderExpenseCategories(items.map { requireNotNull(it.expenseCategoryId) })
                } else {
                    repository.reorderPlanItems(items.map { requireNotNull(it.catalogId) })
                }
                mutableState.value = mutableState.value.copy(isMutating = false, resultAnnouncement = "항목 순서를 변경했어요")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(isMutating = false, error = "항목 순서를 변경하지 못했어요")
            }
        }
    }

    private fun updateDraft(block: ItemManagementDraft.() -> ItemManagementDraft) = edit {
        if (isMutating) this else copy(editor = editor?.block(), editorError = null)
    }

    private inline fun edit(block: ItemManagementUiState.() -> ItemManagementUiState) {
        mutableState.value = mutableState.value.block()
    }
}

private fun defaultClassification(type: ManagedItemType): String = when (type) {
    ManagedItemType.Income -> "급여"
    ManagedItemType.FixedExpense -> "고정비"
    ManagedItemType.VariableExpense -> ""
    ManagedItemType.Savings -> DefaultSavingsClassifications.first()
}

private fun ManagedItemType.toPlanType() = when (this) {
    ManagedItemType.Income -> PlanItemType.Income
    ManagedItemType.FixedExpense -> PlanItemType.FixedExpense
    ManagedItemType.Savings -> PlanItemType.Savings
    ManagedItemType.VariableExpense -> error("Variable expenses use categories")
}

private fun PlanCatalogItem.toManaged() = ManagedItem(
    stableId = "plan:$id",
    catalogId = id,
    type = when (type) {
        PlanItemType.Income -> ManagedItemType.Income
        PlanItemType.FixedExpense -> ManagedItemType.FixedExpense
        PlanItemType.Savings -> ManagedItemType.Savings
    },
    name = name,
    classification = classification,
    ownerMemberOrder = ownerMemberOrder,
    includePurposeAccount = includePurposeAccount,
    includeNetSavings = includeNetSavings,
    displayOrder = displayOrder,
    archived = archived,
)

private fun ExpenseCategory.toManaged() = ManagedItem(
    stableId = "expense:$id",
    expenseCategoryId = id,
    type = ManagedItemType.VariableExpense,
    name = name,
    classification = "변동지출",
    ownerMemberOrder = null,
    includePurposeAccount = false,
    includeNetSavings = false,
    displayOrder = displayOrder,
    archived = archived,
)

private fun ManagedItem.toDraft() = ItemManagementDraft(
    stableId = stableId,
    name = name,
    classification = classification,
    ownerMemberOrder = ownerMemberOrder,
    includePurposeAccount = includePurposeAccount,
    includeNetSavings = includeNetSavings,
)
