package kr.jm.moalog.feature.assets.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kr.jm.moalog.core.designsystem.MoaLogColors
import kr.jm.moalog.core.model.LedgerMember
import kr.jm.moalog.core.model.AssetKind
import kr.jm.moalog.core.model.AssetType
import kr.jm.moalog.core.model.YearMonthKey
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal class AssetEditorAndroidViewModel(
    val holder: AssetEditorStateHolder,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    init {
        val restoredDraft = AssetEditorSavedStateCodec.read(savedStateHandle)
        var awaitingRestore = restoredDraft != null
        restoredDraft?.let { holder.onAction(AssetEditorAction.RestoreDraft(it)) }
        viewModelScope.launch {
            holder.state.collect { state ->
                if (awaitingRestore && state.isLoading) return@collect
                awaitingRestore = false
                if (state.isDirty && !state.saved) AssetEditorSavedStateCodec.write(savedStateHandle, state.toDraftSnapshot())
                else AssetEditorSavedStateCodec.clear(savedStateHandle)
            }
        }
    }
    override fun onCleared() = holder.close()
}

internal object AssetEditorSavedStateCodec {
    private const val PRESENT = "asset.editor.present"
    fun write(handle: SavedStateHandle, value: AssetEditorDraftSnapshot) {
        handle[PRESENT] = true; handle["asset.editor.mode"] = value.selectedMode.name; handle["asset.editor.name"] = value.name
        handle["asset.editor.type"] = value.type.name; handle["asset.editor.owner"] = value.ownerMemberOrder; handle["asset.editor.memo"] = value.memo
        handle["asset.editor.kind"] = value.kind.name; handle["asset.editor.year"] = value.month.year; handle["asset.editor.month"] = value.month.month
        handle["asset.editor.amount"] = value.amount; handle["asset.editor.duration"] = value.duration; handle["asset.editor.base"] = value.baseAmount
        handle["asset.editor.increase"] = value.monthlyIncrease
    }
    fun read(handle: SavedStateHandle): AssetEditorDraftSnapshot? {
        if (handle.get<Boolean>(PRESENT) != true) return null
        return runCatching {
            AssetEditorDraftSnapshot(
                AssetEditorMode.valueOf(requireNotNull(handle.get<String>("asset.editor.mode"))), requireNotNull(handle.get<String>("asset.editor.name")),
                AssetType.valueOf(requireNotNull(handle.get<String>("asset.editor.type"))), handle.get<Int?>("asset.editor.owner"), requireNotNull(handle.get<String>("asset.editor.memo")),
                AssetKind.valueOf(requireNotNull(handle.get<String>("asset.editor.kind"))), YearMonthKey(requireNotNull(handle.get<Int>("asset.editor.year")), requireNotNull(handle.get<Int>("asset.editor.month"))),
                requireNotNull(handle.get<String>("asset.editor.amount")), requireNotNull(handle.get<String>("asset.editor.duration")), requireNotNull(handle.get<String>("asset.editor.base")), requireNotNull(handle.get<String>("asset.editor.increase")),
            )
        }.getOrNull()
    }
    fun clear(handle: SavedStateHandle) { handle.remove<Boolean>(PRESENT) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetEditorRoute(
    args: AssetEditorArgs,
    members: List<LedgerMember>,
    onBack: () -> Unit,
    onSaved: (Long?, YearMonthKey) -> Unit,
    stateHolder: AssetEditorStateHolder = koinInject(parameters = { parametersOf(args) }),
) {
    val androidViewModel = viewModel<AssetEditorAndroidViewModel>(
        factory = remember(stateHolder) { viewModelFactory { initializer { AssetEditorAndroidViewModel(stateHolder, createSavedStateHandle()) } } },
    )
    val restoredHolder = androidViewModel.holder
    val state by restoredHolder.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showDiscard by remember { mutableStateOf(false) }
    val requestClose = { if (state.isDirty && !state.saved) showDiscard = true else onBack() }
    BackHandler(onBack = requestClose)
    LaunchedEffect(state.saved) { if (state.saved) onSaved(state.savedAssetId, state.savedMonth ?: args.month) }
    Box(Modifier.fillMaxSize().background(Color(0x4D2D312E))) {
        ModalBottomSheet(onDismissRequest = requestClose, sheetState = sheetState, containerColor = Color.White, dragHandle = { BottomSheetDefaults.DragHandle() }) {
            AssetEditorScreen(state, members, restoredHolder::onAction, requestClose)
        }
    }
    if (showDiscard) AlertDialog(onDismissRequest = { showDiscard = false }, title = { Text("입력을 취소할까요?") }, text = { Text("저장하지 않은 변경 내용이 사라집니다.") }, confirmButton = { TextButton({ showDiscard = false; onBack() }) { Text("취소하기") } }, dismissButton = { TextButton({ showDiscard = false }) { Text("계속 입력") } })
}

@Composable
fun AssetEditorScreen(
    state: AssetEditorUiState,
    members: List<LedgerMember>,
    onAction: (AssetEditorAction) -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(.9f)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("기록 편집", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            IconButton(onClose, Modifier.size(48.dp)) { Icon(Icons.Default.Close, "닫기") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
        if (state.isLoading) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorTab(if (state.args.purposeAccount) "신규 통장" else "신규 항목", state.selectedMode == AssetEditorMode.NewAsset) { onAction(AssetEditorAction.SelectMode(AssetEditorMode.NewAsset)) }
                if (state.args.assetId != null) {
                    EditorTab("월 금액 수정", state.selectedMode == AssetEditorMode.MonthlyValue) { onAction(AssetEditorAction.SelectMode(AssetEditorMode.MonthlyValue)) }
                    EditorTab("예상 증가 설정", state.selectedMode == AssetEditorMode.GrowthRule) { onAction(AssetEditorAction.SelectMode(AssetEditorMode.GrowthRule)) }
                }
            }
            when (state.selectedMode) {
                AssetEditorMode.NewAsset -> NewAssetForm(state, members, onAction)
                AssetEditorMode.MonthlyValue -> MonthlyValueForm(state, onAction)
                AssetEditorMode.GrowthRule -> GrowthRuleForm(state, onAction)
            }
            state.error?.let {
                Column(Modifier.fillMaxWidth()) {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (state.failedOperation != null) TextButton({ onAction(AssetEditorAction.Retry) }) { Text("다시 시도") }
                }
            }
        }
        Surface(shadowElevation = 4.dp) { Button({ onAction(AssetEditorAction.Save) }, Modifier.fillMaxWidth().padding(12.dp, 12.dp, 12.dp, 20.dp).height(52.dp), enabled = state.canSave, shape = RoundedCornerShape(8.dp)) { if(state.isSaving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White) else Text("저장하기", fontWeight = FontWeight.Bold) } }
    }
}

@Composable private fun EditorTab(label: String, selected: Boolean, onClick: () -> Unit) { if(selected) Button(onClick, Modifier.heightIn(min=48.dp), contentPadding=PaddingValues(horizontal=14.dp), shape=RoundedCornerShape(24.dp)) { Text(label) } else OutlinedButton(onClick, Modifier.heightIn(min=48.dp), contentPadding=PaddingValues(horizontal=14.dp), shape=RoundedCornerShape(24.dp)) { Text(label) } }

@Composable private fun NewAssetForm(state: AssetEditorUiState, members: List<LedgerMember>, onAction: (AssetEditorAction)->Unit) {
    EditorTextField(if (state.args.purposeAccount) "통장 이름" else "자산 이름", state.name, { onAction(AssetEditorAction.ChangeName(it)) }, if (state.args.purposeAccount) "예: 여행통장" else "예: 국민은행 적금")
    Text("자산 유형", style = MaterialTheme.typography.labelMedium, color = MoaLogColors.MutedInk)
    AssetType.entries.chunked(2).forEach { types ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            types.forEach { type -> FilterChip(state.type == type, { onAction(AssetEditorAction.ChangeType(type)) }, { Text(type.displayName) }, Modifier.weight(1f)) }
            if (types.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    Text("자산 구분", style = MaterialTheme.typography.labelMedium, color = MoaLogColors.MutedInk)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(state.kind == AssetKind.Ordinary, { onAction(AssetEditorAction.ChangeKind(AssetKind.Ordinary)) }, { Text("일반 자산") }, Modifier.weight(1f))
        FilterChip(state.kind == AssetKind.PurposeAccount, { onAction(AssetEditorAction.ChangeKind(AssetKind.PurposeAccount)) }, { Text("목적통장") }, Modifier.weight(1f))
    }
    Text("소유자", style = MaterialTheme.typography.labelMedium, color = MoaLogColors.MutedInk)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        val owners = listOf<Pair<String,Int?>>("공동" to null) + members.take(2).map { it.displayName to it.order }
        owners.forEachIndexed { index, owner -> SegmentedButton(state.ownerMemberOrder == owner.second, { onAction(AssetEditorAction.ChangeOwner(owner.second)) }, SegmentedButtonDefaults.itemShape(index, owners.size), label={ Text(owner.first) }) }
    }
    EditorTextField("메모", state.memo, { onAction(AssetEditorAction.ChangeMemo(it)) }, "선택 입력")
    MonthField("기준월", state.month) { onAction(AssetEditorAction.ChangeMonth(it)) }
    MoneyField("초기 금액", state.amount) { onAction(AssetEditorAction.ChangeAmount(it)) }
}

@Composable private fun MonthlyValueForm(state: AssetEditorUiState, onAction: (AssetEditorAction)->Unit) {
    MonthField("수정할 월", state.month) { onAction(AssetEditorAction.ChangeMonth(it)) }
    MoneyField("금액", state.amount) { onAction(AssetEditorAction.ChangeAmount(it)) }
    Surface(Modifier.fillMaxWidth(), color=Color(0xFFF1F4F0), shape=RoundedCornerShape(12.dp)) { Column(Modifier.padding(14.dp)) { Text("확정 잔액으로 저장", fontWeight=FontWeight.Bold); Text("예상 금액이 있어도 이 금액이 우선 적용됩니다", style=MaterialTheme.typography.labelSmall, color=MoaLogColors.Outline) } }
}

@Composable private fun GrowthRuleForm(state: AssetEditorUiState, onAction: (AssetEditorAction)->Unit) {
    MonthField("기준 시작월", state.month) { onAction(AssetEditorAction.ChangeMonth(it)) }
    EditorTextField("반복 기간", state.duration, { onAction(AssetEditorAction.ChangeDuration(it)) }, "12", "개월", true)
    MoneyField("현재 기준 금액", state.baseAmount) { onAction(AssetEditorAction.ChangeBaseAmount(it)) }
    MoneyField("매월 예상 증감액", state.monthlyIncrease, signed = true) { onAction(AssetEditorAction.ChangeMonthlyIncrease(it)) }
    val preview = previewAmount(state)
    OutlinedButton({ onAction(AssetEditorAction.TogglePreview) }, Modifier.fillMaxWidth().heightIn(min=48.dp), enabled=preview != null) { Icon(Icons.Default.Visibility,null); Text(" 증가 추이 미리보기") }
    if (state.isPreviewVisible && preview != null) {
        Surface(Modifier.fillMaxWidth(), color = Color(0xFFF1F4F0), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("예상 잔액", fontWeight = FontWeight.Bold)
                previewRows(state).forEach { (month, amount) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${month.year}년 ${month.month}월", color = MoaLogColors.MutedInk)
                        Text("${formatWon(amount)}원", fontWeight = FontWeight.SemiBold, color = Color(0xFF2A69AC))
                    }
                }
            }
        }
    }
}

