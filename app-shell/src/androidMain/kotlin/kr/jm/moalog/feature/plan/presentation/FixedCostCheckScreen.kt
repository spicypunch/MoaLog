package kr.jm.moalog.feature.plan.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kr.jm.moalog.core.database.ExistingPlanPolicy
import kr.jm.moalog.core.designsystem.MoaLogColors
import kr.jm.moalog.core.model.FixedCostCheckItem
import kr.jm.moalog.core.model.LedgerMember
import kr.jm.moalog.core.model.LedgerSetup
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.core.model.sumWonOrNull
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun FixedCostCheckRoute(
    args: FixedCostCheckArgs,
    setup: LedgerSetup,
    onBack: (YearMonthKey) -> Unit,
    onApplied: (YearMonthKey) -> Unit,
    registerSystemBackRequest: ((() -> Unit)?) -> Unit,
) {
    var savedMonth by rememberSaveable(args.month.year,args.month.month){mutableStateOf(SalaryAllocationMonthSavedStateCodec.encode(args.month))}
    val selectedMonth=SalaryAllocationMonthSavedStateCodec.decode(savedMonth)?:args.month
    val latestMonth by rememberUpdatedState(selectedMonth)
    val latestOnBack by rememberUpdatedState(onBack)
    DisposableEffect(registerSystemBackRequest) {
        registerSystemBackRequest { latestOnBack(latestMonth) }
        onDispose { registerSystemBackRequest(null) }
    }
    key(selectedMonth){FixedCostCheckObservedRoute(args.copy(month=selectedMonth),setup,{onBack(selectedMonth)},{onApplied(selectedMonth)},{savedMonth=SalaryAllocationMonthSavedStateCodec.encode(it)})}
}

@Composable
private fun FixedCostCheckObservedRoute(args:FixedCostCheckArgs,setup:LedgerSetup,onBack:()->Unit,onApplied:()->Unit,onSelectMonth:(YearMonthKey)->Unit,stateHolder: FixedCostCheckStateHolder = koinInject { parametersOf(args) }) {
    val state by stateHolder.state.collectAsStateWithLifecycle()
    DisposableEffect(stateHolder) {
        stateHolder.start()
        onDispose(stateHolder::close)
    }
    LaunchedEffect(state.resultMessage) {
        if (state.resultMessage != null) {
            stateHolder.onAction(FixedCostCheckAction.ClearMessage)
            onApplied()
        }
    }
    FixedCostCheckScreen(state, setup.members.sortedBy { it.order }, stateHolder::onAction, onBack,onSelectMonth)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FixedCostCheckScreen(
    state: FixedCostCheckUiState,
    members: List<LedgerMember>,
    onAction: (FixedCostCheckAction) -> Unit,
    onBack: () -> Unit,
    onSelectMonth:(YearMonthKey)->Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(MoaLogColors.Canvas)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack, Modifier.size(48.dp)) { Icon(Icons.Default.ArrowBackIosNew, "계획으로 돌아가기") }
            Text("고정비 점검", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = { onAction(FixedCostCheckAction.ToggleAll) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (state.selectedIds.size == state.sheet.items.size && state.sheet.items.isNotEmpty()) "전체 해제" else "전체 선택")
            }
        }
        FixedCostMonthSelector(state.month,onSelectMonth)
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { FixedCostSummary(state, members) }
            if (state.isLoading) item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            else if (state.sheet.items.isEmpty()) item { EmptyFixedCosts { onAction(FixedCostCheckAction.OpenEditor()) } }
            else payerGroups(state.sheet.items, members).filter { it.items.isNotEmpty() }.forEach { group ->
                item(key = "header-${group.order}") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${group.label} 고정비 목록", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        Text("${group.items.size}건", color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelMedium)
                        if (state.selectionMode) TextButton({ onAction(FixedCostCheckAction.TogglePayer(group.order)) }, Modifier.heightIn(min = 48.dp)) { Text("전체 선택") }
                    }
                }
                items(group.items, key = { it.id }) { item ->
                    FixedCostRow(item, item.id in state.selectedIds, onAction)
                }
                item(key = "subtotal-${group.order}") {
                    val selected = group.items.filter { it.id in state.selectedIds }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${group.label} 소계 (${selected.size}건 선택됨)", Modifier.weight(1f), color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelMedium)
                        Text(formatNullableTotal(selected), color = MoaLogColors.TealInk, fontWeight = FontWeight.Bold)
                    }
                }
            }
            state.error?.let { error -> item { Text(error, Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, color = MaterialTheme.colorScheme.error) } }
            state.resultMessage?.let { message -> item {
                Surface(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, color = Color(0xFFE6F2F0), shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = MoaLogColors.DeepTeal)
                        Text(message, Modifier.padding(start = 9.dp).weight(1f), color = MoaLogColors.DeepTeal)
                    }
                }
            } }
        }
        Surface(color = Color.White, shadowElevation = 4.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onAction(FixedCostCheckAction.OpenEditor()) },
                    modifier = Modifier.weight(.42f).heightIn(min = 52.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MoaLogColors.CardBorder),
                ) { Icon(Icons.Default.Add, null); Text("항목 추가", Modifier.padding(start = 4.dp), maxLines = 1) }
                Button(
                    onClick = { onAction(FixedCostCheckAction.RequestApply) },
                    enabled = state.selectedIds.isNotEmpty() && !state.isSaving,
                    modifier = Modifier.weight(.58f).heightIn(min = 52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MoaLogColors.DeepTeal),
                ) { Text("계획에 가져오기", maxLines = 1); Icon(Icons.Default.ArrowForward, null, Modifier.padding(start = 4.dp)) }
            }
        }
    }
    state.editor?.let { FixedCostEditorSheet(it, members, state.isSaving, state.error, onAction) }
    state.conflictPreview?.let { FixedCostConflictDialog(state, members, onAction) }
}

