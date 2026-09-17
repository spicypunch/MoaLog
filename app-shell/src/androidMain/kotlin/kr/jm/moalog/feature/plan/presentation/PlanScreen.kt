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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kr.jm.moalog.core.designsystem.MoaLogColors
import kr.jm.moalog.core.model.LedgerSetup
import kr.jm.moalog.core.model.MonthlyPlanItem
import kr.jm.moalog.core.model.PlanItemStatus
import kr.jm.moalog.core.model.PlanItemType
import kr.jm.moalog.core.model.YearMonthKey
import org.koin.compose.koinInject

@Composable
fun PlanRoute(
    setup: LedgerSetup,
    onOpenEditor: (Long?, PlanItemType, YearMonthKey) -> Unit,
    onOpenAnnual: (YearMonthKey, PlanTab) -> Unit,
    onOpenMultiMonthApply: (YearMonthKey, PlanItemType, Long?) -> Unit,
    onOpenSalaryAllocation: (YearMonthKey) -> Unit,
    onOpenFixedCostCheck: (YearMonthKey) -> Unit,
    onOpenRecords: (YearMonthKey) -> Unit,
    initialMonth: YearMonthKey? = null,
    initialTab: PlanTab? = null,
    stateHolder: PlanStateHolder = koinInject(),
) {
    val state by stateHolder.state.collectAsStateWithLifecycle()
    DisposableEffect(stateHolder) { stateHolder.start(); onDispose { stateHolder.close() } }
    LaunchedEffect(initialMonth, initialTab) {
        initialMonth?.let { stateHolder.onAction(PlanAction.SelectMonth(it)) }
        initialTab?.let { stateHolder.onAction(PlanAction.SelectTab(it)) }
    }
    PlanScreen(state, setup, stateHolder::onAction, onOpenEditor, onOpenAnnual, onOpenMultiMonthApply, onOpenSalaryAllocation, onOpenFixedCostCheck, onOpenRecords)
}

