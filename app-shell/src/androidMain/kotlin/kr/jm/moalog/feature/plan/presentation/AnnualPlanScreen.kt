package kr.jm.moalog.feature.plan.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.TableChart
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kr.jm.moalog.core.designsystem.MoaLogColors
import kr.jm.moalog.core.model.LedgerMember
import kr.jm.moalog.core.model.PlanItemType
import kr.jm.moalog.core.model.YearMonthKey
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

private val AnnualBlue = Color(0xFF2A69AC)
private val AnnualBlueSurface = Color(0xFFEBF3FA)
private val ConfirmedSurface = Color(0xFFEFF1F4)
private val ConfirmedInk = Color(0xFF4E5662)

@Composable
fun AnnualPlanRoute(
    args: AnnualPlanArgs,
    members: List<LedgerMember>,
    onBack: () -> Unit,
    onOpenMonth: (YearMonthKey, AnnualPlanSection) -> Unit,
    onOpenEditor: (Long?, PlanItemType, YearMonthKey) -> Unit,
    onOpenVariableMonth: (YearMonthKey) -> Unit,
    stateHolder: AnnualPlanStateHolder = koinInject { parametersOf(args) },
) {
    val state by stateHolder.state.collectAsStateWithLifecycle()
    DisposableEffect(stateHolder) {
        stateHolder.start()
        onDispose(stateHolder::close)
    }
    AnnualPlanScreen(state, members, stateHolder::onAction, onBack, onOpenMonth, onOpenEditor, onOpenVariableMonth)
}

