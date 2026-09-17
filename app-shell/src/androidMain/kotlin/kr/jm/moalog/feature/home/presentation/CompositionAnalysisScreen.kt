package kr.jm.moalog.feature.home.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kr.jm.moalog.core.designsystem.MoaLogColors
import kr.jm.moalog.core.model.YearMonthKey
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

private val AnalysisBorder = Color(0xFFE0E3DF)
private val AnalysisMuted = Color(0xFF55615F)
private val AnalysisShape = RoundedCornerShape(12.dp)
private val AnalysisColors = listOf(Color(0xFF1E4E4A), Color(0xFF8EBEB9), Color(0xFFE8A17B), Color(0xFF5C7FA3), Color(0xFF887AA0))

@Composable
fun CompositionAnalysisRoute(
    initialMonth: YearMonthKey,
    initialTab: CompositionTab = CompositionTab.VariableExpense,
    onBack: () -> Unit,
    onOpenAnnual: (CompositionTab, year: Int) -> Unit,
    onOpenRecords: (categoryId: String?, month: YearMonthKey) -> Unit,
    stateHolder: CompositionAnalysisStateHolder = koinInject(parameters = { parametersOf(initialMonth, initialTab) }),
) {
    val state by stateHolder.state.collectAsStateWithLifecycle()
    DisposableEffect(stateHolder) { onDispose(stateHolder::close) }
    CompositionAnalysisScreen(state, stateHolder::onAction, onBack, onOpenAnnual, onOpenRecords)
}

