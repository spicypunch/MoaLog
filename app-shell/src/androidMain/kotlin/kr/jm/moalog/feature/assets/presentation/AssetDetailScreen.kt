package kr.jm.moalog.feature.assets.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kr.jm.moalog.core.designsystem.MoaLogColors
import kr.jm.moalog.core.model.*
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun AssetDetailRoute(
    args: AssetDetailArgs,
    onBack: (YearMonthKey) -> Unit,
    onEditAsset: (YearMonthKey) -> Unit,
    onEditMonth: (YearMonthKey) -> Unit,
    onEditRule: (YearMonthKey) -> Unit,
    onDeleted: (YearMonthKey) -> Unit,
    stateHolder: AssetDetailStateHolder = koinInject(parameters = { parametersOf(args) }),
) {
    val savedState = viewModel<AssetScreenSavedStateViewModel>(
        factory = remember { viewModelFactory { initializer { AssetScreenSavedStateViewModel(createSavedStateHandle()) } } },
    )
    LaunchedEffect(stateHolder) { savedState.restoredMonth()?.let { stateHolder.onAction(AssetDetailAction.SelectMonth(it)) } }
    val state by stateHolder.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.args.month, state.isLoading) { if (!state.isLoading) savedState.save(state.args.month) }
    DisposableEffect(stateHolder) { onDispose(stateHolder::close) }
    BackHandler { onBack(state.args.month) }
    LaunchedEffect(state.deleted) { if (state.deleted) onDeleted(state.args.month) }
    AssetDetailScreen(
        state,
        stateHolder::onAction,
        { onBack(state.args.month) },
        { onEditAsset(state.args.month) },
        onEditMonth,
        { onEditRule(state.args.month) },
    )
}

@Composable
fun AssetDetailScreen(
    state: AssetDetailUiState,
    onAction: (AssetDetailAction) -> Unit,
    onBack: () -> Unit,
    onEditAsset: () -> Unit,
    onEditMonth: (YearMonthKey) -> Unit,
    onEditRule: () -> Unit,
) {
    var confirmAssetDelete by remember { mutableStateOf(false) }
    var confirmValuationDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(MoaLogColors.Linen)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack, Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
            Text(if (state.asset?.kind == AssetKind.PurposeAccount) "목적통장 상세" else "자산 항목 상세", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextButton(onEditAsset, Modifier.heightIn(min = 48.dp), enabled = state.asset != null) { Text("수정") }
        }
        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.loadError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(state.loadError.orEmpty()); TextButton({ onAction(AssetDetailAction.Retry) }) { Text("다시 시도") } } }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { AssetHeadline(state, onAction) }
                item { DetailTrendCard(state.chart) }
                item { GrowthRuleCard(state.rule, onEditRule) { onAction(AssetDetailAction.DeleteGrowthRule) } }
                item { MonthlyRecordsCard(state.records, onEditMonth) }
                item { Button({ onEditMonth(state.args.month) }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(10.dp)) { Icon(Icons.Default.EditCalendar, null); Spacer(Modifier.width(6.dp)); Text("월별 잔액 직접 입력 / 수정", fontWeight = FontWeight.Bold) } }
                if (state.value.status == AssetValueStatus.Confirmed) item { OutlinedButton({ confirmValuationDelete = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("이달 확정 평가액 삭제") } }
                item { OutlinedButton({ confirmAssetDelete = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("자산 항목 삭제") } }
                state.mutationError?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error); TextButton({ onAction(AssetDetailAction.RetryMutation) }) { Text("다시 시도") } } }
            }
        }
    }
    if (confirmAssetDelete) AlertDialog(onDismissRequest = { confirmAssetDelete = false }, title = { Text("자산 항목을 삭제할까요?") }, text = { Text("월별 평가액과 예상 증가 규칙도 함께 삭제됩니다.") }, confirmButton = { TextButton({ confirmAssetDelete = false; onAction(AssetDetailAction.DeleteAsset) }) { Text("삭제") } }, dismissButton = { TextButton({ confirmAssetDelete = false }) { Text("취소") } })
    if (confirmValuationDelete) AlertDialog(onDismissRequest = { confirmValuationDelete = false }, title = { Text("이달 평가액을 삭제할까요?") }, confirmButton = { TextButton({ confirmValuationDelete = false; onAction(AssetDetailAction.DeleteSelectedValuation) }) { Text("삭제") } }, dismissButton = { TextButton({ confirmValuationDelete = false }) { Text("취소") } })
}