@Composable private fun EditorTextField(label:String,value:String,onChange:(String)->Unit,placeholder:String,suffix:String?=null,number:Boolean=false,signed:Boolean=false) { Column(verticalArrangement=Arrangement.spacedBy(4.dp)) { Text(label,style=MaterialTheme.typography.labelMedium,color=MoaLogColors.MutedInk); OutlinedTextField(value,onChange,Modifier.fillMaxWidth().heightIn(min=52.dp),placeholder={Text(placeholder)},suffix=suffix?.let { text -> { Text(text) } },singleLine=true,shape=RoundedCornerShape(10.dp),keyboardOptions=KeyboardOptions(keyboardType=if(signed) KeyboardType.Text else if(number) KeyboardType.Number else KeyboardType.Text)) } }
@Composable private fun MoneyField(label:String,value:String,signed:Boolean=false,onChange:(String)->Unit) = EditorTextField(label,value,onChange,"0","원",true,signed)
@Composable private fun MonthField(label:String,month:YearMonthKey,onSelected:(YearMonthKey)->Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(label,style=MaterialTheme.typography.labelMedium,color=MoaLogColors.MutedInk)
        OutlinedButton({ showPicker = true }, Modifier.fillMaxWidth().heightIn(min=52.dp), shape=RoundedCornerShape(10.dp)) {
            Icon(Icons.Default.CalendarMonth, null); Spacer(Modifier.width(8.dp)); Text("${month.year}년 ${month.month}월", Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, "연도와 월 선택")
        }
    }
    if (showPicker) AssetMonthPickerDialog(month, { showPicker = false }) { onSelected(it); showPicker = false }
}
private fun previewAmount(state:AssetEditorUiState):Long? { val base=state.baseAmount.toLongOrNull()?:return null; val increase=state.monthlyIncrease.toLongOrNull()?:return null; return safeAdd(base, increase) }
private fun previewRows(state: AssetEditorUiState): List<Pair<YearMonthKey, Long>> {
    val base = state.baseAmount.toLongOrNull() ?: return emptyList()
    val increase = state.monthlyIncrease.toLongOrNull() ?: return emptyList()
    val count = minOf(state.duration.toIntOrNull() ?: return emptyList(), 3)
    return (1..count).mapNotNull { offset ->
        val step = offset.toLong()
        val change = safeMultiplyByNonNegative(increase, step)
        val amount = change?.let { safeAdd(base, it) }
        if (amount == null) null else state.month.plusMonthsOrNull(offset)?.let { it to amount }
    }
}

private fun safeMultiplyByNonNegative(value: Long, multiplier: Long): Long? {
    if (value == 0L || multiplier == 0L) return 0L
    if (value > 0L && value > Long.MAX_VALUE / multiplier) return null
    if (value < 0L && value < Long.MIN_VALUE / multiplier) return null
    return value * multiplier
}

private fun safeAdd(left: Long, right: Long): Long? = when {
    right > 0L && left > Long.MAX_VALUE - right -> null
    right < 0L && left < Long.MIN_VALUE - right -> null
    else -> left + right
}