@Composable
fun CompositionAnalysisScreen(
    state: CompositionAnalysisUiState,
    onAction: (CompositionAnalysisAction) -> Unit,
    onBack: () -> Unit,
    onOpenAnnual: (CompositionTab, year: Int) -> Unit,
    onOpenRecords: (String?, YearMonthKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(MoaLogColors.Canvas)) {
        AnalysisTopBar(onBack)
        AnalysisTabs(state.selectedTab) { onAction(CompositionAnalysisAction.SelectTab(it)) }
        if (state.selectedTab == CompositionTab.VariableExpense) {
            AnalysisPeriodMode(
                selected = state.selectedPeriod,
                onSelect = { onAction(CompositionAnalysisAction.SelectPeriod(it)) },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.loadError != null -> AnalysisError(state.loadError.orEmpty()) { onAction(CompositionAnalysisAction.Retry) }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { AnalysisSummaryCard(state, onAction) }
                item { DistributionCard(state) }
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("카테고리 상세 목록", Modifier.weight(1f).semantics { heading() }, color = MoaLogColors.TealInk, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(if (state.rows.isEmpty()) "입력된 항목 없음" else "총 ${state.rows.size}개 항목", color = AnalysisMuted, style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (state.rows.isEmpty()) {
                    item { EmptyAnalysis(state.selectedTab) }
                } else {
                    items(state.rows, key = CompositionAnalysisRow::id) { row ->
                        AnalysisRow(row) {
                            if (state.selectedTab == CompositionTab.VariableExpense && !state.usesAnnualPeriod) onOpenRecords(row.expenseCategoryId, state.selectedMonth)
                            else onOpenAnnual(state.selectedTab, state.selectedYear)
                        }
                    }
                }
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) {
                            if (state.selectedTab == CompositionTab.VariableExpense && !state.usesAnnualPeriod) onOpenRecords(null, state.selectedMonth)
                            else onOpenAnnual(state.selectedTab, state.selectedYear)
                        },
                        color = Color.White,
                        shape = AnalysisShape,
                        border = BorderStroke(1.dp, AnalysisBorder),
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.ReceiptLong, null, Modifier.size(19.dp), tint = MoaLogColors.DeepTeal)
                            Text(if (state.selectedTab == CompositionTab.VariableExpense && !state.usesAnnualPeriod) "${state.selectedMonth.month}월 변동지출 내역 전체보기" else "${state.selectedYear}년 계획 전체보기", Modifier.padding(start = 7.dp), color = MoaLogColors.TealInk, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalysisPeriodMode(
    selected: CompositionPeriod,
    onSelect: (CompositionPeriod) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().background(Color(0xFFECEFEA), CircleShape).padding(4.dp).selectableGroup()) {
        CompositionPeriod.entries.forEach { period ->
            val active = selected == period
            Surface(
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).selectable(selected = active, role = Role.RadioButton, onClick = { onSelect(period) }),
                color = if (active) MoaLogColors.DeepTeal else Color.Transparent,
                contentColor = if (active) Color.White else AnalysisMuted,
                shape = CircleShape,
            ) {
                Box(contentAlignment = Alignment.Center) { Text(if (period == CompositionPeriod.Monthly) "월간" else "연간", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun AnalysisTopBar(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = "뒤로가기", onClick = onBack).semantics { contentDescription = "뒤로가기" }, contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MoaLogColors.DeepTeal)
        }
        Text("구성 분석", color = MoaLogColors.DeepTeal, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AnalysisTabs(selected: CompositionTab, onSelect: (CompositionTab) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).background(Color(0xFFECEFEA), AnalysisShape).padding(4.dp).selectableGroup()) {
        CompositionTab.entries.forEach { tab ->
            val active = tab == selected
            Surface(
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).selectable(selected = active, role = Role.Tab, onClick = { onSelect(tab) }),
                color = if (active) Color.White else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
                shadowElevation = if (active) 1.dp else 0.dp,
            ) {
                Box(contentAlignment = Alignment.Center) { Text(tab.label(), color = if (active) MoaLogColors.TealInk else AnalysisMuted, style = MaterialTheme.typography.labelMedium, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium) }
            }
        }
    }
}

@Composable
private fun AnalysisSummaryCard(state: CompositionAnalysisUiState, onAction: (CompositionAnalysisAction) -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = Color.White, shape = AnalysisShape, border = BorderStroke(1.dp, AnalysisBorder), shadowElevation = 1.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PeriodButton("이전 ${if (state.usesAnnualPeriod) "연도" else "달"}") { onAction(CompositionAnalysisAction.PreviousPeriod) }
                Surface(Modifier.weight(1f), color = Color(0xFFF1F4F0), shape = CircleShape) {
                    Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CalendarToday, null, Modifier.size(17.dp), tint = MoaLogColors.DeepTeal)
                        Text(state.periodLabel, Modifier.padding(start = 6.dp), color = MoaLogColors.TealInk, fontWeight = FontWeight.Bold)
                    }
                }
                PeriodButton("다음 ${if (state.usesAnnualPeriod) "연도" else "달"}", next = true) { onAction(CompositionAnalysisAction.NextPeriod) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(state.totalLabel(), Modifier.weight(1f), color = AnalysisMuted, style = MaterialTheme.typography.labelSmall)
                if (state.memberNames.isNotBlank()) Text("♡ ${state.memberNames} 공동", color = AnalysisMuted, style = MaterialTheme.typography.labelSmall)
            }
            Text(if (state.selectedTab == CompositionTab.Savings) formatSignedWon(state.totalWon) else formatWon(state.totalWon), color = if (state.totalWon < 0) MoaLogColors.Overspend else MoaLogColors.TealInk, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            if (!state.isComplete && state.usesAnnualPeriod) Text("일부 달이 미입력되어 알려진 금액만 합산했어요", color = AnalysisMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PeriodButton(label: String, next: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = label, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(if (next) Icons.Default.ChevronRight else Icons.Default.ChevronLeft, null, tint = MoaLogColors.DeepTeal)
    }
}

@Composable
private fun DistributionCard(state: CompositionAnalysisUiState) {
    Surface(Modifier.fillMaxWidth(), color = Color.White, shape = AnalysisShape, border = BorderStroke(1.dp, AnalysisBorder), shadowElevation = 1.dp) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text("항목별 비중", Modifier.weight(1f).semantics { heading() }, color = MoaLogColors.TealInk, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("총 ${state.rows.size}개 항목", color = AnalysisMuted, style = MaterialTheme.typography.labelSmall)
            }
            HorizontalDivider(Modifier.padding(top = 10.dp), color = AnalysisBorder)
            if (state.rows.isEmpty()) {
                Text("비중을 계산할 데이터가 없어요", Modifier.fillMaxWidth().padding(vertical = 32.dp), color = AnalysisMuted, style = MaterialTheme.typography.bodyMedium)
            } else {
                if (state.selectedTab == CompositionTab.Savings && state.rows.any { it.isNegative }) {
                    NegativeSavingsChart(state.rows)
                } else {
                    val description = state.rows.joinToString(", ") { "${it.title} ${formatPercent(it.percent)}" }
                    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                        DonutChart(state.rows, description)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("가장 큰 비중", color = AnalysisMuted, style = MaterialTheme.typography.labelSmall)
                            Text(formatPercent(state.rows.first().percent), color = MoaLogColors.TealInk, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(state.rows.first().title, color = AnalysisMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                }
                Surface(color = Color(0xFFF1F4F0), shape = AnalysisShape) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Info, null, Modifier.size(18.dp), tint = MoaLogColors.DeepTeal)
                        Text(if (state.rows.any { it.isNegative }) "음수 저축은 차감액으로 합계에 반영하고 비중도 음수로 표시해요." else "항목을 누르면 관련 기록이나 연간 계획으로 이동해요.", Modifier.padding(start = 8.dp), color = AnalysisMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun NegativeSavingsChart(rows: List<CompositionAnalysisRow>) {
    val maximum = rows.maxOfOrNull { if (it.amountWon == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(it.amountWon) }?.coerceAtLeast(1L) ?: 1L
    Column(
        Modifier.fillMaxWidth().padding(vertical = 16.dp).semantics {
            contentDescription = "증감 차트. " + rows.joinToString(", ") { "${it.title} ${formatSignedWon(it.amountWon)}" }
        },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        rows.forEachIndexed { index, row ->
            val fraction = ((if (row.amountWon == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(row.amountWon)).toDouble() / maximum.toDouble()).toFloat()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(row.title, Modifier.width(72.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                Box(Modifier.weight(.22f).height(24.dp), contentAlignment = Alignment.CenterEnd) {
                    if (row.isNegative) Box(Modifier.fillMaxWidth(fraction).height(20.dp).background(MoaLogColors.OverspendSurface, RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)))
                }
                Box(Modifier.width(1.dp).height(30.dp).background(AnalysisMuted))
                Box(Modifier.weight(.78f).height(24.dp), contentAlignment = Alignment.CenterStart) {
                    if (!row.isNegative) Box(Modifier.fillMaxWidth(fraction).height(20.dp).background(AnalysisColors[index % AnalysisColors.size], RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)))
                }
                Text(formatSignedCompact(row.amountWon), Modifier.width(62.dp), color = if (row.isNegative) MoaLogColors.Overspend else AnalysisMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun DonutChart(rows: List<CompositionAnalysisRow>, description: String) {
    Canvas(Modifier.size(180.dp).semantics { contentDescription = "항목 비중 차트. $description" }) {
        val stroke = 20.dp.toPx()
        val inset = stroke / 2
        val chartSize = Size(size.width - stroke, size.height - stroke)
        drawArc(Color(0xFFF1F4F0), -90f, 360f, false, topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = chartSize, style = Stroke(stroke))
        var start = -90f
        rows.forEachIndexed { index, row ->
            val sweep = kotlin.math.abs(row.percent ?: 0.0).toFloat() * 3.6f
            if (sweep > 0f) {
                drawArc(if (row.isNegative) MoaLogColors.Overspend else AnalysisColors[index % AnalysisColors.size], start, sweep, false, topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = chartSize, style = Stroke(stroke))
                start += sweep
            }
        }
    }
}

@Composable
private fun AnalysisRow(row: CompositionAnalysisRow, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "${row.title} 상세 보기", onClick = onClick), color = Color.White, shape = AnalysisShape, border = BorderStroke(1.dp, AnalysisBorder), shadowElevation = 1.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(if (row.isNegative) MoaLogColors.Overspend else MoaLogColors.DeepTeal, CircleShape))
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                    row.supportingText?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, color = AnalysisMuted, style = MaterialTheme.typography.labelSmall) }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatWon(row.amountWon), color = if (row.isNegative) MoaLogColors.Overspend else MoaLogColors.TealInk, fontWeight = FontWeight.Bold)
                    Text(formatPercent(row.percent), color = AnalysisMuted, style = MaterialTheme.typography.labelSmall)
                }
                Icon(Icons.Default.ChevronRight, null, Modifier.padding(start = 6.dp).size(20.dp), tint = AnalysisBorder)
            }
            Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0xFFF1F4F0), CircleShape)) {
                Box(Modifier.fillMaxWidth((kotlin.math.abs(row.percent ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()).height(6.dp).background(if (row.isNegative) MoaLogColors.Overspend else MoaLogColors.DeepTeal, CircleShape))
            }
        }
    }
}

@Composable
private fun EmptyAnalysis(tab: CompositionTab) {
    Surface(Modifier.fillMaxWidth(), color = Color.White, shape = AnalysisShape, border = BorderStroke(1.dp, AnalysisBorder)) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${tab.label()} 데이터가 없어요", color = AnalysisMuted)
            Text(if (tab == CompositionTab.VariableExpense) "지출을 기록하면 이곳에서 비중을 확인할 수 있어요." else "연간 계획에 항목과 금액을 입력해 주세요.", Modifier.padding(top = 6.dp), color = AnalysisMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AnalysisError(message: String, retry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp).semantics { error(message) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Button(onClick = retry, modifier = Modifier.padding(top = 12.dp).heightIn(min = 48.dp)) { Text("다시 시도") }
    }
}

private fun CompositionTab.label() = when (this) {
    CompositionTab.FixedExpense -> "고정지출"
    CompositionTab.VariableExpense -> "변동지출"
    CompositionTab.Savings -> "저축·투자"
}

private fun CompositionAnalysisUiState.totalLabel() = when (selectedTab) {
    CompositionTab.FixedExpense -> "연간 고정지출 합계"
    CompositionTab.VariableExpense -> if (usesAnnualPeriod) "연간 변동지출 합계" else "변동지출 합계"
    CompositionTab.Savings -> "연간 저축·투자 합계"
}

private fun formatWon(value: Long): String {
    val negative = value < 0L
    val digits = if (value == Long.MIN_VALUE) "9223372036854775808" else kotlin.math.abs(value).toString()
    val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
    return "${if (negative) "-" else ""}${grouped}원"
}
private fun formatSignedWon(value: Long): String = when {
    value > 0L -> "(+) ${formatWon(value)}"
    value < 0L -> "(-) ${formatWonMagnitude(value)}"
    else -> "0원"
}
private fun formatWonMagnitude(value: Long): String {
    val digits = if (value == Long.MIN_VALUE) "9223372036854775808" else kotlin.math.abs(value).toString()
    return digits.reversed().chunked(3).joinToString(",").reversed() + "원"
}
private fun formatSignedCompact(value: Long): String {
    val sign = if (value < 0L) "-" else "+"
    val magnitude = if (value == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(value)
    return if (magnitude >= 1_000L) "$sign${magnitude / 1_000L}K" else "$sign$magnitude"
}
private fun formatPercent(value: Double?): String = value?.let { "${kotlin.math.round(it * 10.0) / 10.0}%" } ?: "—"
