package kr.jm.moalog.feature.records.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kr.jm.moalog.core.designsystem.MoaLogColors
import kr.jm.moalog.core.model.ExpenseCategory
import kr.jm.moalog.core.model.LocalDateKey
import kr.jm.moalog.core.model.YearMonthKey
import org.koin.compose.getKoin
import org.koin.core.parameter.parametersOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

internal class ExpenseEditorAndroidViewModel(
    val stateHolder: ExpenseEditorStateHolder,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private var pending = ExpenseEditorSavedStateCodec.read(savedStateHandle)

    init {
        stateHolder.start()
        pending?.let { stateHolder.onAction(ExpenseEditorAction.RestoreDraft(it)) }
        viewModelScope.launch { stateHolder.state.collect(::saveState) }
    }

    private fun saveState(state: ExpenseEditorUiState) {
        pending?.let { restored ->
            if (state.isLoading || state.toDraftSnapshot() != restored) return
            pending = null
        }
        when {
            state.completed || state.exitRequested -> ExpenseEditorSavedStateCodec.clear(savedStateHandle)
            state.hasUnsavedChanges || state.errors.hasAny || state.showDiscardConfirmation ->
                ExpenseEditorSavedStateCodec.write(savedStateHandle, state.toDraftSnapshot())
            else -> ExpenseEditorSavedStateCodec.clear(savedStateHandle)
        }
    }

    override fun onCleared() = stateHolder.close()
}

internal object ExpenseEditorSavedStateCodec {
    private const val PRESENT = "expense_editor.draft.present"
    private const val AMOUNT = "expense_editor.draft.amount"
    private const val CATEGORY = "expense_editor.draft.category"
    private const val MONTH = "expense_editor.draft.month"
    private const val DATE = "expense_editor.draft.date"
    private const val DETAIL = "expense_editor.draft.detail"
    private const val OVERSPENT = "expense_editor.draft.overspent"

    fun write(handle: SavedStateHandle, snapshot: ExpenseEditorDraftSnapshot) {
        handle[PRESENT] = true
        handle[AMOUNT] = snapshot.amount
        handle[CATEGORY] = snapshot.categoryId
        handle[MONTH] = snapshot.attributionMonth
        handle[DATE] = snapshot.actualDate
        handle[DETAIL] = snapshot.detail
        handle[OVERSPENT] = snapshot.overspent
    }

    fun read(handle: SavedStateHandle): ExpenseEditorDraftSnapshot? {
        if (handle.get<Boolean>(PRESENT) != true) return null
        return ExpenseEditorDraftSnapshot(
            amount = handle[AMOUNT] ?: return null,
            categoryId = handle[CATEGORY],
            attributionMonth = handle[MONTH] ?: return null,
            actualDate = handle[DATE] ?: return null,
            detail = handle[DETAIL] ?: return null,
            overspent = handle[OVERSPENT] ?: false,
        )
    }

    fun clear(handle: SavedStateHandle) { handle.remove<Boolean>(PRESENT) }
}

@Composable
fun ExpenseEditorRoute(
    args: ExpenseEditorArgs,
    onFinished: (YearMonthKey) -> Unit,
    onBack: () -> Unit,
    registerSystemBackRequest: ((() -> Unit)?) -> Unit,
) {
    val koin = getKoin()
    val androidViewModel = viewModel<ExpenseEditorAndroidViewModel>(
        key = "expense-editor-${args.recordId ?: "new"}-${args.initialMonth}",
        factory = remember(args, koin) {
            viewModelFactory {
                initializer {
                    ExpenseEditorAndroidViewModel(
                        stateHolder = koin.get { parametersOf(args) },
                        savedStateHandle = createSavedStateHandle(),
                    )
                }
            }
        },
    )
    val stateHolder = androidViewModel.stateHolder
    val state by stateHolder.state.collectAsStateWithLifecycle()
    DisposableEffect(stateHolder) {
        registerSystemBackRequest { stateHolder.onAction(ExpenseEditorAction.RequestBack) }
        onDispose { registerSystemBackRequest(null) }
    }
    LaunchedEffect(state.completed) {
        if (state.completed) onFinished(parseYearMonth(state.attributionMonth) ?: args.initialMonth)
    }
    LaunchedEffect(state.exitRequested) { if (state.exitRequested) onBack() }
    ExpenseEditorScreen(state, stateHolder::onAction)
}

