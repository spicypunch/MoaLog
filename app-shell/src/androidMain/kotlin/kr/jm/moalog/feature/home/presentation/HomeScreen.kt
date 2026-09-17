package kr.jm.moalog.feature.home.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kr.jm.moalog.core.designsystem.MoaLogColors
import kr.jm.moalog.core.model.YearMonthKey
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

private val HomeCardShape = RoundedCornerShape(16.dp)
private val HomeInnerShape = RoundedCornerShape(12.dp)
private val HomeBorder = Color(0xFFE0E3DF)
private val HomeCanvas = Color(0xFFF7FAF5)
private val HomeCardInset = Color(0xFFF1F4F0)
private val HomeSecondary = Color(0xFF55615F)

@Composable
fun HomeRoute(
    initialMonth: YearMonthKey,
    onAddExpense: (YearMonthKey) -> Unit,
    onOpenOverspentExpenses: (YearMonthKey) -> Unit,
    onOpenComposition: (YearMonthKey) -> Unit,
    onOpenAnnualIncome: (YearMonthKey) -> Unit,
    onOpenAnnualExpense: (YearMonthKey) -> Unit,
    onOpenAnnualSavings: (YearMonthKey) -> Unit,
    stateHolder: HomeStateHolder = koinInject(parameters = { parametersOf(initialMonth) }),
) {
    val state by stateHolder.state.collectAsStateWithLifecycle()
    DisposableEffect(stateHolder) { onDispose(stateHolder::close) }
    HomeScreen(
        state,
        stateHolder::onAction,
        onAddExpense = { onAddExpense(state.selectedMonth) },
        onOpenOverspentExpenses = { onOpenOverspentExpenses(state.selectedMonth) },
        onOpenComposition = { onOpenComposition(state.selectedMonth) },
        onOpenAnnualIncome = { onOpenAnnualIncome(state.selectedMonth) },
        onOpenAnnualExpense = { onOpenAnnualExpense(state.selectedMonth) },
        onOpenAnnualSavings = { onOpenAnnualSavings(state.selectedMonth) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onAction: (HomeAction) -> Unit,
    onAddExpense: () -> Unit,
    onOpenOverspentExpenses: () -> Unit,
    onOpenComposition: () -> Unit,
    onOpenAnnualIncome: () -> Unit,
    onOpenAnnualExpense: () -> Unit,
    onOpenAnnualSavings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(HomeCanvas)) {
        Column(Modifier.fillMaxSize()) {
            HomeTopBar(state, onAction)
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.loadError != null -> HomeLoadError(state.loadError.orEmpty()) { onAction(HomeAction.Retry) }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    item { SavingsGoalCard(state) { onAction(HomeAction.OpenGoalEditor) } }
                    item { AnnualSummaryCard(state, onOpenAnnualIncome, onOpenAnnualExpense, onOpenAnnualSavings) }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("현황 분석 및 알림", Modifier.padding(horizontal = 4.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            CompositionCard(state.annualSummary, onOpenComposition)
                            SpendingCheckCard(state.monthlySummary, onOpenOverspentExpenses)
                        }
                    }
                    item {
                        Button(
                            onClick = onAddExpense,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            shape = HomeInnerShape,
                            colors = ButtonDefaults.buttonColors(containerColor = MoaLogColors.DeepTeal),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(22.dp))
                            Text("지출 기록하기", Modifier.padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        if (state.isGoalEditorVisible) GoalEditorSheet(state, onAction)
    }
}

@Composable
private fun HomeTopBar(state: HomeUiState, onAction: (HomeAction) -> Unit) {
    Surface(color = HomeCanvas, border = BorderStroke(0.5.dp, HomeBorder)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(state.ledgerName.ifBlank { "모아로그" }, color = MoaLogColors.DeepTeal, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Surface(Modifier.padding(start = 8.dp).weight(1f, fill = false), color = Color.White, shape = CircleShape, border = BorderStroke(1.dp, HomeBorder)) {
                Text(state.memberNames, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, color = HomeSecondary, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.weight(1f))
            IconButton48("이전 연도", { onAction(HomeAction.PreviousYear) }) { Icon(Icons.Default.ChevronLeft, null) }
            Text("${state.selectedYear}년", color = MoaLogColors.DeepTeal, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            IconButton48("다음 연도", { onAction(HomeAction.NextYear) }) { Icon(Icons.Default.ChevronRight, null) }
        }
    }
}

@Composable
private fun SavingsGoalCard(state: HomeUiState, onEdit: () -> Unit) {
    val annual = state.annualSummary
    HomeCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${state.selectedYear} 연간 저축 목표", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("우리의 첫 내 집 마련과 행복한 미래", color = HomeSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Box(Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "저축 목표 수정", onClick = onEdit).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("목표 수정", color = HomeSecondary, style = MaterialTheme.typography.labelSmall)
                    Icon(Icons.Default.Edit, null, Modifier.padding(start = 3.dp).size(16.dp), tint = HomeSecondary)
                }
            }
        }
        Surface(Modifier.padding(top = 4.dp), color = Color(0xFFECEFEA), shape = CircleShape) {
            Text("목적통장 포함", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = HomeSecondary, style = MaterialTheme.typography.labelSmall)
        }
        Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("목표 저축액", color = HomeSecondary, style = MaterialTheme.typography.labelSmall)
            Text(
                state.annualSavingsTargetWon?.let { "${formatNumber(it)}원" } ?: "아직 목표를 설정하지 않았어요",
                color = MoaLogColors.DeepTeal,
                style = if (state.annualSavingsTargetWon == null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Surface(color = HomeCardInset.copy(alpha = .7f), shape = HomeInnerShape, border = BorderStroke(1.dp, HomeBorder.copy(alpha = .6f))) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(MoaLogColors.DeepTeal, CircleShape))
                    Text("현재 모은 돈 (예상 포함)", Modifier.padding(start = 7.dp), color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    Text(amountOrPartial(annual.knownSavingsWon, annual.savingsIsComplete), color = HomeSecondary, style = MaterialTheme.typography.labelSmall)
                }
                val progress = ((annual.goalProgressRate ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
                Box(Modifier.fillMaxWidth().height(12.dp).background(HomeBorder, CircleShape)) {
                    Box(Modifier.fillMaxWidth(progress).height(12.dp).background(MoaLogColors.DeepTeal, CircleShape))
                }
                HorizontalDivider(color = HomeBorder.copy(alpha = .7f))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = Color(0xFFE4F2ED), shape = CircleShape) {
                        Text("달성률 ${formatPercent(annual.goalProgressRate)}", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = Color(0xFF255C50), style = MaterialTheme.typography.labelSmall)
                    }
                    Text(goalRemainingText(annual.goalRemainingWon), Modifier.padding(start = 7.dp).weight(1f), color = HomeSecondary, style = MaterialTheme.typography.bodySmall)
                    Text("저축률 ${formatPercent(annual.savingsRate)}", color = HomeSecondary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun AnnualSummaryCard(state: HomeUiState, onIncome: () -> Unit, onExpense: () -> Unit, onSavings: () -> Unit) {
    val annual = state.annualSummary
    HomeCard {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "연간 상세 보기", onClick = onIncome), verticalAlignment = Alignment.CenterVertically) {
            Text("연간 누적 결산", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("연간 상세 보기", color = MoaLogColors.DeepTeal, style = MaterialTheme.typography.labelMedium)
            Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = MoaLogColors.DeepTeal)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AnnualMetric("연간 수입", amountOrPartial(annual.knownIncomeWon, annual.incomeIsComplete), Modifier.weight(1f), onIncome)
            AnnualMetric("연간 지출", amountOrPartial(annual.knownTotalExpenseWon, annual.fixedExpenseIsComplete), Modifier.weight(1f), onExpense)
            AnnualMetric("순 저축률", formatPercent(annual.netSavingsRate), Modifier.weight(1f), onSavings, true)
        }
        HorizontalDivider(Modifier.padding(top = 12.dp), color = HomeBorder)
        Row(Modifier.fillMaxWidth().padding(top = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Handshake, null, Modifier.size(18.dp), tint = MoaLogColors.DeepTeal)
            Text("부부 공동 목표 기여", Modifier.padding(start = 6.dp), color = HomeSecondary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text(annual.memberContributionLabel ?: "계산 불가", color = HomeSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun AnnualMetric(label: String, value: String, modifier: Modifier, onClick: () -> Unit, accent: Boolean = false) {
    Surface(modifier.heightIn(min = 82.dp).clickable(role = Role.Button, onClickLabel = "$label 상세 보기", onClick = onClick), color = HomeCardInset, shape = HomeInnerShape) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(label, color = HomeSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Text(value, Modifier.padding(top = 4.dp), color = if (accent) MoaLogColors.DeepTeal else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun CompositionCard(summary: HomeAnnualSummary, onOpen: () -> Unit) {
    val composition = summary.composition
    HomeCard {
        Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "지출과 저축 구성 분석 열기", onClick = onOpen)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(Color(0xFFD9E5E3), HomeInnerShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.DonutLarge, null, Modifier.size(22.dp), tint = MoaLogColors.DeepTeal)
                }
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text("지출·저축 구성", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text("연간 카테고리별 비중", color = HomeSecondary, style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp), tint = Color(0xFF707977))
            }
            HorizontalDivider(Modifier.padding(top = 8.dp), color = HomeBorder.copy(alpha = .7f))
            if (!composition.isComplete) Text("12개월 계획이 모두 입력되면 정확한 비중을 보여드려요", Modifier.padding(top = 12.dp), color = HomeSecondary, style = MaterialTheme.typography.bodySmall)
            CompositionBar("고정지출", composition.fixedExpenseWon, composition.fixedExpensePercent, Color(0xFF1E4E4A))
            CompositionBar("변동지출", composition.variableExpenseWon, composition.variableExpensePercent, Color(0xFF8EBEB9))
            CompositionBar("저축·투자", composition.savingsWon, composition.savingsPercent, Color(0xFFE8A17B))
        }
    }
}

@Composable
private fun CompositionBar(label: String, amount: Long, percent: Double?, color: Color) {
    Column(Modifier.padding(top = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), color = HomeSecondary, style = MaterialTheme.typography.labelSmall)
            Text("${formatWon(amount)} · ${formatPercent(percent)}", color = HomeSecondary, style = MaterialTheme.typography.labelSmall)
        }
        Box(Modifier.fillMaxWidth().height(7.dp).background(HomeBorder, CircleShape)) {
            Box(Modifier.fillMaxWidth(((percent ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()).height(7.dp).background(color, CircleShape))
        }
    }
}

@Composable
private fun SpendingCheckCard(summary: HomeMonthlySummary, onOpen: () -> Unit) {
    val hasOverspent = summary.overspentCount > 0
    HomeCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).background(if (hasOverspent) MoaLogColors.OverspendSurface else HomeCardInset, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Warning, null, Modifier.size(20.dp), tint = if (hasOverspent) MoaLogColors.Overspend else HomeSecondary)
            }
            Text("${summary.month}월 소비 체크", Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (hasOverspent) Surface(color = MoaLogColors.OverspendSurface, shape = CircleShape) {
                Text("과소비", Modifier.padding(horizontal = 9.dp, vertical = 3.dp), color = MoaLogColors.Overspend, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
        Text(if (hasOverspent) "${formatWon(summary.overspentTotalWon)} · 사용자가 표시한 ${summary.overspentCount}건" else "과소비로 표시한 지출이 없어요", Modifier.padding(top = 10.dp), color = if (hasOverspent) MoaLogColors.Overspend else HomeSecondary, style = MaterialTheme.typography.bodySmall)
        if (hasOverspent) Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("이번 달 변동지출 ${formatWon(summary.expenseTotalWon)}", Modifier.weight(1f), color = HomeSecondary, style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) { Text("상세 확인", color = MoaLogColors.Overspend) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalEditorSheet(state: HomeUiState, onAction: (HomeAction) -> Unit) {
    ModalBottomSheet(onDismissRequest = { onAction(HomeAction.DismissGoalEditor) }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("저축 목표 수정", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${state.selectedYear}년 목표", color = HomeSecondary)
            OutlinedTextField(
                value = state.goalInput,
                onValueChange = { onAction(HomeAction.GoalInputChanged(it)) },
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "연 저축 목표 금액"
                    state.goalInputError?.let { error(it) }
                },
                label = { Text("목표액") },
                suffix = { Text("원") },
                isError = state.goalInputError != null,
                supportingText = { state.goalInputError?.let { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = !state.isSavingGoal,
                singleLine = true,
            )
            state.goalSaveError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = { onAction(HomeAction.SaveGoal) }, enabled = !state.isSavingGoal, modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = MoaLogColors.DeepTeal), shape = HomeInnerShape) {
                Text(if (state.isSavingGoal) "저장 중" else "저장")
            }
            if (state.annualSavingsTargetWon != null) TextButton(onClick = { onAction(HomeAction.RemoveGoal) }, enabled = !state.isSavingGoal, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("목표 제거", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun HomeLoadError(message: String, retry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp).semantics { error(message) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Button(onClick = retry, modifier = Modifier.padding(top = 12.dp).heightIn(min = 48.dp)) { Text("다시 시도") }
    }
}

@Composable
private fun IconButton48(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = label, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun HomeCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = Color.White, shape = HomeCardShape, border = BorderStroke(1.dp, HomeBorder), shadowElevation = 1.dp) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

private fun amountOrPartial(value: Long, complete: Boolean): String = if (complete) formatWon(value) else if (value == 0L) "미입력" else "${formatWon(value)}+"
private fun goalRemainingText(value: Long?): String = when {
    value == null -> "목표까지 계산 불가"
    value > 0L -> "목표까지 ${formatWon(value)}"
    value < 0L -> "목표를 ${formatWon(safeMagnitude(value))} 넘었어요"
    else -> "목표를 달성했어요"
}
private fun formatPercent(value: Double?): String = value?.let { "${kotlin.math.round(it * 10.0) / 10.0}%" } ?: "—"
private fun formatWon(value: Long): String = formatNumber(value) + "원"
private fun formatNumber(value: Long): String {
    val negative = value < 0
    val digits = if (value == Long.MIN_VALUE) "9223372036854775808" else kotlin.math.abs(value).toString()
    val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
    return if (negative) "-$grouped" else grouped
}
private fun safeMagnitude(value: Long): Long = if (value == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(value)
