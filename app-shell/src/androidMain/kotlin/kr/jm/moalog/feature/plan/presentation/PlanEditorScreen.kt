package kr.jm.moalog.feature.plan.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.compose.viewModel
import kr.jm.moalog.core.designsystem.MoaLogColors
import kr.jm.moalog.core.model.LedgerMember
import kr.jm.moalog.core.model.PlanItemStatus
import kr.jm.moalog.core.model.PlanItemType
import kr.jm.moalog.core.model.YearMonthKey
import org.koin.compose.getKoin
import org.koin.core.parameter.parametersOf
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private val incomeCategories = listOf("급여", "부수입", "성과급", "기타 수입")
private val fixedCategories = listOf("주거비", "교통비", "생활비", "보험", "통신비", "비상금", "구독", "개별 용돈")
private val savingsCategories = listOf("예적금", "청약", "주식", "부동산", "대출원금", "퇴직금")

internal class PlanEditorAndroidViewModel(
    val stateHolder: PlanEditorStateHolder,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private var pendingRestoredSnapshot = PlanEditorSavedStateCodec.read(savedStateHandle)

    init {
        stateHolder.start()
        pendingRestoredSnapshot?.let { snapshot ->
            stateHolder.onAction(PlanEditorAction.RestoreDraft(snapshot))
        }
        viewModelScope.launch {
            stateHolder.state.collect(::saveDraftState)
        }
    }

    private fun saveDraftState(state: PlanEditorUiState) {
        pendingRestoredSnapshot?.let { snapshot ->
            if (state.isLoading || state.initialLoadFailed || state.toDraftSnapshot() != snapshot) return
            pendingRestoredSnapshot = null
        }

        when {
            state.completed || state.exitRequested -> PlanEditorSavedStateCodec.clear(savedStateHandle)
            state.hasUnsavedChanges || state.errors.hasAny || state.showDiscardConfirmation -> {
                PlanEditorSavedStateCodec.write(savedStateHandle, state.toDraftSnapshot())
            }
            else -> PlanEditorSavedStateCodec.clear(savedStateHandle)
        }
    }

    override fun onCleared() = stateHolder.close()
}

internal object PlanEditorSavedStateCodec {
    private const val PRESENT = "plan_editor.draft.present"
    private const val TYPE = "plan_editor.draft.type"
    private const val MONTH = "plan_editor.draft.month"
    private const val NAME = "plan_editor.draft.name"
    private const val AMOUNT = "plan_editor.draft.amount"
    private const val CATEGORY = "plan_editor.draft.category"
    private const val STATUS = "plan_editor.draft.status"
    private const val OWNER_PRESENT = "plan_editor.draft.owner_present"
    private const val OWNER = "plan_editor.draft.owner"
    private const val MEMO = "plan_editor.draft.memo"
    private const val PURPOSE = "plan_editor.draft.purpose"
    private const val NET = "plan_editor.draft.net"
    private const val ERROR_MONTH = "plan_editor.draft.error_month"
    private const val ERROR_NAME = "plan_editor.draft.error_name"
    private const val ERROR_AMOUNT = "plan_editor.draft.error_amount"
    private const val ERROR_CATEGORY = "plan_editor.draft.error_category"
    private const val SHOW_DISCARD = "plan_editor.draft.show_discard"
    private const val HAS_CHANGES = "plan_editor.draft.has_changes"
    private const val CATALOG_ID = "plan_editor.draft.catalog_id"