@Composable private fun AssetHeadline(state: AssetDetailUiState, onAction: (AssetDetailAction) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(state.asset?.name.orEmpty(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(state.asset?.ownerMemberOrder?.let { "개인 소유자 ${it}번" } ?: "공동 소유", style = MaterialTheme.typography.labelSmall, color = MoaLogColors.Outline)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                { onAction(AssetDetailAction.PreviousMonth) },
                enabled = state.args.month.year > YearMonthKey.MIN_YEAR || state.args.month.month > 1,
            ) { Icon(Icons.Default.ChevronLeft, "이전 월") }
            Text("${state.args.month.year}년 ${state.args.month.month}월 기준", Modifier.clickable { showPicker = true }.heightIn(min = 48.dp).wrapContentHeight(), fontWeight = FontWeight.SemiBold)
            IconButton(
                { onAction(AssetDetailAction.NextMonth) },
                enabled = state.args.month.year < YearMonthKey.MAX_YEAR || state.args.month.month < 12,
            ) { Icon(Icons.Default.ChevronRight, "다음 월") }
        }
        Text(if (state.value.hasOverflow) "범위 초과" else state.value.amountWon?.let { "${formatWon(it)}원" } ?: "미입력", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MoaLogColors.DeepTeal)
        Text(when(state.value.status) { AssetValueStatus.Confirmed -> "직접 입력한 평가액"; AssetValueStatus.Estimated -> "예상 증가 설정으로 계산한 금액"; AssetValueStatus.Missing -> "이 달의 평가액이 없습니다" }, style = MaterialTheme.typography.labelSmall, color = MoaLogColors.Outline)
        state.asset?.memo?.takeIf { it.isNotBlank() }?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MoaLogColors.MutedInk) }
        state.previousMonthDeltaWon?.let { delta -> Text("전월 대비 ${if (delta >= 0) "+" else ""}${formatWon(delta)}원", style = MaterialTheme.typography.labelMedium, color = if (delta < 0) MaterialTheme.colorScheme.error else Color(0xFF23826C)) }
    }
    if (showPicker) AssetMonthPickerDialog(state.args.month, { showPicker = false }) { onAction(AssetDetailAction.SelectMonth(it)); showPicker = false }
}

@Composable private fun DetailTrendCard(points: List<AssetDetailPoint>) = DetailSurface {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column { Text("자산 추이 및 예상", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium); Text("최근 3개월 · 다음 2개월", style = MaterialTheme.typography.labelSmall, color = MoaLogColors.Outline) }; Row { StatusDot(MoaLogColors.DeepTeal, "확정"); Spacer(Modifier.width(8.dp)); StatusDot(Color(0xFF2A69AC), "예상") } }
    Spacer(Modifier.height(16.dp))
    val description = points.joinToString { point -> "${point.month.year}년 ${point.month.month}월 ${if (point.value.hasOverflow) "범위 초과" else point.value.amountWon?.let { "${formatWon(it)}원" } ?: "미입력"} ${if (point.value.status == AssetValueStatus.Confirmed) "확정" else if (point.value.status == AssetValueStatus.Estimated) "예상" else "미입력"}" }
    Box(Modifier.fillMaxWidth().semantics { contentDescription = description }) {
        val amounts = points.mapNotNull { it.value.amountWon }
        Canvas(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 8.dp, vertical = 12.dp)) {
            if (amounts.isNotEmpty()) {
                val min = amounts.min(); val max = amounts.max(); val span = (max.toDouble() - min.toDouble()).coerceAtLeast(1.0)
                var previous: Pair<Offset, AssetValueStatus>? = null
                points.forEachIndexed { index, point ->
                    val amount = point.value.amountWon
                    val current = amount?.let { Offset(size.width * index / (points.size - 1).coerceAtLeast(1), size.height - (((it.toDouble() - min.toDouble()) / span) * size.height).toFloat()) }
                    if (current != null) {
                        previous?.let { (start, status) -> drawLine(if (status == AssetValueStatus.Estimated || point.value.status == AssetValueStatus.Estimated) Color(0xFF2A69AC) else MoaLogColors.DeepTeal, start, current, strokeWidth = 4f) }
                        previous = current to point.value.status
                    } else previous = null
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 52.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) { points.forEach { point -> Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(if (point.value.hasOverflow) "!" else point.value.amountWon?.let { compactWon(it) } ?: "-", style = MaterialTheme.typography.labelSmall, color = if(point.value.status == AssetValueStatus.Estimated) Color(0xFF2A69AC) else MoaLogColors.DeepTeal); Box(Modifier.size(if(point.month == points.getOrNull(2)?.month) 12.dp else 9.dp).background(if(point.value.status == AssetValueStatus.Estimated) Color(0xFF2A69AC) else MoaLogColors.DeepTeal, CircleShape)); Text("${point.month.month}월${if(point.value.status == AssetValueStatus.Estimated) " 예상" else ""}", style = MaterialTheme.typography.labelSmall, color = MoaLogColors.MutedInk) } } }
    }
}

