package kr.jm.moalog.feature.maintenance.presentation

import kr.jm.moalog.core.model.MaintenanceFeeGroup
import kr.jm.moalog.core.model.MaintenanceFeeItemKey
import kr.jm.moalog.core.model.MaintenanceFeeMonth
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.maintenance.domain.MaintenanceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class MaintenanceEditorArgs(val month: YearMonthKey)

data class MaintenanceEditorField(
    val key: MaintenanceFeeItemKey,
    val label: String,
    val group: MaintenanceFeeGroup,
    val text: String,
    val error: String? = null,
)

data class MaintenanceEditorUiState(
    val month: YearMonthKey,
    val fields: List<MaintenanceEditorField> = emptyEditorFields(),
    val calculatedTotalWon: Long? = null,
    val enteredCount: Int = 0,
    val totalError: String? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val loadError: String? = null,
    val persistenceError: String? = null,
    val completed: Boolean = false,
)

sealed interface MaintenanceEditorAction {
    data class AmountChanged(val key: MaintenanceFeeItemKey, val value: String) : MaintenanceEditorAction
    data class MonthChanged(val month: YearMonthKey) : MaintenanceEditorAction
    data object Save : MaintenanceEditorAction
    data object Retry : MaintenanceEditorAction
}

data class ValidMaintenanceEditorInput(
    val month: YearMonthKey,
    val amounts: Map<MaintenanceFeeItemKey, Long?>,
)

fun validateMaintenanceEditor(
    month: YearMonthKey,
    fields: List<MaintenanceEditorField>,
): Triple<List<MaintenanceEditorField>, Long?, ValidMaintenanceEditorInput?> {
    require(fields.map { it.key } == MaintenanceFeeItemKey.entries) { "Editor fields must use canonical order" }
    val parsed = linkedMapOf<MaintenanceFeeItemKey, Long?>()
    var hasError = false
    val validated = fields.map { field ->
        val normalized = field.text.trim().replace(",", "")
        val value = normalized.takeIf { it.isNotEmpty() }?.toLongOrNull()
        val error = when {
            normalized.isEmpty() -> null
            value == null -> "금액을 숫자로 입력해 주세요"
            value < 0 && !field.key.acceptsNegative -> "0원 이상의 금액을 입력해 주세요"
            else -> null
        }
        if (error != null) hasError = true
        parsed[field.key] = if (error == null) value else null
        field.copy(error = error)
    }
    val total = if (hasError) null else runCatching {
        MaintenanceFeeMonth.fromAmounts(month, parsed).totalWon
    }.getOrNull()
    val totalOverflow = !hasError && parsed.values.any { it != null } && total == null
    return Triple(
        validated,
        total,
        if (!hasError && !totalOverflow) ValidMaintenanceEditorInput(month, parsed) else null,
    )
}

class MaintenanceEditorStateHolder(
    args: MaintenanceEditorArgs,
    private val repository: MaintenanceRepository,
    parentScope: CoroutineScope,
) {
    private val holderJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + holderJob)
    private val mutableState = MutableStateFlow(MaintenanceEditorUiState(args.month))
    val state: StateFlow<MaintenanceEditorUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null

    init {
        load(args.month)
    }

    fun close() = holderJob.cancel()

    fun onAction(action: MaintenanceEditorAction) {
        when (action) {
            is MaintenanceEditorAction.AmountChanged -> updateAmount(action.key, action.value)
            is MaintenanceEditorAction.MonthChanged -> load(action.month)
            MaintenanceEditorAction.Save -> save()
            MaintenanceEditorAction.Retry -> load(mutableState.value.month)
        }
    }

    private fun updateAmount(key: MaintenanceFeeItemKey, value: String) {
        val fields = mutableState.value.fields.map { field ->
            if (field.key == key) field.copy(text = value) else field
        }
        val (validated, total, valid) = validateMaintenanceEditor(mutableState.value.month, fields)
        mutableState.value = mutableState.value.copy(
            fields = validated,
            calculatedTotalWon = total,
            enteredCount = valid?.amounts?.values?.count { it != null } ?: fields.count { it.text.isNotBlank() },
            totalError = if (validated.none { it.error != null } && valid == null) "입력 합계가 너무 커요" else null,
            persistenceError = null,
            completed = false,
        )
    }

    private fun load(month: YearMonthKey) {
        loadJob?.cancel()
        mutableState.value = MaintenanceEditorUiState(month = month)
        loadJob = scope.launch {
            try {
                val saved = repository.observeMonth(month).first()
                val fields = saved.entries.map {
                    MaintenanceEditorField(it.key, it.key.label, it.key.group, it.amountWon?.toString().orEmpty())
                }
                mutableState.value = MaintenanceEditorUiState(
                    month = month,
                    fields = fields,
                    calculatedTotalWon = saved.totalWon,
                    enteredCount = saved.enteredCount,
                    isLoading = false,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    loadError = "관리비 입력을 준비하지 못했어요. 다시 시도해 주세요",
                )
            }
        }
    }

    private fun save() {
        val current = mutableState.value
        if (current.isLoading || current.isSaving) return
        val (validated, total, valid) = validateMaintenanceEditor(current.month, current.fields)
        if (valid == null) {
            mutableState.value = current.copy(
                fields = validated,
                calculatedTotalWon = total,
                totalError = if (validated.none { it.error != null }) "입력 합계가 너무 커요" else null,
            )
            return
        }
        mutableState.value = current.copy(
            fields = validated,
            calculatedTotalWon = total,
            isSaving = true,
            persistenceError = null,
        )
        scope.launch {
            try {
                repository.saveMonth(MaintenanceFeeMonth.fromAmounts(valid.month, valid.amounts))
                mutableState.value = mutableState.value.copy(isSaving = false, completed = true)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(
                    isSaving = false,
                    persistenceError = "관리비를 저장하지 못했어요. 다시 시도해 주세요",
                )
            }
        }
    }
}

private fun emptyEditorFields(): List<MaintenanceEditorField> = MaintenanceFeeItemKey.entries.map {
    MaintenanceEditorField(it, it.label, it.group, "")
}
