package kr.jm.moalog.feature.assets.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kr.jm.moalog.core.designsystem.MoaLogColors
import kr.jm.moalog.core.model.AssetType
import kr.jm.moalog.core.model.AssetValueStatus
import kr.jm.moalog.core.model.YearMonthKey
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun PurposeAccountsRoute(
    initialMonth: YearMonthKey,
    onBack: (YearMonthKey) -> Unit,
    onManage: (YearMonthKey) -> Unit,
    onAdd: (YearMonthKey) -> Unit,
    onOpenAccount: (Long, YearMonthKey) -> Unit,
    onFillMissing: (Long, YearMonthKey) -> Unit,
    stateHolder: PurposeAccountsStateHolder = koinInject(parameters = { parametersOf(initialMonth) }),
) {
    val savedState = viewModel<AssetScreenSavedStateViewModel>(
        factory = remember { viewModelFactory { initializer { AssetScreenSavedStateViewModel(createSavedStateHandle()) } } },
    )
    LaunchedEffect(stateHolder) { savedState.restoredMonth()?.let { stateHolder.onAction(PurposeAccountsAction.SelectMonth(it)) } }
    val state by stateHolder.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.month, state.isLoading) { if (!state.isLoading) savedState.save(state.month) }
    DisposableEffect(stateHolder) { onDispose(stateHolder::close) }
    BackHandler { onBack(state.month) }
    PurposeAccountsScreen(
        state,
        stateHolder::onAction,
        { onBack(state.month) },
        { onManage(state.month) },
        { onAdd(state.month) },
        { onOpenAccount(it, state.month) },
        { onFillMissing(it, state.month) },
    )
}

@Composable
fun PurposeAccountsScreen(
    state: PurposeAccountsUiState,
    onAction: (PurposeAccountsAction) -> Unit,
    onBack: () -> Unit,
    onManage: () -> Unit,
    onAdd: () -> Unit,
    onOpenAccount: (Long) -> Unit,
    onFillMissing: (Long) -> Unit,
) {
    Box(Modifier.fillMaxSize().background(MoaLogColors.Linen)) {
        Column(Modifier.fillMaxSize()) {
            PurposeTopBar(onBack, onManage)
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.loadError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.loadError.orEmpty(), color = MaterialTheme.colorScheme.error)
                        TextButton({ onAction(PurposeAccountsAction.Retry) }) { Text("다시 시도") }
                    }
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 104.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item { PurposeMonthSelector(state.month, onAction) }
                    item { PurposeSummary(state) }
                    item { PurposeAccountList(state, onOpenAccount, onFillMissing) }
                    if (state.rows.isNotEmpty()) item {
                        OutlinedButton(onAdd, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MoaLogColors.Outline)) {
                            Icon(Icons.Default.AddCircle, null)
                            Spacer(Modifier.width(6.dp))
                            Text("새 목적통장 추가")
                        }
                    }
                }
            }
        }
        if (!state.isLoading && state.loadError == null) {
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = MoaLogColors.Linen.copy(alpha = .98f), shadowElevation = 4.dp) {
                Button(onAdd, Modifier.padding(horizontal = 20.dp, vertical = 12.dp).navigationBarsPadding().height(52.dp), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("목적통장 추가", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PurposeTopBar(onBack: () -> Unit, onManage: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack, Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
        Text("목적통장 현황", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        TextButton(onManage, Modifier.heightIn(min = 48.dp)) { Text("통장 관리", fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun PurposeMonthSelector(month: YearMonthKey, onAction: (PurposeAccountsAction) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(
                { onAction(PurposeAccountsAction.PreviousMonth) },
                enabled = month.year > YearMonthKey.MIN_YEAR || month.month > 1,
            ) { Icon(Icons.Default.ChevronLeft, "이전 월") }
            Row(Modifier.clickable { showPicker = true }.heightIn(min = 48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.CalendarMonth, null, Modifier.size(20.dp), tint = MoaLogColors.DeepTeal)
                Text("${month.year}년 ${month.month}월", fontWeight = FontWeight.Bold)
            }
            IconButton(
                { onAction(PurposeAccountsAction.NextMonth) },
                enabled = month.year < YearMonthKey.MAX_YEAR || month.month < 12,
            ) { Icon(Icons.Default.ChevronRight, "다음 월") }
        }
    }
    if (showPicker) AssetMonthPickerDialog(month, { showPicker = false }) {
        onAction(PurposeAccountsAction.SelectMonth(it))
        showPicker = false
    }
}

@Composable
private fun PurposeSummary(state: PurposeAccountsUiState) = PurposeSurface {
    Text("확정·예상 잔액 합계", style = MaterialTheme.typography.labelMedium, color = MoaLogColors.MutedInk)
    Text(if (state.hasTotalOverflow) "합계 범위 초과" else state.enteredTotalWon?.let { "${formatWon(it)}원" } ?: "미입력", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = if (state.enteredTotalWon == null) MoaLogColors.Outline else MoaLogColors.DeepTeal, modifier = Modifier.padding(top = 4.dp))
    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AccountBalance, null, Modifier.size(18.dp), tint = MoaLogColors.DeepTeal)
            Text(" 총 ${state.rows.size}개 통장 운용 중", style = MaterialTheme.typography.bodySmall, color = MoaLogColors.MutedInk)
            if (state.incompleteCount > 0) Text(" · 미입력 ${state.incompleteCount}개", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        val delta = state.previousMonthDeltaWon
        Text(delta?.let { "전월 대비 ${if (it >= 0) "+" else ""}${formatWon(it)}원" } ?: "전월 비교 미입력", style = MaterialTheme.typography.labelSmall, color = if (delta != null && delta < 0) MaterialTheme.colorScheme.error else MoaLogColors.DeepTeal)
    }
    Surface(Modifier.fillMaxWidth().padding(top = 12.dp), color = Color(0xFFF1F4F0), shape = RoundedCornerShape(8.dp)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Info, null, Modifier.size(16.dp), tint = MoaLogColors.DeepTeal)
            Text(" 목적통장 잔액은 일반 저축 납입액과 별개로 관리되는 보유 잔액입니다.", style = MaterialTheme.typography.labelSmall, color = MoaLogColors.MutedInk)
        }
    }
}

@Composable
private fun PurposeAccountList(state: PurposeAccountsUiState, onOpen: (Long) -> Unit, onFillMissing: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("통장별 잔액", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) { Text(state.rows.size.toString(), Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MoaLogColors.DeepTeal) }
            }
            Text("${state.month.year}.${state.month.month.toString().padStart(2, '0')} 기준", style = MaterialTheme.typography.labelSmall, color = MoaLogColors.Outline)
        }
        if (state.rows.isEmpty()) PurposeEmpty() else state.rows.forEachIndexed { index, row ->
            PurposeAccountCard(index, state.month, row, onOpen, onFillMissing)
        }
    }
}