@Composable
fun ExpenseEditorScreen(state: ExpenseEditorUiState, onAction: (ExpenseEditorAction) -> Unit, modifier: Modifier = Modifier) {
    var showMonthPicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val firstErrorOffset = when {
        state.errors.amount != null -> 0
        state.errors.category != null -> with(density) { 240.dp.roundToPx() }
        state.errors.attributionMonth != null -> with(density) { 480.dp.roundToPx() }
        state.errors.actualDate != null -> with(density) { 600.dp.roundToPx() }
        else -> 0
    }
    LaunchedEffect(state.errors) {
        if (state.errors.hasAny) scrollState.animateScrollTo(firstErrorOffset)
    }
    Box(modifier.fillMaxSize().background(MoaLogColors.Canvas).imePadding()) {
        if (state.isLoading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onAction(ExpenseEditorAction.RequestBack) }, modifier = Modifier.size(48.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Icon(Icons.Default.ArrowBackIosNew, contentDescription = "기록으로 돌아가기", tint = MoaLogColors.MutedInk, modifier = Modifier.size(20.dp))
                    }
                    Text(
                        if (state.isEditing) "지출 수정" else "지출 입력",
                        Modifier.weight(1f),
                        color = MoaLogColors.TealInk,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = { onAction(ExpenseEditorAction.RequestBack) }, modifier = Modifier.height(48.dp)) {
                        Text("취소", color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelMedium)
                    }
                }

                if (state.errors.hasAny) {
                    ValidationErrorBanner(
                        errorCount = state.errors.count,
                        onMoveToFirstError = { coroutineScope.launch { scrollState.animateScrollTo(firstErrorOffset) } },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                state.persistenceError?.let { message ->
                    PersistenceErrorBanner(
                        message = message,
                        operation = state.failedOperation,
                        onRetry = { onAction(ExpenseEditorAction.RetryPersistence) },
                        onLater = { onAction(ExpenseEditorAction.DismissPersistenceError) },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                AmountCard(state, onAction, Modifier.padding(top = 12.dp))
                CategorySection(state, onAction, Modifier.padding(top = 24.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp).border(1.dp, MoaLogColors.CardBorder, RoundedCornerShape(12.dp)),
                    color = Color.White,
                    shape = RoundedCornerShape(12.dp),
                    shadowElevation = 1.dp,
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SelectorField(
                            label = "귀속 월 *",
                            value = parseYearMonth(state.attributionMonth)?.let(::formatEditorMonth).orEmpty(),
                            errorMessage = state.errors.attributionMonth,
                            placeholder = "YYYY-MM",
                            icon = Icons.Default.CalendarMonth,
                            supportingText = "이 지출을 합산할 달이에요",
                            onClick = { showMonthPicker = true },
                        )
                        HorizontalDivider(color = MoaLogColors.Linen)
                        SelectorField(
                            label = "실제 지출일 (선택)",
                            value = parseLocalDate(state.actualDate)?.let(::formatEditorDate).orEmpty(),
                            errorMessage = state.errors.actualDate,
                            placeholder = "YYYY-MM-DD",
                            icon = Icons.Default.DateRange,
                            onClick = { showDatePicker = true },
                        )
                        val suggestedDate = state.suggestedActualDate
                        if (state.actualDate.isBlank() && suggestedDate != null) {
                            TextButton(
                                onClick = { onAction(ExpenseEditorAction.UseSuggestedActualDate) },
                                modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
                            ) {
                                Text("오늘 ${formatEditorDate(suggestedDate)} 사용", color = MoaLogColors.DeepTeal)
                            }
                        }
                    }
                }

                val month = parseYearMonth(state.attributionMonth)
                val date = parseLocalDate(state.actualDate)
                if (month != null && date != null && month != date.yearMonth) {
                    Text("실제 지출일과 달라도 ${month.year}년 ${month.month}월 합계에 포함돼요", Modifier.padding(top = 8.dp, start = 4.dp), color = MoaLogColors.DeepTeal, style = MaterialTheme.typography.labelSmall)
                }

                StyledEditorField(
                    label = "세부 내용 (선택)",
                    value = state.detail,
                    errorMessage = null,
                    placeholder = "예: 마트 장보기 초과분, 생필품 대량 구매",
                    modifier = Modifier.padding(top = 24.dp),
                ) { onAction(ExpenseEditorAction.DetailChanged(it)) }

                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp).toggleable(value = state.overspent, role = Role.Switch, onValueChange = { onAction(ExpenseEditorAction.OverspentChanged(it)) }).border(1.dp, Color(0xFFF9D2D5), RoundedCornerShape(12.dp)),
                    color = MoaLogColors.OverspendSurface.copy(alpha = .7f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(26.dp).background(Color(0xFFFFDAD6), CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.Warning, contentDescription = null, tint = MoaLogColors.Overspend, modifier = Modifier.size(16.dp)) }
                            Text("과소비로 표시하기", Modifier.weight(1f).padding(start = 8.dp), color = MoaLogColors.Overspend, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Switch(
                                checked = state.overspent,
                                onCheckedChange = null,
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = MoaLogColors.Overspend),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = MoaLogColors.Overspend, modifier = Modifier.size(16.dp))
                            Text(
                                "켜면 이 거래 금액 전체가 과소비 합계에 포함돼요.",
                                color = MoaLogColors.OverspendInk,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(if (state.isEditing) 126.dp else 92.dp))
            }

            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                color = Color.White.copy(alpha = .97f),
                shadowElevation = 7.dp,
            ) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(
                        onClick = { onAction(ExpenseEditorAction.Save) },
                        enabled = state.canSave,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MoaLogColors.DeepTeal),
                    ) {
                        if (state.isSaving) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp).padding(end = 6.dp))
                        if (!state.isSaving) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text(if (state.isSaving) "저장 중" else "저장하기", Modifier.padding(start = if (state.isSaving) 0.dp else 6.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                    if (state.isEditing) {
                        TextButton(onClick = { onAction(ExpenseEditorAction.RequestDelete) }, enabled = !state.isSaving, modifier = Modifier.height(40.dp)) {
                            Text("지출 삭제", color = MoaLogColors.Overspend, style = MaterialTheme.typography.labelMedium)
                        }
                    } else {
                        TextButton(
                            onClick = { onAction(ExpenseEditorAction.ResetForm) },
                            enabled = !state.isSaving && state.hasUnsavedChanges,
                            modifier = Modifier.height(40.dp),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("입력 초기화", Modifier.padding(start = 4.dp), color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }

    if (state.showDeleteConfirmation) DeleteDialog(onAction)
    if (state.showDiscardConfirmation) DiscardDialog(onAction)
    if (showMonthPicker) {
        MonthPickerDialog(
            current = parseYearMonth(state.attributionMonth),
            onDismiss = { showMonthPicker = false },
            onSelected = { onAction(ExpenseEditorAction.AttributionMonthChanged(it.toString())); showMonthPicker = false },
        )
    }
    if (showDatePicker) {
        DateSelectorDialog(
            current = parseLocalDate(state.actualDate),
            fallbackMonth = parseYearMonth(state.attributionMonth),
            onDismiss = { showDatePicker = false },
            onSelected = { date -> onAction(ExpenseEditorAction.ActualDateChanged(date?.toString().orEmpty())); showDatePicker = false },
        )
    }
}

@Composable
private fun AmountCard(state: ExpenseEditorUiState, onAction: (ExpenseEditorAction) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth().border(1.dp, MoaLogColors.CardBorder, RoundedCornerShape(12.dp)), color = Color.White, shape = RoundedCornerShape(12.dp), shadowElevation = 1.dp) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("금액", color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelMedium)
            }
            OutlinedTextField(
                value = formatAmountInput(state.amount),
                onValueChange = { onAction(ExpenseEditorAction.AmountChanged(normalizeAmountInput(it))) },
                modifier = Modifier.fillMaxWidth().height(if (state.errors.amount == null) 68.dp else 88.dp).semantics { contentDescription = "금액" }
                    .then(if (state.errors.amount == null) Modifier else Modifier.semantics { error(state.errors.amount.orEmpty()) }),
                suffix = { Text("원", color = MoaLogColors.MutedInk, style = MaterialTheme.typography.headlineMedium) },
                placeholder = { Text("0", color = MoaLogColors.Outline, style = MaterialTheme.typography.displaySmall) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                isError = state.errors.amount != null,
                supportingText = state.errors.amount?.let { message -> ({ Text(message) }) },
                textStyle = MaterialTheme.typography.displaySmall.copy(color = MoaLogColors.TealInk, fontWeight = FontWeight.ExtraBold),
                shape = RoundedCornerShape(0.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, errorBorderColor = Color.Transparent, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
            )
            HorizontalDivider(color = MoaLogColors.Linen)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10_000L to "+1만", 50_000L to "+5만", 100_000L to "+10만", 500_000L to "+50만").forEach { (amount, label) ->
                    OutlinedButton(
                        onClick = {
                            val current = (parseRequiredWon(state.amount) as? RequiredWonResult.Valid)?.value ?: 0L
                            if (current <= Long.MAX_VALUE - amount) {
                                onAction(ExpenseEditorAction.AmountChanged((current + amount).toString()))
                            }
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MoaLogColors.CardBorder),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) { Text(label, color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

@Composable
private fun CategorySection(state: ExpenseEditorUiState, onAction: (ExpenseEditorAction) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("카테고리 *", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("필수 선택", color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelSmall)
        }
        state.categories.chunked(3).forEach { categories ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                categories.forEach { category -> CategoryChoice(category, state.categoryId == category.id, Modifier.weight(1f)) { onAction(ExpenseEditorAction.CategorySelected(category.id)) } }
                repeat(3 - categories.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        state.errors.category?.let { ErrorText(it) }
    }
}

@Composable
private fun CategoryChoice(category: ExpenseCategory, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        color = if (selected) MoaLogColors.DeepTeal else Color.White,
        contentColor = if (selected) Color.White else MoaLogColors.MutedInk,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) MoaLogColors.DeepTeal else MoaLogColors.CardBorder),
        shadowElevation = if (selected) 2.dp else 0.dp,
    ) { Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(categoryIcon(category.name), contentDescription = null, modifier = Modifier.size(17.dp))
        Text(category.name, Modifier.padding(start = 5.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
    } }
}

@Composable
private fun StyledEditorField(
    label: String,
    value: String,
    errorMessage: String?,
    placeholder: String,
    modifier: Modifier = Modifier,
    leading: String? = null,
    trailing: String? = null,
    supportingText: String? = null,
    onValueChange: (String) -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }.then(if (errorMessage == null) Modifier else Modifier.semantics { error(errorMessage) }),
            placeholder = { Text(placeholder, color = MoaLogColors.Outline) },
            leadingIcon = leading?.let { glyph -> ({ Text(glyph, color = MoaLogColors.DeepTeal) }) },
            trailingIcon = trailing?.let { glyph -> ({ Text(glyph, color = MoaLogColors.MutedInk) }) },
            singleLine = true,
            isError = errorMessage != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            supportingText = errorMessage?.let { message -> ({ Text(message) }) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MoaLogColors.DeepTeal, unfocusedBorderColor = MoaLogColors.CardBorder, focusedContainerColor = MoaLogColors.Canvas, unfocusedContainerColor = MoaLogColors.Canvas),
        )
        if (supportingText != null && errorMessage == null) Text(supportingText, Modifier.padding(start = 4.dp), color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SelectorField(
    label: String,
    value: String,
    errorMessage: String?,
    placeholder: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    supportingText: String? = null,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Surface(
            modifier = Modifier.fillMaxWidth().height(52.dp).clickable(onClickLabel = "$label 선택", role = Role.Button, onClick = onClick)
                .then(if (errorMessage == null) Modifier else Modifier.semantics { error(errorMessage) }),
            color = MoaLogColors.Canvas,
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (errorMessage == null) MoaLogColors.CardBorder else MoaLogColors.Overspend),
        ) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MoaLogColors.DeepTeal, modifier = Modifier.size(20.dp))
                Text(value.ifBlank { placeholder }, Modifier.weight(1f).padding(start = 10.dp), color = if (value.isBlank()) MoaLogColors.Outline else MoaLogColors.Ink, style = MaterialTheme.typography.bodyLarge, fontWeight = if (value.isBlank()) FontWeight.Normal else FontWeight.SemiBold)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MoaLogColors.MutedInk, modifier = Modifier.size(20.dp))
            }
        }
        when {
            errorMessage != null -> ErrorText(errorMessage)
            supportingText != null -> Text(supportingText, Modifier.padding(start = 4.dp), color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MonthPickerDialog(current: YearMonthKey?, onDismiss: () -> Unit, onSelected: (YearMonthKey) -> Unit) {
    var year by remember(current) { mutableStateOf(current?.year ?: 2026) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = Color.White, shape = RoundedCornerShape(16.dp), shadowElevation = 8.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (year > YearMonthKey.MIN_YEAR) year-- }) { Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "이전 연도") }
                    Text("${year}년", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    IconButton(onClick = { if (year < YearMonthKey.MAX_YEAR) year++ }) { Icon(Icons.Default.KeyboardArrowRight, contentDescription = "다음 연도") }
                }
                (1..12).chunked(3).forEach { months ->
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        months.forEach { month ->
                            val selected = current?.year == year && current.month == month
                            Surface(
                                modifier = Modifier.weight(1f).height(48.dp).selectable(selected = selected, role = Role.RadioButton) { onSelected(YearMonthKey(year, month)) },
                                color = if (selected) MoaLogColors.DeepTeal else MoaLogColors.Linen,
                                contentColor = if (selected) Color.White else MoaLogColors.Ink,
                                shape = RoundedCornerShape(12.dp),
                            ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("${month}월", style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) } }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).height(48.dp)) { Text("취소", color = MoaLogColors.MutedInk) }
            }
        }
    }
}