    fun write(handle: SavedStateHandle, snapshot: PlanEditorDraftSnapshot) {
        handle[PRESENT] = true
        handle[TYPE] = snapshot.type.name
        handle[MONTH] = snapshot.month
        handle[NAME] = snapshot.name
        handle[AMOUNT] = snapshot.amount
        handle[CATEGORY] = snapshot.category
        handle[STATUS] = snapshot.status.name
        handle[OWNER_PRESENT] = snapshot.ownerMemberOrder != null
        handle[OWNER] = snapshot.ownerMemberOrder ?: 0
        handle[MEMO] = snapshot.memo
        handle[PURPOSE] = snapshot.includePurposeAccount
        handle[NET] = snapshot.includeNetSavings
        handle[ERROR_MONTH] = snapshot.errors.month
        handle[ERROR_NAME] = snapshot.errors.name
        handle[ERROR_AMOUNT] = snapshot.errors.amount
        handle[ERROR_CATEGORY] = snapshot.errors.category
        handle[SHOW_DISCARD] = snapshot.showDiscardConfirmation
        handle[HAS_CHANGES] = snapshot.hasUnsavedChanges
        handle[CATALOG_ID] = snapshot.selectedCatalogId
    }

    fun read(handle: SavedStateHandle): PlanEditorDraftSnapshot? {
        if (handle.get<Boolean>(PRESENT) != true) return null
        val type = enumValueOrNull<PlanItemType>(handle[TYPE]) ?: return null
        val status = enumValueOrNull<PlanItemStatus>(handle[STATUS]) ?: return null
        return PlanEditorDraftSnapshot(
            type = type,
            month = handle[MONTH] ?: return null,
            name = handle[NAME] ?: return null,
            amount = handle[AMOUNT] ?: return null,
            category = handle[CATEGORY] ?: return null,
            status = status,
            ownerMemberOrder = if (handle.get<Boolean>(OWNER_PRESENT) == true) handle.get<Int>(OWNER) else null,
            memo = handle[MEMO] ?: return null,
            includePurposeAccount = handle[PURPOSE] ?: false,
            includeNetSavings = handle[NET] ?: false,
            errors = PlanEditorErrors(
                month = handle[ERROR_MONTH],
                name = handle[ERROR_NAME],
                amount = handle[ERROR_AMOUNT],
                category = handle[ERROR_CATEGORY],
            ),
            showDiscardConfirmation = handle[SHOW_DISCARD] ?: false,
            hasUnsavedChanges = handle[HAS_CHANGES] ?: false,
            selectedCatalogId = handle[CATALOG_ID],
        )
    }