@Composable
private fun PurposeEmpty() = PurposeSurface {
    Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(Modifier.size(56.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.AccountBalance, null, tint = MoaLogColors.DeepTeal) } }
            Text("아직 등록된 목적통장이 없어요", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("데이트·여행·경조사 통장의 월별 잔액을\n직접 기록해 보세요.", Modifier.padding(top = 5.dp), style = MaterialTheme.typography.bodySmall, color = MoaLogColors.Outline)
        }
    }
}

@Composable
private fun PurposeAccountCard(index: Int, month: YearMonthKey, row: PurposeAccountRow, onOpen: (Long) -> Unit, onFillMissing: (Long) -> Unit) = PurposeSurface(Modifier.clickable { onOpen(row.asset.id) }) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(40.dp), shape = CircleShape, color = if (row.value.status == AssetValueStatus.Missing) Color(0xFFF1F2EF) else MaterialTheme.colorScheme.secondaryContainer) {
            Box(contentAlignment = Alignment.Center) { Icon(purposeIcon(index), null, Modifier.size(20.dp), tint = if (row.value.status == AssetValueStatus.Missing) MoaLogColors.Outline else MoaLogColors.DeepTeal) }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("통장 이름", style = MaterialTheme.typography.labelSmall, color = MoaLogColors.Outline)
            Text(row.asset.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        }
        Icon(Icons.Default.ChevronRight, null, tint = MoaLogColors.Outline)
    }
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PurposeField("유형", row.asset.type.displayName, Modifier.weight(1f))
        PurposeField("평가 기준월", "${month.year}.${month.month.toString().padStart(2, '0')}", Modifier.weight(1f))
    }
    val valueAmount = row.value.amountWon
    val valueLabel = when {
        row.value.hasOverflow -> "범위 초과"
        valueAmount == null -> "미입력"
        row.value.status == AssetValueStatus.Confirmed -> "${formatWon(valueAmount)}원 · 실제"
        else -> "${formatWon(valueAmount)}원 · 예상"
    }
    PurposeField("현재 잔액", valueLabel, Modifier.fillMaxWidth().padding(top = 12.dp), emphasize = true, missing = row.value.status == AssetValueStatus.Missing)
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val monthlyChangeWon = row.monthlyChangeWon
        PurposeField(
            "다음 달 증감",
            when {
                row.hasOverflow -> "범위 초과"
                monthlyChangeWon != null -> buildString {
                    if (monthlyChangeWon >= 0) append('+')
                    append(formatWon(monthlyChangeWon)).append("원 · ")
                    append(if (row.monthlyChangeStatus == AssetValueStatus.Confirmed) "실제" else "예상")
                }
                else -> "미설정"
            },
            Modifier.weight(1f),
            accent = monthlyChangeWon != null,
        )
        PurposeField("메모", row.asset.memo?.takeIf(String::isNotBlank) ?: "없음", Modifier.weight(1f))
    }
    if (row.value.status == AssetValueStatus.Missing && !row.value.hasOverflow) {
        OutlinedButton({ onFillMissing(row.asset.id) }, Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp)) {
            Text("${row.asset.name} 잔액 입력")
        }
    }
}

@Composable
private fun PurposeField(label: String, value: String, modifier: Modifier, emphasize: Boolean = false, missing: Boolean = false, accent: Boolean = false) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MoaLogColors.Outline)
        Surface(Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(min = 48.dp), color = Color.White, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = if (emphasize) Alignment.CenterEnd else Alignment.CenterStart) {
                Text(value, style = if (emphasize) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium, fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal, color = when { missing -> MoaLogColors.Outline; accent -> Color(0xFF23826C); else -> MaterialTheme.colorScheme.onSurface }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun purposeIcon(index: Int): ImageVector = when (index % 4) {
    0 -> Icons.Default.Favorite
    1 -> Icons.Default.FlightTakeoff
    2 -> Icons.Default.Celebration
    else -> Icons.Default.Elderly
}

@Composable
private fun PurposeSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), content = content)
    }
}