@Composable
private fun DateSelectorDialog(
    current: LocalDateKey?,
    fallbackMonth: YearMonthKey?,
    onDismiss: () -> Unit,
    onSelected: (LocalDateKey?) -> Unit,
) {
    var year by remember(current, fallbackMonth) { mutableStateOf(current?.year ?: fallbackMonth?.year ?: 2026) }
    var month by remember(current, fallbackMonth) { mutableStateOf(current?.month ?: fallbackMonth?.month ?: 1) }
    fun previousMonth() {
        if (year == YearMonthKey.MIN_YEAR && month == 1) return
        if (month == 1) { month = 12; year-- } else month--
    }
    fun nextMonth() {
        if (year == YearMonthKey.MAX_YEAR && month == 12) return
        if (month == 12) { month = 1; year++ } else month++
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = Color.White, shape = RoundedCornerShape(16.dp), shadowElevation = 8.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = ::previousMonth) { Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "이전 달") }
                    Text("${year}년 ${month}월", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    IconButton(onClick = ::nextMonth) { Icon(Icons.Default.KeyboardArrowRight, contentDescription = "다음 달") }
                }
                (1..daysInMonth(year, month)).chunked(7).forEach { days ->
                    Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        days.forEach { day ->
                            val selected = current?.year == year && current.month == month && current.day == day
                            Surface(
                                modifier = Modifier.weight(1f).height(48.dp).selectable(selected = selected, role = Role.RadioButton) { onSelected(LocalDateKey(year, month, day)) },
                                color = if (selected) MoaLogColors.DeepTeal else Color.Transparent,
                                contentColor = if (selected) Color.White else MoaLogColors.Ink,
                                shape = CircleShape,
                            ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(day.toString(), style = MaterialTheme.typography.labelMedium) } }
                        }
                        repeat(7 - days.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onSelected(null) }, modifier = Modifier.height(48.dp)) { Text("날짜 지우기", color = MoaLogColors.MutedInk) }
                    TextButton(onClick = onDismiss, modifier = Modifier.height(48.dp)) { Text("취소", color = MoaLogColors.MutedInk) }
                }
            }
        }
    }
}