@Composable
fun PlanScreen(
    state: PlanUiState,
    setup: LedgerSetup,
    onAction: (PlanAction) -> Unit,
    onOpenEditor: (Long?, PlanItemType, YearMonthKey) -> Unit,
    onOpenAnnual: (YearMonthKey, PlanTab) -> Unit,
    onOpenMultiMonthApply: (YearMonthKey, PlanItemType, Long?) -> Unit,
    onOpenSalaryAllocation: (YearMonthKey) -> Unit,
    onOpenFixedCostCheck: (YearMonthKey) -> Unit,
    onOpenRecords: (YearMonthKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().background(MoaLogColors.Canvas),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("모아로그", color = MoaLogColors.DeepTeal, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Surface(Modifier.padding(start = 8.dp), color = Color.White, shape = CircleShape, border = BorderStroke(1.dp, MoaLogColors.CardBorder)) {
                    Text(setup.members.sortedBy { it.order }.joinToString(" · ") { it.displayName }, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "연간 보기",
                    modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { onOpenAnnual(state.month, state.selectedTab) }.padding(horizontal = 8.dp, vertical = 15.dp),
                    color = MoaLogColors.DeepTeal,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                IconButton({ onAction(PlanAction.PreviousMonth) }, Modifier.size(48.dp)) { Icon(Icons.Default.ChevronLeft, "이전 달") }
                Surface(color = Color.White, shape = CircleShape, border = BorderStroke(1.dp, MoaLogColors.CardBorder)) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = MoaLogColors.DeepTeal, modifier = Modifier.size(19.dp))
                        Text("${state.month.year}년 ${state.month.month}월", Modifier.padding(start = 7.dp), color = MoaLogColors.DeepTeal, fontWeight = FontWeight.Bold)
                    }
                }
                IconButton({ onAction(PlanAction.NextMonth) }, Modifier.size(48.dp)) { Icon(Icons.Default.ChevronRight, "다음 달") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PlanShortcut("월급 배분", "이번 달 실제 급여 배분", Icons.Default.AccountBalance, Modifier.weight(1f)) { onOpenSalaryAllocation(state.month) }
                PlanShortcut("고정비 점검", "정기 지출을 계획에 반영", Icons.Default.ReceiptLong, Modifier.weight(1f)) { onOpenFixedCostCheck(state.month) }
            }
        }
        item { SummaryCard(state) }
        item { PlanTabs(state.selectedTab, onAction) }
        if (state.isLoading) item { Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        else if (state.error != null) item { Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error) }
        else if (state.selectedTab == PlanTab.VariableExpense) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EmptyCard(
                    "기록 탭의 변동지출 합계",
                    "현재 ${if (state.hasVariableExpenseOverflow) "합계 범위 초과" else formatWon(state.variableExpenseTotalWon)} · 계획 항목은 기록에서 관리해요",
                )
                Button(
                    onClick = { onOpenRecords(state.month) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MoaLogColors.DeepTeal),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("${state.month.month}월 지출 기록 보기", fontWeight = FontWeight.SemiBold) }
            }
        }
        else if (state.visibleItems.isEmpty()) item { EmptyCard("아직 계획 항목이 없어요", "아래 버튼으로 이번 달 계획을 추가해 주세요") }
        else items(state.visibleItems, key = { it.id }) { item ->
            PlanItemRow(item, setup, onOpenEditor) {
                onOpenMultiMonthApply(state.month, item.type, item.id)
            }
        }
        if (state.selectedTab != PlanTab.VariableExpense) item {
            Button(
                onClick = { onOpenEditor(null, state.selectedTab.toItemType(), state.month) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MoaLogColors.DeepTeal),
            ) { Icon(Icons.Default.Add, null); Text("계획 항목 추가", Modifier.padding(start = 8.dp), fontWeight = FontWeight.SemiBold) }
        }
        if (state.selectedTab != PlanTab.VariableExpense && state.visibleItems.isNotEmpty()) item {
            Surface(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClickLabel = "현재 탭 계획을 여러 달에 적용") {
                    onOpenMultiMonthApply(state.month, state.selectedTab.toItemType(), null)
                },
                color = Color(0xFFF1F4F0),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ContentCopy, null, tint = MoaLogColors.DeepTeal, modifier = Modifier.size(20.dp))
                    Text("현재 ${state.selectedTab.label()} 계획을 여러 달에 적용", Modifier.padding(start = 9.dp).weight(1f), color = MoaLogColors.DeepTeal, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Default.ChevronRight, null, tint = MoaLogColors.DeepTeal)
                }
            }
        }
    }
}

@Composable private fun PlanShortcut(title:String,subtitle:String,icon:androidx.compose.ui.graphics.vector.ImageVector,modifier:Modifier,onClick:()->Unit){Surface(modifier.heightIn(min=96.dp).clickable(role=Role.Button,onClickLabel=title,onClick=onClick),color=Color.White,shape=RoundedCornerShape(16.dp),border=BorderStroke(1.dp,MoaLogColors.CardBorder)){Column(Modifier.padding(14.dp)){Icon(icon,null,tint=MoaLogColors.DeepTeal);Text(title,Modifier.padding(top=8.dp),fontWeight=FontWeight.SemiBold);Text(subtitle,color=MoaLogColors.MutedInk,style=MaterialTheme.typography.labelSmall)}}}