@Composable
fun AnnualPlanScreen(
    state: AnnualPlanUiState,
    members: List<LedgerMember>,
    onAction: (AnnualPlanAction) -> Unit,
    onBack: () -> Unit,
    onOpenMonth: (YearMonthKey, AnnualPlanSection) -> Unit,
    onOpenEditor: (Long?, PlanItemType, YearMonthKey) -> Unit,
    onOpenVariableMonth: (YearMonthKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().background(MoaLogColors.Canvas),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로가기", tint = MoaLogColors.DeepTeal)
                }
                Text(
                    "${state.year}년 연간 한눈에 보기",
                    color = MoaLogColors.DeepTeal,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        item {
            AnnualSectionChips(state.selectedSection, onAction)
        }
        if (state.isLoading) {
            item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        } else if (state.error != null) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    Button(
                        onClick = { onAction(AnnualPlanAction.Retry) },
                        modifier = Modifier.padding(top = 12.dp).heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MoaLogColors.DeepTeal),
                    ) { Text("다시 시도") }
                }
            }
        } else {
            item { AnnualSummaryCard(state) }
            item { AnnualViewModeHeader(state, onAction) }
            if (state.viewMode == AnnualPlanViewMode.Table) {
                item { AnnualLegend() }
                item {
                    AnnualTable(
                        state = state,
                        members = members,
                        onOpenMonth = { onOpenMonth(it, state.selectedSection) },
                        onOpenEditor = onOpenEditor,
                        onOpenVariableMonth = onOpenVariableMonth,
                    )
                }
            } else {
                items(state.months, key = { it.month.toString() }) { month ->
                    AnnualMonthCard(month, state.currentMonth) { onOpenMonth(it, state.selectedSection) }
                }
            }
            item {
                Text(
                    "월을 누르면 해당 월의 계획으로 이동해요.",
                    Modifier.padding(horizontal = 20.dp),
                    color = MoaLogColors.MutedInk,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun AnnualSectionChips(selected: AnnualPlanSection, onAction: (AnnualPlanAction) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(AnnualPlanSection.entries) { section ->
            val chosen = section == selected
            Surface(
                modifier = Modifier.heightIn(min = 48.dp).selectable(
                    selected = chosen,
                    role = Role.Tab,
                    onClick = { onAction(AnnualPlanAction.SelectSection(section)) },
                ),
                color = if (chosen) MoaLogColors.DeepTeal else Color.White,
                contentColor = if (chosen) Color.White else MoaLogColors.MutedInk,
                shape = CircleShape,
                border = if (chosen) null else BorderStroke(1.dp, MoaLogColors.CardBorder),
                shadowElevation = if (chosen) 1.dp else 0.dp,
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (section == AnnualPlanSection.Income) {
                        Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(section.label(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun AnnualSummaryCard(state: AnnualPlanUiState) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        color = Color.White,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MoaLogColors.CardBorder),
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = Color(0xFFD9E5E3), modifier = Modifier.size(24.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text("₩", color = MoaLogColors.DeepTeal, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
                }
                Text(
                    "${state.year}년 ${state.selectedSection.label()} 합계",
                    Modifier.padding(start = 8.dp).weight(1f),
                    color = MoaLogColors.MutedInk,
                    style = MaterialTheme.typography.labelMedium,
                )
                Surface(shape = CircleShape, color = Color(0xFFECEFEA)) {
                    Text(
                        when {
                            state.isYearComplete -> "전체 12개월 집계"
                            state.hasKnownAmounts -> "일부 미입력 · 알려진 금액"
                            else -> "12개월 미입력"
                        },
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        color = MoaLogColors.MutedInk,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
                Text(
                    if (state.hasKnownAmounts) formatAnnualAmount(state.knownTotalWon) else "미입력",
                    color = MoaLogColors.DeepTeal,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (state.hasKnownAmounts) Text("원", Modifier.padding(start = 4.dp, bottom = 2.dp), color = MoaLogColors.DeepTeal, style = MaterialTheme.typography.titleMedium)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp).background(Color(0xFFF1F4F0), RoundedCornerShape(10.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = MoaLogColors.MutedInk, modifier = Modifier.size(18.dp))
                Text("월평균 환산액", Modifier.padding(start = 7.dp).weight(1f), color = MoaLogColors.MutedInk, style = MaterialTheme.typography.bodySmall)
                Text(
                    state.monthlyAverageWon?.let(::formatWonValue) ?: "계산 불가",
                    color = MoaLogColors.Ink,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun AnnualViewModeHeader(state: AnnualPlanUiState, onAction: (AnnualPlanAction) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.viewMode == AnnualPlanViewMode.Table) {
            Icon(Icons.Default.Swipe, contentDescription = null, tint = MoaLogColors.MutedInk, modifier = Modifier.size(18.dp))
            Text(
                "가로로 스크롤하여 12개월 전체를 볼 수 있어요",
                Modifier.padding(start = 6.dp).weight(1f),
                color = MoaLogColors.MutedInk,
                style = MaterialTheme.typography.labelSmall,
            )
        } else {
            Text("1월부터 12월까지 월별로 확인해요", Modifier.weight(1f), color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelSmall)
        }
        Surface(
            modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) {
                onAction(AnnualPlanAction.SelectViewMode(if (state.viewMode == AnnualPlanViewMode.Table) AnnualPlanViewMode.MonthlyList else AnnualPlanViewMode.Table))
            },
            color = Color(0xFFF1F4F0),
            contentColor = MoaLogColors.DeepTeal,
            shape = RoundedCornerShape(8.dp),
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (state.viewMode == AnnualPlanViewMode.Table) Icons.AutoMirrored.Filled.List else Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(17.dp))
                Text(if (state.viewMode == AnnualPlanViewMode.Table) "월별 목록 보기" else "표로 보기", Modifier.padding(start = 5.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun AnnualMonthCard(month: AnnualMonthSummary, currentMonth: YearMonthKey, onOpenMonth: (YearMonthKey) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            .clickable(role = Role.Button, onClickLabel = "${month.month.month}월 계획 열기") { onOpenMonth(month.month) },
        color = Color.White,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MoaLogColors.CardBorder),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${month.month.month}월", color = MoaLogColors.DeepTeal, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (month.month == currentMonth) {
                    Surface(Modifier.padding(start = 8.dp), color = Color(0xFFD9E5E3), shape = CircleShape) {
                        Text("당월", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = MoaLogColors.DeepTeal, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("계획 열기", color = MoaLogColors.DeepTeal, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MonthMetric("수입", month.income, Modifier.weight(1f))
                MonthMetric("고정지출", month.fixedExpense, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MonthMetric("변동지출", month.variableExpense, Modifier.weight(1f))
                MonthMetric("저축·투자", month.savings, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().background(Color(0xFFF1F4F0), RoundedCornerShape(8.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("월 잔액", Modifier.weight(1f), color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelMedium)
                Text(month.balanceWon?.let(::formatWonValue) ?: "계산 불가", color = if (month.balanceWon == null) MoaLogColors.MutedInk else MoaLogColors.DeepTeal, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MonthMetric(label: String, cell: AnnualCell, modifier: Modifier) {
    Column(modifier.background(Color(0xFFF7FAF5), RoundedCornerShape(8.dp)).padding(9.dp), horizontalAlignment = Alignment.Start) {
        Text(label, color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelSmall)
        Text(cell.amountWon?.let(::formatAnnualAmount) ?: "미입력", color = if (cell.amountWon == null) MoaLogColors.MutedInk else MoaLogColors.Ink, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1)
        if (cell.status != AnnualCellStatus.Missing) {
            Text(cell.status.label(), color = cell.status.color(), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun AnnualLegend() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LegendDot(ConfirmedSurface, "확정")
        LegendDot(AnnualBlue, "예상")
        LegendDot(MoaLogColors.CardBorder, "미입력/0원")
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, Modifier.padding(start = 4.dp), color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AnnualTable(
    state: AnnualPlanUiState,
    members: List<LedgerMember>,
    onOpenMonth: (YearMonthKey) -> Unit,
    onOpenEditor: (Long?, PlanItemType, YearMonthKey) -> Unit,
    onOpenVariableMonth: (YearMonthKey) -> Unit,
) {
    val scroll = rememberScrollState()
    val rowHeight = 72.dp
    val headerHeight = 52.dp
    val totalHeight = 58.dp
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        color = Color.White,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MoaLogColors.CardBorder),
    ) {
        Row {
            Column(Modifier.width(110.dp)) {
                TableLabelCell("${state.selectedSection.label()} 항목", headerHeight, header = true)
                state.rows.forEach { row ->
                    TableItemName(row, members, rowHeight)
                }
                TableLabelCell("월 합계", totalHeight, subtitle = "총 ${state.rows.size}개 항목", emphasized = true)
                TableLabelCell("월 잔액", totalHeight, subtitle = "수입-지출-저축", emphasized = true)
            }
            Column(Modifier.horizontalScroll(scroll)) {
                Row {
                    state.months.forEach { month -> MonthHeader(month, state, headerHeight, onOpenMonth) }
                    AnnualTotalHeader(headerHeight)
                }
                state.rows.forEach { row ->
                    Row {
                        row.cells.forEach { cell ->
                            AnnualValueCell(
                                cell = cell,
                                height = rowHeight,
                                onClick = {
                                    row.planType?.let { type -> onOpenEditor(cell.sourceItemId, type, cell.month) }
                                        ?: onOpenVariableMonth(cell.month)
                                },
                                rowName = row.name,
                            )
                        }
                        AnnualRowTotal(row, rowHeight)
                    }
                }
                Row {
                    state.selectedCells.forEach { cell -> AnnualValueCell(cell, totalHeight, { onOpenMonth(cell.month) }, total = true) }
                    AnnualSectionTotal(state, totalHeight)
                }
                Row {
                    state.months.forEach { month -> BalanceCell(month, totalHeight, onOpenMonth) }
                    AnnualBalanceTotal(state, totalHeight)
                }
            }
        }
    }
}

@Composable
private fun TableLabelCell(title: String, height: androidx.compose.ui.unit.Dp, subtitle: String? = null, header: Boolean = false, emphasized: Boolean = false) {
    Column(
        Modifier.width(110.dp).height(height).background(if (header || emphasized) Color(0xFFF1F4F0) else Color.White)
            .padding(horizontal = 12.dp).semantics { contentDescription = listOfNotNull(title, subtitle).joinToString(", ") },
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = if (emphasized) MoaLogColors.DeepTeal else MoaLogColors.MutedInk, style = MaterialTheme.typography.labelMedium, fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Medium)
        subtitle?.let { Text(it, color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun TableItemName(row: AnnualPlanRow, members: List<LedgerMember>, height: androidx.compose.ui.unit.Dp) {
    val owner = row.ownerMemberOrder?.let { order -> members.firstOrNull { it.order == order }?.displayName } ?: "공동"
    Column(
        Modifier.width(110.dp).height(height).background(Color.White).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(row.name, color = MoaLogColors.Ink, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$owner · ${row.category}", color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MonthHeader(month: AnnualMonthSummary, state: AnnualPlanUiState, height: androidx.compose.ui.unit.Dp, onOpenMonth: (YearMonthKey) -> Unit) {
    val cell = when (state.selectedSection) {
        AnnualPlanSection.Income -> month.income
        AnnualPlanSection.FixedExpense -> month.fixedExpense
        AnnualPlanSection.VariableExpense -> month.variableExpense
        AnnualPlanSection.Savings -> month.savings
    }
    val current = month.month == state.currentMonth
    Column(
        Modifier.width(96.dp).height(height)
            .background(if (current) Color(0xFFE6E9E4) else Color(0xFFF1F4F0))
            .clickable(role = Role.Button, onClickLabel = "${month.month.month}월 계획 열기") { onOpenMonth(month.month) }
            .semantics { contentDescription = "${month.month.month}월, ${cell.status.label()}" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("${month.month.month}월${if (current) " (당월)" else ""}", color = if (current) MoaLogColors.DeepTeal else MoaLogColors.Ink, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(cell.status.label(), color = cell.status.color(), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AnnualTotalHeader(height: androidx.compose.ui.unit.Dp) {
    Column(Modifier.width(128.dp).height(height).background(Color(0xFFD9E5E3)), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("연간 총합", color = MoaLogColors.DeepTeal, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Text("12개월 누적", color = MoaLogColors.DeepTeal, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AnnualValueCell(
    cell: AnnualCell,
    height: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    total: Boolean = false,
    rowName: String? = null,
) {
    Column(
        Modifier.width(96.dp).height(height).background(if (total) Color(0xFFF1F4F0) else Color.White)
            .clickable(role = Role.Button, onClickLabel = if (rowName == null) "${cell.month.month}월 계획 열기" else "$rowName ${cell.month.month}월 입력 열기", onClick = onClick)
            .semantics { contentDescription = listOfNotNull(rowName, "${cell.month.month}월", cell.amountDescription(), cell.status.label()).joinToString(", ") },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            cell.amountWon?.let(::formatAnnualAmount) ?: "미입력",
            color = if (cell.amountWon == null) MoaLogColors.MutedInk else if (cell.status == AnnualCellStatus.Estimated) AnnualBlue else MoaLogColors.Ink,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (total) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
        )
        if (!total) StatusBadge(cell.status)
    }
}

@Composable
private fun StatusBadge(status: AnnualCellStatus) {
    val background = when (status) {
        AnnualCellStatus.Estimated -> AnnualBlueSurface
        AnnualCellStatus.Partial -> Color(0xFFFDECEE)
        else -> ConfirmedSurface
    }
    Surface(Modifier.padding(top = 4.dp), color = background, shape = RoundedCornerShape(4.dp)) {
        Text(status.label(), Modifier.padding(horizontal = 5.dp, vertical = 1.dp), color = status.color(), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AnnualRowTotal(row: AnnualPlanRow, height: androidx.compose.ui.unit.Dp) {
    Column(Modifier.width(128.dp).height(height).background(Color(0xFFEAF1EF)), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(formatAnnualAmount(row.knownTotalWon), color = MoaLogColors.DeepTeal, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Text(if (row.isComplete) "원" else "원 · 일부 미입력", color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AnnualSectionTotal(state: AnnualPlanUiState, height: androidx.compose.ui.unit.Dp) {
    Box(Modifier.width(128.dp).height(height).background(MoaLogColors.DeepTeal), contentAlignment = Alignment.Center) {
        Text(if (state.hasKnownAmounts) formatAnnualAmount(state.knownTotalWon) else "미입력", color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BalanceCell(month: AnnualMonthSummary, height: androidx.compose.ui.unit.Dp, onOpenMonth: (YearMonthKey) -> Unit) {
    Box(
        Modifier.width(96.dp).height(height).background(Color(0xFFF1F4F0))
            .clickable(role = Role.Button, onClickLabel = "${month.month.month}월 계획 열기") { onOpenMonth(month.month) }
            .semantics { contentDescription = "${month.month.month}월 잔액, ${month.balanceWon?.let(::formatWonValue) ?: "계산 불가"}" },
        contentAlignment = Alignment.Center,
    ) {
        Text(month.balanceWon?.let(::formatAnnualAmount) ?: "계산 불가", color = if (month.balanceWon == null) MoaLogColors.MutedInk else MoaLogColors.DeepTeal, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AnnualBalanceTotal(state: AnnualPlanUiState, height: androidx.compose.ui.unit.Dp) {
    val balances = state.months.map { it.balanceWon }
    Box(Modifier.width(128.dp).height(height).background(Color(0xFFD9E5E3)), contentAlignment = Alignment.Center) {
        Text(
            if (balances.size == 12 && balances.all { it != null }) formatAnnualAmount(balances.sumOf { it!! }) else "계산 불가",
            color = MoaLogColors.DeepTeal,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun AnnualPlanSection.label() = when (this) {
    AnnualPlanSection.Income -> "수입"
    AnnualPlanSection.FixedExpense -> "고정지출"
    AnnualPlanSection.VariableExpense -> "변동지출"
    AnnualPlanSection.Savings -> "저축·투자"
}

private fun AnnualCellStatus.label() = when (this) {
    AnnualCellStatus.Confirmed -> "확정"
    AnnualCellStatus.Estimated -> "예상"
    AnnualCellStatus.Mixed -> "혼합"
    AnnualCellStatus.Missing -> "미입력"
    AnnualCellStatus.Partial -> "일부 미입력"
    AnnualCellStatus.Zero -> "0원"
}

private fun AnnualCellStatus.color() = when (this) {
    AnnualCellStatus.Estimated -> AnnualBlue
    AnnualCellStatus.Partial -> Color(0xFF9A3032)
    AnnualCellStatus.Missing, AnnualCellStatus.Zero -> MoaLogColors.MutedInk
    else -> ConfirmedInk
}

private fun AnnualCell.amountDescription() = amountWon?.let(::formatWonValue) ?: "미입력"
private fun formatAnnualAmount(value: Long) = value.toString().reversed().chunked(3).joinToString(",").reversed()
private fun formatWonValue(value: Long) = "${formatAnnualAmount(value)}원"