private fun daysInMonth(year: Int, month: Int): Int = (31 downTo 28).first { day ->
    runCatching { LocalDateKey(year, month, day) }.isSuccess
}

private fun formatEditorMonth(month: YearMonthKey): String = "${month.year}년 ${month.month}월"

private fun formatEditorDate(date: LocalDateKey): String =
    "${date.year}년 ${date.month}월 ${date.day}일 (${koreanWeekday(date).take(1)})"

@Composable
private fun DeleteDialog(onAction: (ExpenseEditorAction) -> Unit) {
    Dialog(onDismissRequest = { onAction(ExpenseEditorAction.DismissDelete) }) {
        Surface(color = Color.White, shape = RoundedCornerShape(12.dp), shadowElevation = 8.dp) {
            Column(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(48.dp).background(MoaLogColors.OverspendSurface, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.Delete, contentDescription = null, tint = MoaLogColors.Overspend) }
                    Text("이 지출을 삭제할까요?", style = MaterialTheme.typography.headlineMedium)
                    Text("삭제하면 월별 합계에도 반영돼요.", color = MoaLogColors.MutedInk, style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider(color = MoaLogColors.CardBorder)
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { onAction(ExpenseEditorAction.DismissDelete) }, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(bottomStart = 12.dp)) { Text("취소", color = MoaLogColors.MutedInk) }
                    Box(Modifier.width(1.dp).height(52.dp).background(MoaLogColors.CardBorder))
                    TextButton(onClick = { onAction(ExpenseEditorAction.ConfirmDelete) }, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(bottomEnd = 12.dp)) { Text("삭제", color = MoaLogColors.Overspend, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
private fun DiscardDialog(onAction: (ExpenseEditorAction) -> Unit) {
    Dialog(onDismissRequest = { onAction(ExpenseEditorAction.DismissDiscard) }) {
        Surface(color = Color.White, shape = RoundedCornerShape(16.dp), shadowElevation = 8.dp) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("저장하지 않은 변경 사항이 있어요", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    Text("지금 나가면 입력한 내용이 사라져요.", color = MoaLogColors.MutedInk, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onAction(ExpenseEditorAction.DismissDiscard) }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = MoaLogColors.DeepTeal)) { Text("계속 작성") }
                    OutlinedButton(onClick = { onAction(ExpenseEditorAction.ConfirmDiscard) }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, MoaLogColors.CardBorder)) { Text("나가기", color = MoaLogColors.Ink) }
                }
            }
        }
    }
}