@Composable private fun DisabledShortcut(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Surface(modifier, color = Color.White, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MoaLogColors.CardBorder)) {
        Column(Modifier.padding(14.dp)) {
            Icon(icon, null, tint = MoaLogColors.DeepTeal)
            Text(title, Modifier.padding(top = 8.dp), fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable private fun SummaryCard(state: PlanUiState) {
    Surface(color = Color.White, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MoaLogColors.CardBorder), shadowElevation = 1.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${state.month.month}월 재정 플랜 요약", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("예정 수입", if (state.summary.hasIncomeOverflow) "합계 범위 초과" else state.summary.incomeWon?.let(::formatWon) ?: "미입력", Modifier.weight(1f))
                Metric("고정 지출", if (state.summary.hasFixedExpenseOverflow) "합계 범위 초과" else state.summary.fixedExpenseWon?.let(::formatWon) ?: "미입력", Modifier.weight(1f))
                Metric("변동 지출", if (state.summary.hasVariableExpenseOverflow) "합계 범위 초과" else formatWon(state.summary.variableExpenseWon), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("지출률", state.summary.spendingRate?.let(::formatRate) ?: "계산 불가", Modifier.weight(1f))
                Metric("저축률", state.summary.savingsRate?.let(::formatRate) ?: "계산 불가", Modifier.weight(1f))
                Metric("순저축률", state.summary.netSavingsRate?.let(::formatRate) ?: "계산 불가", Modifier.weight(1f))
            }
            if (state.summary.hasAnyOverflow) Text("금액 합계가 표시 가능한 범위를 초과했어요", color = MoaLogColors.Overspend, style = MaterialTheme.typography.labelSmall)
            else if (state.summary.hasMissingAmounts) Text("미입력 항목은 합계와 비율 계산에서 제외하거나 계산 불가로 표시했어요", color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable private fun Metric(label: String, value: String, modifier: Modifier) {
    Surface(modifier.heightIn(min = 72.dp), color = Color(0xFFF1F4F0), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(label, color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            Text(value, Modifier.padding(top = 4.dp), color = MoaLogColors.TealInk, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
    }
}

@Composable private fun PlanTabs(selected: PlanTab, onAction: (PlanAction) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PlanTab.entries.forEach { tab ->
            val chosen = tab == selected
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp).selectable(chosen, role = Role.Tab) { onAction(PlanAction.SelectTab(tab)) },
                contentAlignment = Alignment.Center,
            ) { Text(tab.label(), color = if (chosen) MoaLogColors.DeepTeal else MoaLogColors.MutedInk, style = MaterialTheme.typography.labelSmall, fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center) }
        }
    }
}

@Composable private fun PlanItemRow(
    item: MonthlyPlanItem,
    setup: LedgerSetup,
    onOpen: (Long?, PlanItemType, YearMonthKey) -> Unit,
    onApplyAcrossMonths: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp).clickable(role = Role.Button, onClickLabel = "${item.name} 수정") { onOpen(item.id, item.type, item.attributionMonth) },
        color = Color.White, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, MoaLogColors.CardBorder),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.SemiBold)
                val owner = item.ownerMemberOrder?.let { order -> setup.members.firstOrNull { it.order == order }?.displayName } ?: "공동"
                Text("${item.category} · $owner · ${if (item.status == PlanItemStatus.Confirmed) "확정" else "예상"}", color = MoaLogColors.MutedInk, style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(item.amountWon?.let(::formatWon) ?: "금액 입력", color = if (item.amountWon == null) MoaLogColors.Overspend else MoaLogColors.TealInk, fontWeight = FontWeight.Bold)
                IconButton(
                    onClick = onApplyAcrossMonths,
                    modifier = Modifier.size(48.dp),
                ) { Icon(Icons.Default.ContentCopy, "${item.name} 여러 달에 적용", tint = MoaLogColors.DeepTeal, modifier = Modifier.size(20.dp)) }
            }
        }
    }
}

@Composable private fun EmptyCard(title: String, detail: String) {
    Surface(Modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MoaLogColors.CardBorder)) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(title, fontWeight = FontWeight.SemiBold); Text(detail, Modifier.padding(top = 6.dp), color = MoaLogColors.MutedInk, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
    }
}

private fun PlanTab.toItemType() = when (this) { PlanTab.Income -> PlanItemType.Income; PlanTab.FixedExpense -> PlanItemType.FixedExpense; PlanTab.Savings -> PlanItemType.Savings; PlanTab.VariableExpense -> error("variable expenses are recorded separately") }
private fun PlanTab.label() = when (this) { PlanTab.Income -> "수입"; PlanTab.FixedExpense -> "고정지출"; PlanTab.VariableExpense -> "변동지출"; PlanTab.Savings -> "저축·투자" }
private fun formatWon(value: Long) = value.toString().reversed().chunked(3).joinToString(",").reversed() + "원"
private fun formatRate(value: Double) = "${(value * 10).toInt() / 10.0}%"