    fun clear(handle: SavedStateHandle) {
        handle.remove<Boolean>(PRESENT)
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(name: String?): T? =
        name?.let { value -> enumValues<T>().firstOrNull { it.name == value } }
}

@Composable
fun PlanEditorRoute(
    args: PlanEditorArgs,
    members: List<LedgerMember>,
    onFinished: () -> Unit,
    onBack: () -> Unit,
    onSavedAndApply: (Long, YearMonthKey, PlanItemType) -> Unit,
    registerSystemBackRequest: ((() -> Unit)?) -> Unit,
) {
    val koin = getKoin()
    val androidViewModel = viewModel<PlanEditorAndroidViewModel>(
        factory = remember(args, koin) {
            viewModelFactory {
                initializer {
                    PlanEditorAndroidViewModel(
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
        registerSystemBackRequest { stateHolder.onAction(PlanEditorAction.RequestBack) }
        onDispose { registerSystemBackRequest(null) }
    }
    LaunchedEffect(state.completion) {
        when (val completion = state.completion) {
            null -> Unit
            else -> if (completion.kind == PlanEditorCompletionKind.SavedAndApply) {
                onSavedAndApply(completion.itemId, completion.month, completion.type)
            } else {
                onFinished()
            }
        }
    }
    LaunchedEffect(state.exitRequested) { if (state.exitRequested) onBack() }
    PlanEditorScreen(state, members, stateHolder::onAction)
}

@Composable
fun PlanEditorScreen(
    state: PlanEditorUiState,
    members: List<LedgerMember>,
    onAction: (PlanEditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(state.errors.hasAny, state.persistenceError) {
        if (state.errors.hasAny || state.persistenceError != null) scroll.animateScrollTo(0)
    }
    Box(modifier.fillMaxSize().background(MoaLogColors.Canvas).imePadding()) {
        if (state.isLoading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = MoaLogColors.DeepTeal)
        else if (state.initialLoadFailed) InitialLoadFailure(state.persistenceError, onAction)
        else {
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 20.dp, end = 20.dp, bottom = 156.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                EditorToolbar(state, onAction)
                if (!state.isEditing) TypeSelector(state.type) { onAction(PlanEditorAction.TypeChanged(it)) }
                CatalogSelector(state, onAction)
                val message = state.persistenceError ?: state.errors.let { listOfNotNull(it.month, it.name, it.amount, it.category).firstOrNull() }
                message?.let { ErrorBanner(it) }
                EditorCard {
                    MonthSelector(state.month, state.errors.month, onAction)
                    Field("항목명 *", state.name, state.type.nameHint(), state.errors.name) { onAction(PlanEditorAction.NameChanged(it)) }
                    AmountField(state, onAction)
                    CategorySelector(state, onAction)
                }
                if (state.type != PlanItemType.Savings) {
                    EditorCard {
                        OwnerSection(state, members, onAction)
                        StatusSection(state, onAction)
                    }
                } else {
                    EditorCard {
                        OwnerSection(state, members, onAction)
                        StatusSection(state, onAction)
                    }
                    EditorCard {
                        ToggleRow("목적통장으로 분류", "목적통장에 모으는 저축으로 구분해요", state.includePurposeAccount) { onAction(PlanEditorAction.PurposeChanged(it)) }
                        ToggleRow("순저축에 포함", "켜면 순저축과 순저축률 계산에 포함돼요", state.includeNetSavings) { onAction(PlanEditorAction.NetChanged(it)) }
                        Text("모든 저축 항목은 목적통장 분류와 관계없이 저축률 계산에 포함돼요.", color = MoaLogColors.MutedInk, style = MaterialTheme.typography.bodySmall)
                    }
                }
                EditorCard { MemoField(state, onAction) }
                TipCard(state.type)
            }
            BottomActions(
                saving = state.isSaving,
                save = { onAction(PlanEditorAction.Save) },
                saveAndApply = { onAction(PlanEditorAction.SaveAndApply) },
                canSaveAndApply = !state.selectedCatalogIsArchived,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
    if (state.showDeleteConfirmation) ConfirmDialog("계획 항목을 삭제할까요?", "이 달의 계획에서 항목이 삭제되며 되돌릴 수 없어요.", "삭제", { onAction(PlanEditorAction.DismissDelete) }) { onAction(PlanEditorAction.ConfirmDelete) }
    if (state.showDiscardConfirmation) ConfirmDialog("입력을 그만둘까요?", "저장하지 않은 내용이 사라져요.", "나가기", { onAction(PlanEditorAction.DismissDiscard) }) { onAction(PlanEditorAction.ConfirmDiscard) }
}

@Composable
private fun InitialLoadFailure(message: String?, onAction: (PlanEditorAction) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ onAction(PlanEditorAction.RequestBack) }, Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "계획으로 돌아가기")
            }
            Text(
                "계획 항목 수정",
                Modifier.weight(1f),
                color = MoaLogColors.TealInk,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(48.dp))
        }
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(Modifier.size(56.dp), shape = CircleShape, color = MaterialTheme.colorScheme.errorContainer) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Info, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.error)
                }
            }
            Text("계획 항목을 불러오지 못했어요", Modifier.padding(top = 18.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                message ?: "잠시 후 다시 시도해 주세요.",
                Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Assertive },
                color = MoaLogColors.MutedInk,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Button(
                { onAction(PlanEditorAction.RetryLoad) },
                Modifier.fillMaxWidth().padding(top = 24.dp).heightIn(min = 52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MoaLogColors.DeepTeal),
            ) { Text("다시 시도", fontWeight = FontWeight.Bold) }
            TextButton(
                { onAction(PlanEditorAction.RequestBack) },
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("계획으로 돌아가기", color = MoaLogColors.DeepTeal) }
        }
    }
}