@Composable
private fun ErrorText(message: String, modifier: Modifier = Modifier) {
    Text(message, modifier.semantics { error(message) }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ValidationErrorBanner(
    errorCount: Int,
    onMoveToFirstError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().semantics {
            liveRegion = LiveRegionMode.Assertive
            error("필수 항목 ${errorCount}개가 누락되었거나 올바르지 않습니다")
        },
        color = MoaLogColors.OverspendSurface,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF3B7BA)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MoaLogColors.Overspend, modifier = Modifier.size(18.dp))
                Text("입력 오류가 발생했습니다", Modifier.padding(start = 6.dp), color = MoaLogColors.OverspendInk, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
            Text("필수 항목 ${errorCount}개를 확인해 주세요.", color = MoaLogColors.OverspendInk, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onMoveToFirstError, modifier = Modifier.height(48.dp)) {
                Text("첫 번째 오류로 이동", color = MoaLogColors.Overspend, style = MaterialTheme.typography.labelMedium)
                Icon(Icons.Default.ArrowDownward, contentDescription = null, tint = MoaLogColors.Overspend, modifier = Modifier.padding(start = 4.dp).size(16.dp))
            }
        }
    }
}

@Composable
private fun PersistenceErrorBanner(
    message: String,
    operation: ExpensePersistenceOperation?,
    onRetry: () -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().semantics {
            liveRegion = LiveRegionMode.Assertive
            error(message)
        },
        color = MoaLogColors.OverspendSurface,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF3B7BA)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MoaLogColors.Overspend, modifier = Modifier.size(18.dp))
                Text(message, Modifier.padding(start = 6.dp), color = MoaLogColors.OverspendInk, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            if (operation != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MoaLogColors.DeepTeal),
                    ) {
                        Text(
                            when (operation) {
                                ExpensePersistenceOperation.Load -> "다시 불러오기"
                                ExpensePersistenceOperation.Save -> "다시 저장"
                                ExpensePersistenceOperation.Delete -> "다시 삭제"
                            },
                        )
                    }
                    OutlinedButton(
                        onClick = onLater,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MoaLogColors.CardBorder),
                    ) { Text("나중에", color = MoaLogColors.MutedInk) }
                }
            }
        }
    }
}