@Composable private fun FixedCostMonthSelector(month:YearMonthKey,onSelectMonth:(YearMonthKey)->Unit){val previous=month.plusMonthsOrNull(-1);val next=month.plusMonthsOrNull(1);Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){IconButton({previous?.let(onSelectMonth)},enabled=previous!=null,modifier=Modifier.size(48.dp).semantics{contentDescription="이전 달";role=Role.Button}){Icon(Icons.Default.ChevronLeft,null)};Text("${month.year}년 ${month.month}월",Modifier.semantics{contentDescription="선택한 월 ${month.year}년 ${month.month}월"},fontWeight=FontWeight.Bold);IconButton({next?.let(onSelectMonth)},enabled=next!=null,modifier=Modifier.size(48.dp).semantics{contentDescription="다음 달";role=Role.Button}){Icon(Icons.Default.ChevronRight,null)}}}

@Composable private fun FixedCostSummary(state: FixedCostCheckUiState, members: List<LedgerMember>) {
    Surface(Modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MoaLogColors.CardBorder)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = Color(0xFFE6F2F0)) { Icon(Icons.Default.ReceiptLong, null, Modifier.padding(8.dp), tint = MoaLogColors.DeepTeal) }
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text("매월 정기 고정 지출", color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelMedium)
                    Text("${state.month.year}.${state.month.month.toString().padStart(2, '0')} 기준", color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(state.sheet.knownTotalWonOrNull?.let(::formatNumber) ?: "합계 범위 초과", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = if (state.sheet.hasOverflow) MoaLogColors.Overspend else Color.Unspecified)
                Text("원", Modifier.padding(start = 5.dp, bottom = 3.dp), color = MoaLogColors.MutedInk)
            }
            Text("매월 정기적으로 나가는 고정비를 점검하고 월별 계획에 가져오세요.", color = MoaLogColors.MutedInk, style = MaterialTheme.typography.bodySmall)
            if (state.sheet.hasMissingAmounts) Text("미입력 금액을 제외한 알려진 금액 합계예요", color = MoaLogColors.Overspend, style = MaterialTheme.typography.labelSmall)
            if (state.sheet.hasOverflow) Text("입력 금액이 표시 가능한 합계 범위를 넘었어요", color = MoaLogColors.Overspend, style = MaterialTheme.typography.labelSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                payerGroups(state.sheet.items, members).forEach { group ->
                    val total = group.items.mapNotNull(FixedCostCheckItem::amountWon).sumWonOrNull()
                    Surface(Modifier.weight(1f), color = Color(0xFFF1F4F0), shape = CircleShape) {
                        Column(Modifier.padding(horizontal = 6.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(group.label, style = MaterialTheme.typography.labelSmall, color = MoaLogColors.MutedInk)
                            Text(total?.let(::formatNumber) ?: "범위 초과", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun FixedCostRow(item: FixedCostCheckItem, selected: Boolean, onAction: (FixedCostCheckAction) -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(role = Role.Checkbox, onClickLabel = "${item.name} 선택 변경") { onAction(FixedCostCheckAction.ToggleItem(item.id)) }, color = Color.White, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if(selected) MoaLogColors.DeepTeal.copy(alpha=.42f) else MoaLogColors.CardBorder)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 68.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(selected, null, Modifier.semantics { contentDescription = "${item.name} 선택" })
            Surface(shape = CircleShape, color = Color(0xFFE6F2F0)) { Icon(if (item.name.contains("관리") || item.name.contains("주거")) Icons.Default.Apartment else Icons.Default.Home, null, Modifier.padding(7.dp).size(18.dp), tint = MoaLogColors.DeepTeal) }
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(item.name, fontWeight = FontWeight.SemiBold)
                if (item.amountWon == null) Text("금액 미입력", color = MoaLogColors.Overspend, style = MaterialTheme.typography.labelSmall)
            }
            Text(item.amountWon?.let { "${formatNumber(it)}원" } ?: "—", Modifier.padding(horizontal = 4.dp), fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
            IconButton({ onAction(FixedCostCheckAction.OpenEditor(item)) }, Modifier.size(48.dp)) { Icon(Icons.Default.Edit, "${item.name} 편집", Modifier.size(18.dp)) }
        }
    }
}

@Composable private fun EmptyFixedCosts(onAdd: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MoaLogColors.CardBorder)) {
        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("점검할 고정비가 없어요", fontWeight = FontWeight.Bold)
            Text("이번 달 정기 지출 항목을 추가해 주세요", Modifier.padding(top = 6.dp), color = MoaLogColors.MutedInk, style = MaterialTheme.typography.bodySmall)
            TextButton(onAdd, Modifier.heightIn(min = 48.dp)) { Text("첫 항목 추가") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun FixedCostEditorSheet(draft: FixedCostDraft, members: List<LedgerMember>, saving: Boolean, error: String?, onAction: (FixedCostCheckAction) -> Unit) {
    ModalBottomSheet(onDismissRequest = { onAction(FixedCostCheckAction.CloseEditor) }, containerColor = Color.White) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("고정비 항목 편집", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); IconButton({ onAction(FixedCostCheckAction.CloseEditor) }) { Icon(Icons.Default.Close, "편집 닫기") } } }
            item { Text("결제 주체", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold); Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf(null to "공동") + members.map { it.order to it.displayName }).forEach { (order, label) ->
                    val selected = draft.payerMemberOrder == order
                    Surface(Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.RadioButton) { onAction(FixedCostCheckAction.ChangePayer(order)) }, color = if (selected) Color.White else Color(0xFFF1F4F0), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, if (selected) MoaLogColors.DeepTeal else Color.Transparent)) {
                        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) { RadioButton(selected, null); Text(label) }
                    }
                }
            } }
            item { FixedCostField("항목명 *", draft.name, { onAction(FixedCostCheckAction.ChangeName(it)) }, "예: 주거비") }
            item { FixedCostField("월 금액 *", draft.amountInput, { onAction(FixedCostCheckAction.ChangeAmount(it)) }, "0", true) }
            error?.let { item { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, color = MaterialTheme.colorScheme.error) } }
            if (draft.id != 0L) item { TextButton({ onAction(FixedCostCheckAction.Delete) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Default.DeleteOutline, null); Text("항목 삭제", Modifier.padding(start = 6.dp)) } }
            item { Button({ onAction(FixedCostCheckAction.Save) }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !saving, shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = MoaLogColors.DeepTeal)) { Text("저장하기") } }
        }
    }
}