@Composable
private fun EditorToolbar(state: PlanEditorUiState, onAction: (PlanEditorAction) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton({ onAction(PlanEditorAction.RequestBack) }, Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "계획으로 돌아가기") }
        Text(
            if (state.isEditing) "${state.type.title()} 계획" else "계획 항목 입력",
            Modifier.weight(1f), color = MoaLogColors.TealInk, style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        )
        if (state.isEditing) IconButton({ onAction(PlanEditorAction.RequestDelete) }, Modifier.size(48.dp)) { Icon(Icons.Default.Delete, "계획 항목 삭제", tint = MoaLogColors.Overspend) }
        else TextButton({ onAction(PlanEditorAction.RequestBack) }, Modifier.heightIn(min = 48.dp)) { Text("취소") }
    }
}

@Composable
private fun TypeSelector(selected: PlanItemType, select: (PlanItemType) -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MoaLogColors.CardBorder)) {
        Row(Modifier.padding(4.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PlanItemType.entries.forEach { type ->
                val active = type == selected
                Surface(
                    Modifier.weight(1f).heightIn(min = 48.dp).selectable(active, role = Role.Tab) { select(type) },
                    color = if (active) MoaLogColors.DeepTealContainer else Color.Transparent,
                    shape = RoundedCornerShape(9.dp),
                ) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        if (active) { Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp), tint = MoaLogColors.DeepTeal); Spacer(Modifier.width(4.dp)) }
                        Text(type.tabTitle(), color = if (active) MoaLogColors.DeepTeal else MoaLogColors.MutedInk, style = MaterialTheme.typography.labelMedium, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogSelector(state: PlanEditorUiState, onAction: (PlanEditorAction) -> Unit) {
    val items = state.catalogItems
        .filter { it.type == state.type && (!it.archived || it.id == state.selectedCatalogId) }
        .sortedWith(compareBy({ it.archived }, { it.displayOrder }, { it.id }))
    if (items.isEmpty() && state.isEditing) return
    EditorCard {
        Text("관리 항목", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (state.isEditing) {
            val selected = items.firstOrNull { it.id == state.selectedCatalogId }
            Text(
                selected?.let { if (it.archived) "${it.name} · 보관됨" else it.name } ?: "연결된 관리 항목 없음",
                color = if (selected?.archived == true) MoaLogColors.MutedInk else MoaLogColors.TealInk,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(
                "등록된 항목을 선택하면 분류와 담당 정보가 자동으로 입력돼요.",
                color = MoaLogColors.MutedInk,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.selectedCatalogId == null,
                    onClick = { onAction(PlanEditorAction.CatalogSelected(null)) },
                    label = { Text("직접 입력") },
                )
                items.filterNot { it.archived }.forEach { item ->
                    FilterChip(
                        selected = state.selectedCatalogId == item.id,
                        onClick = { onAction(PlanEditorAction.CatalogSelected(item.id)) },
                        label = { Text(item.name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AmountField(state: PlanEditorUiState, onAction: (PlanEditorAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("월 금액 *", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(if (state.type == PlanItemType.Savings) "음수 입력 가능" else "원 단위", color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelSmall)
        }
        BasicTextField(
            value = state.amount,
            onValueChange = { value -> onAction(PlanEditorAction.AmountChanged(value.filter { it.isDigit() || it == '-' || it == ',' })) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).background(Color.White, RoundedCornerShape(12.dp))
                .border(1.dp, state.errors.amount?.let { MaterialTheme.colorScheme.error } ?: MoaLogColors.CardBorder, RoundedCornerShape(12.dp))
                .padding(start = 16.dp, end = 8.dp).semantics { contentDescription = "월 금액 필수"; state.errors.amount?.let { error(it) } },
            textStyle = MaterialTheme.typography.headlineMedium.copy(color = MoaLogColors.TealInk, fontWeight = FontWeight.Bold),
            keyboardOptions = KeyboardOptions(keyboardType = if (state.type == PlanItemType.Savings) KeyboardType.Text else KeyboardType.Number), singleLine = true, cursorBrush = SolidColor(MoaLogColors.DeepTeal),
            decorationBox = { input ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { if (state.amount.isEmpty()) Text("0", color = MoaLogColors.Outline, style = MaterialTheme.typography.headlineMedium); input() }
                    if (state.amount.isNotEmpty()) IconButton({ onAction(PlanEditorAction.ResetAmount) }, Modifier.size(48.dp)) { Icon(Icons.Default.Close, "금액 지우기", Modifier.size(18.dp), tint = MoaLogColors.MutedInk) }
                    Text("원", Modifier.padding(end = 8.dp), color = MoaLogColors.MutedInk)
                }
            },
        )
        state.errors.amount?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10_000L, 50_000L, 100_000L).forEach { increment ->
                OutlinedButton(
                    { onAction(PlanEditorAction.AddAmount(increment)) },
                    Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(4.dp), shape = RoundedCornerShape(24.dp),
                ) { Text("+${increment / 10_000}만") }
            }
        }
        if (state.type == PlanItemType.Savings) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Info, null, Modifier.size(18.dp), tint = MoaLogColors.MutedInk)
            Text("인출·매도·원금 감소는 음수로 입력해요", Modifier.padding(start = 6.dp), color = MoaLogColors.MutedInk, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MonthSelector(value: String, fieldError: String?, onAction: (PlanEditorAction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = parseMonth(value)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("귀속월 *", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Box {
            Surface(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClickLabel = "귀속월 선택") { expanded = true },
                color = Color.White, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, fieldError?.let { MaterialTheme.colorScheme.error } ?: MoaLogColors.CardBorder),
            ) {
                Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CalendarMonth, null, Modifier.size(20.dp), tint = MoaLogColors.DeepTeal)
                    Text("${selected.year}년 ${selected.month}월", Modifier.weight(1f).padding(horizontal = 10.dp), fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Default.ArrowDropDown, null, tint = MoaLogColors.MutedInk)
                }
            }
            DropdownMenu(expanded, { expanded = false }) {
                (-6..6).mapNotNull { offsetMonth(selected, it) }.forEach { month ->
                    DropdownMenuItem({ Text("${month.year}년 ${month.month}월") }, { expanded = false; onAction(PlanEditorAction.MonthChanged(month.toString())) })
                }
            }
        }
        fieldError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun Field(label: String, value: String, hint: String, fieldError: String?, minLines: Int = 1, onValue: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        BasicTextField(
            value, onValue,
            Modifier.fillMaxWidth().heightIn(min = if (minLines == 1) 52.dp else 112.dp).background(Color.White, RoundedCornerShape(12.dp))
                .border(1.dp, fieldError?.let { MaterialTheme.colorScheme.error } ?: MoaLogColors.CardBorder, RoundedCornerShape(12.dp))
                .padding(16.dp).semantics { contentDescription = label.ifEmpty { "메모" }; fieldError?.let { error(it) } },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), singleLine = minLines == 1, minLines = minLines,
            cursorBrush = SolidColor(MoaLogColors.DeepTeal),
            decorationBox = { input -> Box(contentAlignment = if (minLines == 1) Alignment.CenterStart else Alignment.TopStart) { if (value.isEmpty()) Text(hint, color = MoaLogColors.MutedInk); input() } },
        )
        fieldError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun CategorySelector(state: PlanEditorUiState, onAction: (PlanEditorAction) -> Unit) {
    var expanded by remember(state.type) { mutableStateOf(false) }
    val categories = when (state.type) { PlanItemType.Income -> incomeCategories; PlanItemType.FixedExpense -> fixedCategories; PlanItemType.Savings -> savingsCategories }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(state.type.categoryLabel(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Box {
            Surface(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClickLabel = "카테고리 선택") { expanded = true },
                color = Color.White, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, state.errors.category?.let { MaterialTheme.colorScheme.error } ?: MoaLogColors.CardBorder),
            ) { Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { Text(state.category.ifBlank { "선택해 주세요" }, Modifier.weight(1f), color = if (state.category.isBlank()) MoaLogColors.MutedInk else MaterialTheme.colorScheme.onSurface); Icon(Icons.Default.ArrowDropDown, null) } }
            DropdownMenu(expanded, { expanded = false }) { categories.forEach { category -> DropdownMenuItem({ Text(category) }, { expanded = false; onAction(PlanEditorAction.CategoryChanged(category)) }) } }
        }
        state.errors.category?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun OwnerSection(state: PlanEditorUiState, members: List<LedgerMember>, onAction: (PlanEditorAction) -> Unit) {
    Text(if (state.type == PlanItemType.Income) "소득 귀속" else "공동/구성원", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OwnerChip("공동", state.ownerMemberOrder == null, Modifier.weight(1f)) { onAction(PlanEditorAction.OwnerChanged(null)) }
        members.sortedBy { it.order }.forEach { member -> OwnerChip(member.displayName, state.ownerMemberOrder == member.order, Modifier.weight(1f)) { onAction(PlanEditorAction.OwnerChanged(member.order)) } }
    }
}

@Composable
private fun StatusSection(state: PlanEditorUiState, onAction: (PlanEditorAction) -> Unit) {
    Text("상태 구분", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusChoice("예상 금액", "아직 확정 전", state.status == PlanItemStatus.Estimated, Modifier.weight(1f)) { onAction(PlanEditorAction.StatusChanged(PlanItemStatus.Estimated)) }
        StatusChoice("확정 금액", "실제 금액", state.status == PlanItemStatus.Confirmed, Modifier.weight(1f)) { onAction(PlanEditorAction.StatusChanged(PlanItemStatus.Confirmed)) }
    }
}

@Composable
private fun StatusChoice(title: String, detail: String, selected: Boolean, modifier: Modifier, click: () -> Unit) {
    Surface(
        modifier.heightIn(min = 72.dp).selectable(selected, role = Role.RadioButton, onClick = click), color = if (selected) MoaLogColors.DeepTealContainer else Color.White,
        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (selected) MoaLogColors.DeepTeal else MoaLogColors.CardBorder),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(20.dp), shape = CircleShape, color = if (selected) MoaLogColors.DeepTeal else Color.White, border = BorderStroke(1.dp, if (selected) MoaLogColors.DeepTeal else MoaLogColors.Outline)) {
                if (selected) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Box(Modifier.size(8.dp).background(Color.White, CircleShape)) }
            }
            Column(Modifier.padding(start = 8.dp)) { Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (selected) MoaLogColors.DeepTeal else MaterialTheme.colorScheme.onSurface); Text(detail, style = MaterialTheme.typography.labelSmall, color = MoaLogColors.MutedInk) }
        }
    }
}

@Composable
private fun MemoField(state: PlanEditorUiState, onAction: (PlanEditorAction) -> Unit) {
    Row(Modifier.fillMaxWidth()) { Text("메모 (선택)", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold); Text("${state.memo.length}/100", color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelSmall) }
    Field("", state.memo, "메모를 입력해 주세요", null, minLines = 3) { onAction(PlanEditorAction.MemoChanged(it.take(100))) }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, checkedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(checked, role = Role.Switch, onValueChange = checkedChange), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(detail, color = MoaLogColors.MutedInk, style = MaterialTheme.typography.bodySmall) }
        Switch(checked, null)
    }
}

@Composable
private fun TipCard(type: PlanItemType) {
    Surface(Modifier.fillMaxWidth(), color = Color(0xFFF0F4EF), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MoaLogColors.CardBorder)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Lightbulb, null, Modifier.size(20.dp), tint = MoaLogColors.DeepTeal)
            Column(Modifier.padding(start = 10.dp)) { Text(type.tipTitle(), fontWeight = FontWeight.Bold, color = MoaLogColors.TealInk); Text(type.tip(), Modifier.padding(top = 4.dp), color = MoaLogColors.MutedInk, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun BottomActions(saving: Boolean, save: () -> Unit, saveAndApply: () -> Unit, canSaveAndApply: Boolean, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), color = Color.White, shadowElevation = 8.dp) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            Button(save, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !saving, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = MoaLogColors.DeepTeal)) {
                if (saving) { CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("저장 중") }
                else Text("저장하기", fontWeight = FontWeight.Bold)
            }
            TextButton(saveAndApply, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !saving && canSaveAndApply) { Text("저장 후 여러 달에 적용", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable private fun EditorCard(content: @Composable ColumnScope.() -> Unit) = Surface(Modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MoaLogColors.CardBorder), shadowElevation = 1.dp) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content) }

@Composable
private fun OwnerChip(label: String, selected: Boolean, modifier: Modifier, click: () -> Unit) = Surface(
    modifier.heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = click), color = if (selected) MoaLogColors.DeepTealContainer else Color.White,
    shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, if (selected) MoaLogColors.DeepTeal else MoaLogColors.CardBorder),
) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(label, color = if (selected) MoaLogColors.DeepTeal else MoaLogColors.MutedInk, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) } }