@Composable private fun StatusDot(color: Color, label: String) { Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(7.dp).background(color, CircleShape)); Text(" $label", style = MaterialTheme.typography.labelSmall) } }

@Composable private fun GrowthRuleCard(rule: AssetGrowthRule?, onEdit: () -> Unit, onDelete: () -> Unit) = DetailSurface {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.AutoGraph, null, tint = MoaLogColors.DeepTeal); Text(" 예상 증가 자동 계산 설정", fontWeight = FontWeight.Bold) }; Switch(rule != null, onCheckedChange = { enabled -> if (enabled) onEdit() else onDelete() }) }
    if (rule == null) Text("아직 설정된 예상 증가 규칙이 없어요", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = MoaLogColors.Outline) else {
        val firstForecastMonth = rule.startMonth.plusMonthsOrNull(1)
        val lastForecastMonth = rule.startMonth.plusMonthsOrNull(rule.durationMonths)
        Surface(Modifier.fillMaxWidth().padding(top = 12.dp), color = Color(0xFFF1F4F0), shape = RoundedCornerShape(10.dp)) { Column(Modifier.padding(12.dp)) { Text("적용 규칙", style = MaterialTheme.typography.labelSmall, color = MoaLogColors.Outline); Text("기준 금액(${rule.startMonth.month}월)에 매월 ${if (rule.monthlyIncreaseWon >= 0) "+" else ""}${formatWon(rule.monthlyIncreaseWon)}원 반영", fontWeight = FontWeight.Bold); Text("${formatRuleMonth(firstForecastMonth)} ~ ${formatRuleMonth(lastForecastMonth)} (${rule.durationMonths}개월간)", style = MaterialTheme.typography.labelSmall, color = MoaLogColors.MutedInk) } }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onEdit, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("규칙 수정") }; OutlinedButton(onEdit, Modifier.weight(1f).heightIn(min = 48.dp)) { Icon(Icons.Default.Visibility, null); Text(" 미리보기") } }
        Text("직접 입력한 확정 잔액이 있으면 예상 금액보다 우선합니다.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = MoaLogColors.Outline)
    }
}

@Composable private fun MonthlyRecordsCard(records: List<AssetDetailPoint>, onEdit: (YearMonthKey) -> Unit) = DetailSurface {
    Text("월별 잔액 기록", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    records.forEach { record -> Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).clickable { onEdit(record.month) }, verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(28.dp).background(if(record.value.status == AssetValueStatus.Confirmed) MaterialTheme.colorScheme.secondaryContainer else Color(0xFFF1F4F0), CircleShape), contentAlignment = Alignment.Center) { Text(record.month.month.toString(), style = MaterialTheme.typography.labelSmall) }; Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text("${record.month.year}년 ${record.month.month}월", fontWeight = FontWeight.SemiBold); Text(if(record.value.status == AssetValueStatus.Confirmed) "확정" else if(record.value.status == AssetValueStatus.Estimated) "예상" else "미입력", style = MaterialTheme.typography.labelSmall, color = MoaLogColors.Outline) }; Text(record.value.amountWon?.let { "${formatWon(it)}원" } ?: "-", fontWeight = FontWeight.Bold); Icon(Icons.Default.ChevronRight, null, tint = MoaLogColors.Outline) }
    }
}

@Composable private fun DetailSurface(content: @Composable ColumnScope.() -> Unit) { Surface(Modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { Column(Modifier.padding(14.dp), content = content) } }
private fun compactWon(value: Long) = if(value >= 10_000) "${formatWon(value / 10_000)}만" else formatWon(value)
private fun formatRuleMonth(month: YearMonthKey?) = month?.let { "${it.year}.${it.month.toString().padStart(2, '0')}" } ?: "지원 범위 밖"