@Composable private fun FixedCostField(label: String, value: String, onChange: (String) -> Unit, placeholder: String, money: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(value, onChange, Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics { contentDescription = label }, placeholder = { Text(placeholder) }, singleLine = true, keyboardOptions = if (money) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default, suffix = if (money) {{ Text("원") }} else null, shape = RoundedCornerShape(10.dp))
    }
}

@Composable private fun FixedCostConflictDialog(state: FixedCostCheckUiState, members: List<LedgerMember>, onAction: (FixedCostCheckAction) -> Unit) {
    val preview = state.conflictPreview ?: return
    Dialog(onDismissRequest = { onAction(FixedCostCheckAction.DismissConflict) }) {
        Surface(Modifier.fillMaxWidth().heightIn(max = 760.dp), color = MoaLogColors.Canvas, shape = RoundedCornerShape(20.dp)) {
            Column {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ onAction(FixedCostCheckAction.DismissConflict) }) { Icon(Icons.Default.Close, "충돌 확인 닫기") }
                    Text("예정된 예산 적용", Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                    Text("2/3", Modifier.padding(end = 12.dp), style = MaterialTheme.typography.labelMedium)
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { Text("${preview.month.year}년 ${preview.month.month}월 고정비", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text("선택한 달에 이미 등록된 항목이 있습니다. 적용 방식을 확인해주세요.", Modifier.padding(top = 6.dp), color = MoaLogColors.MutedInk, style = MaterialTheme.typography.bodySmall) }
                    payerGroups(preview.rows.map { it.sourceItem }, members).filter { it.items.isNotEmpty() }.forEach { group ->
                        item { Text(group.label, color = MoaLogColors.DeepTeal, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
                        items(preview.rows.filter { it.sourceItem.payerMemberOrder == group.order }, key = { it.sourceItem.id }) { row ->
                            Surface(Modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MoaLogColors.CardBorder)) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(true, null)
                                    Column(Modifier.weight(1f)) { Text(row.sourceItem.name, fontWeight = FontWeight.SemiBold); Text("고정비", color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelSmall) }
                                    Column(horizontalAlignment = Alignment.End) { Text(row.sourceItem.amountWon?.let { "${formatNumber(it)}원" } ?: "미입력", fontWeight = FontWeight.Bold); if (row.existingItem != null) Surface(color = Color(0xFFFDECEE), shape = CircleShape) { Text("기존 항목 있음", Modifier.padding(horizontal = 7.dp, vertical = 3.dp), color = MoaLogColors.Overspend, style = MaterialTheme.typography.labelSmall) } }
                                }
                            }
                        }
                    }
                }
                Surface(color = Color.White, shadowElevation = 5.dp) { Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("충돌 ${preview.conflictCount}개 항목이 있습니다", fontWeight = FontWeight.Bold)
                    Text("선택한 ${preview.rows.size}개의 항목을 어떻게 적용할까요?", color = MoaLogColors.MutedInk, style = MaterialTheme.typography.bodySmall)
                    Button({ onAction(FixedCostCheckAction.ConfirmApply(ExistingPlanPolicy.Overwrite)) }, Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 52.dp), enabled = !state.isSaving, colors = ButtonDefaults.buttonColors(containerColor = MoaLogColors.DeepTeal)) { Text("선택한 금액으로 업데이트") }
                    TextButton({ onAction(FixedCostCheckAction.ConfirmApply(ExistingPlanPolicy.KeepExisting)) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !state.isSaving) { Text("기존 금액 유지 (건너뛰기)") }
                } }
            }
        }
    }
}

private data class PayerGroup(val order: Int?, val label: String, val items: List<FixedCostCheckItem>)
private fun payerGroups(items: List<FixedCostCheckItem>, members: List<LedgerMember>): List<PayerGroup> =
    (listOf(null to "공동") + members.map { it.order to it.displayName }).map { (order, label) -> PayerGroup(order, label, items.filter { it.payerMemberOrder == order }) }

private fun formatNullableTotal(items: List<FixedCostCheckItem>): String {
    val total = items.mapNotNull(FixedCostCheckItem::amountWon).sumWonOrNull() ?: return "합계 범위 초과"
    return if (items.any { it.amountWon == null }) "${formatNumber(total)}원 + 미입력" else "${formatNumber(total)}원"
}
private fun formatNumber(value: Long): String = value.toString().reversed().chunked(3).joinToString(",").reversed()