@Composable private fun ErrorBanner(message: String) = Surface(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive; error(message) }, color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) { Text(message, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall) }

@Composable private fun ConfirmDialog(title: String, message: String, confirm: String, dismiss: () -> Unit, onConfirm: () -> Unit) = AlertDialog(dismiss, title = { Text(title) }, text = { Text(message) }, confirmButton = { TextButton(onConfirm, Modifier.heightIn(min = 48.dp)) { Text(confirm, color = MoaLogColors.Overspend) } }, dismissButton = { TextButton(dismiss, Modifier.heightIn(min = 48.dp)) { Text("취소") } })

private fun parseMonth(value: String) = runCatching { value.split('-').let { YearMonthKey(it[0].toInt(), it[1].toInt()) } }.getOrDefault(YearMonthKey(2026, 1))
private fun offsetMonth(month: YearMonthKey, offset: Int): YearMonthKey? { var value = month; repeat(kotlin.math.abs(offset)) { value = if (offset < 0) previousPlanEditorMonth(value) ?: return null else nextPlanEditorMonth(value) ?: return null }; return value }
private fun PlanItemType.title() = when (this) { PlanItemType.Income -> "수입"; PlanItemType.FixedExpense -> "고정지출"; PlanItemType.Savings -> "저축" }
private fun PlanItemType.tabTitle() = when (this) { PlanItemType.Income -> "수입"; PlanItemType.FixedExpense -> "고정지출"; PlanItemType.Savings -> "저축·투자" }
private fun PlanItemType.nameHint() = when (this) { PlanItemType.Income -> "예: 종민 월급"; PlanItemType.FixedExpense -> "예: 월세, 통신비"; PlanItemType.Savings -> "예: 비상금 통장" }
private fun PlanItemType.categoryLabel() = when (this) { PlanItemType.Income -> "소득 구분 *"; PlanItemType.FixedExpense -> "카테고리 *"; PlanItemType.Savings -> "저축 카테고리 *" }
private fun PlanItemType.tipTitle() = when (this) { PlanItemType.Income -> "함께 만드는 수입 계획"; PlanItemType.FixedExpense -> "함께 점검하는 고정지출 계획"; PlanItemType.Savings -> "함께 모으는 저축 계획" }
private fun PlanItemType.tip() = when (this) { PlanItemType.Income -> "월급이나 정기 수입은 여러 달에 적용하면 반복 입력을 줄일 수 있어요."; PlanItemType.FixedExpense -> "매달 같은 고정지출은 저장 후 여러 달에 적용할 수 있어요."; PlanItemType.Savings -> "저축 계획을 먼저 세우면 두 사람이 같은 목표를 바라보기 쉬워져요." }
