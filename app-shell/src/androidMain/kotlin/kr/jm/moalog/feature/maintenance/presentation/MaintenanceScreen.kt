package kr.jm.moalog.feature.maintenance.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kr.jm.moalog.core.designsystem.MoaLogColors
import kr.jm.moalog.core.model.YearMonthKey
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun MaintenanceOverviewRoute(
    args: MaintenanceOverviewArgs,
    onBack: () -> Unit,
    onEdit: (YearMonthKey) -> Unit,
    stateHolder: MaintenanceOverviewStateHolder = koinInject(parameters = { parametersOf(args) }),
) {
    val state by stateHolder.state.collectAsStateWithLifecycle()
    DisposableEffect(stateHolder) { onDispose(stateHolder::close) }
    MaintenanceOverviewScreen(state, stateHolder::onAction, onBack, onEdit)
}

@Composable
fun MaintenanceOverviewScreen(
    state: MaintenanceOverviewUiState,
    onAction: (MaintenanceOverviewAction) -> Unit,
    onBack: () -> Unit,
    onEdit: (YearMonthKey) -> Unit,
) {
    Box(Modifier.fillMaxSize().background(MoaLogColors.Linen)) {
        Column(Modifier.fillMaxSize()) {
            MaintenanceTopBar("관리비 현황", onBack)
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.loadError != null -> LoadError(state.loadError.orEmpty()) { onAction(MaintenanceOverviewAction.Retry) }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item { MonthSelector(state.month, onAction) }
                    item { MaintenanceTotalCard(state.totalWon, state.enteredCount) }
                    item { OverviewTabs(state.selectedTab, onAction) }
                    if (state.selectedTab == MaintenanceOverviewTab.Detail) {
                        item { MaintenanceDetailGroups(state.rows) }
                    } else {
                        item { MaintenanceComparison(state) }
                    }
                }
            }
        }
        if (!state.isLoading && state.loadError == null) {
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = MoaLogColors.Linen.copy(alpha = .98f), shadowElevation = 4.dp) {
                Button(
                    onClick = { onEdit(state.month) },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).navigationBarsPadding().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(if (state.enteredCount == 0) "이번 달 관리비 입력" else "관리비 수정", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun MaintenanceTopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack, Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
        Text(title, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        IconButton({}, Modifier.size(48.dp)) { Icon(Icons.Default.HelpOutline, "관리비 도움말", tint = MoaLogColors.Outline) }
    }
}

@Composable
private fun MonthSelector(month: YearMonthKey, onAction: (MaintenanceOverviewAction) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        IconButton({ onAction(MaintenanceOverviewAction.PreviousMonth) }) { Icon(Icons.Default.ChevronLeft, "이전 청구월") }
        Text("${month.year}년 ${month.month}월", Modifier.widthIn(min = 150.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        IconButton({ onAction(MaintenanceOverviewAction.NextMonth) }) { Icon(Icons.Default.ChevronRight, "다음 청구월") }
    }
}

@Composable
private fun MaintenanceTotalCard(totalWon: Long?, enteredCount: Int) = MaintenanceSurface {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("이번 달 관리비 총액", color = MoaLogColors.MutedInk)
        Text(
            totalWon?.let { "${formatMaintenanceWon(it)}원" } ?: "미입력",
            Modifier.padding(top = 5.dp),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = if (totalWon == null) MoaLogColors.Outline else MaterialTheme.colorScheme.onSurface,
        )
        Text("21개 중 ${enteredCount}개 입력", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, color = MoaLogColors.Outline)
    }
}

@Composable
private fun OverviewTabs(selected: MaintenanceOverviewTab, onAction: (MaintenanceOverviewAction) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        listOf(MaintenanceOverviewTab.Detail to "상세", MaintenanceOverviewTab.Comparison to "월별 비교").forEach { (tab, label) ->
            Column(Modifier.weight(1f).heightIn(min = 52.dp).clickable { onAction(MaintenanceOverviewAction.SelectTab(tab)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, Modifier.padding(vertical = 14.dp), color = if (selected == tab) MoaLogColors.DeepTeal else MoaLogColors.Outline, fontWeight = FontWeight.Bold)
                HorizontalDivider(thickness = if (selected == tab) 2.dp else 1.dp, color = if (selected == tab) MoaLogColors.DeepTeal else MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun MaintenanceDetailGroups(rows: List<MaintenanceOverviewRow>) {
    val groups = listOf(
        Triple("공용·관리비", rows.take(11), true),
        Triple("세대 사용료", rows.drop(11).take(8), false),
        Triple("차감 및 기타", rows.drop(19).take(2), false),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        groups.forEach { (title, items, initiallyExpanded) -> MaintenanceDetailGroup(title, items, initiallyExpanded) }
    }
}

@Composable
private fun MaintenanceDetailGroup(title: String, rows: List<MaintenanceOverviewRow>, initiallyExpanded: Boolean) {
    var expanded by remember(title) { mutableStateOf(initiallyExpanded) }
    val entered = rows.mapNotNull { it.amountWon }
    val subtotal = entered.takeIf { it.isNotEmpty() }?.sum()
    MaintenanceSurface(Modifier.clickable { expanded = !expanded }, padding = PaddingValues(0.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("$title (${rows.size}건)", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
            Text(subtotal?.let { "${formatMaintenanceWon(it)}원" } ?: "미입력", fontWeight = FontWeight.Bold, color = if (subtotal != null && subtotal < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "접기" else "펼치기", tint = MoaLogColors.Outline)
        }
        if (expanded) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                rows.forEachIndexed { index, row ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(row.label, Modifier.weight(1f), color = MoaLogColors.MutedInk)
                        val amountWon = row.amountWon
                        Text(amountWon?.let { "${formatMaintenanceWon(it)}원" } ?: "미입력", fontWeight = FontWeight.Medium, color = when { amountWon == null -> MoaLogColors.Outline; amountWon < 0 -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurface })
                    }
                    if (index < rows.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
                }
            }
        }
    }
}

@Composable
private fun MaintenanceComparison(state: MaintenanceOverviewUiState) {
    val values = state.comparisonPoints.mapNotNull { it.totalWon }
    val max = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val description = state.comparisonPoints.joinToString { "${it.month.year}년 ${it.month.month}월 ${it.totalWon?.let(::formatMaintenanceWon) ?: "미입력"}원" }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MaintenanceSurface {
            Text("최근 ${state.comparisonPoints.size}개월 관리비", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Canvas(Modifier.fillMaxWidth().height(170.dp).padding(top = 22.dp).semantics { contentDescription = description }) {
                repeat(3) { line -> drawLine(Color(0xFFEAE6DF), Offset(0f, size.height * line / 3f), Offset(size.width, size.height * line / 3f), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f))) }
                val count = state.comparisonPoints.size.coerceAtLeast(1)
                val slot = size.width / count
                state.comparisonPoints.forEachIndexed { index, point ->
                    point.totalWon?.let { amount ->
                        val height = size.height * (amount.coerceAtLeast(0L).toFloat() / max.toFloat()) * .8f
                        drawRect(if (point.month == state.month) MoaLogColors.DeepTeal else Color(0xFFDCECE8), Offset(index * slot + slot * .22f, size.height - height), androidx.compose.ui.geometry.Size(slot * .56f, height))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) { state.comparisonPoints.forEach { Text("${it.month.month}월", style = MaterialTheme.typography.labelSmall, color = if (it.month == state.month) MoaLogColors.DeepTeal else MoaLogColors.Outline) } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("월별 상세 내역", style = MaterialTheme.typography.labelMedium, color = MoaLogColors.MutedInk)
            Text("${state.comparisonStart.year}.${state.comparisonStart.month.toString().padStart(2, '0')} ~ ${state.comparisonEnd.year}.${state.comparisonEnd.month.toString().padStart(2, '0')}", style = MaterialTheme.typography.labelSmall, color = MoaLogColors.Outline)
        }
        MaintenanceSurface(padding = PaddingValues(0.dp)) {
            state.comparisonPoints.reversed().forEachIndexed { index, point ->
                val previous = state.comparisonPoints.getOrNull(state.comparisonPoints.indexOf(point) - 1)?.totalWon
                val totalWon = point.totalWon
                val delta = if (totalWon != null && previous != null) totalWon - previous else null
                Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(Modifier.size(40.dp), shape = CircleShape, color = if (point.month == state.month) MaterialTheme.colorScheme.secondaryContainer else Color(0xFFF5F3ED)) { Box(contentAlignment = Alignment.Center) { Text("${point.month.month}월", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) } }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("${point.month.year}년", style = MaterialTheme.typography.labelSmall, color = MoaLogColors.Outline)
                        Text(point.totalWon?.let { "${formatMaintenanceWon(it)}원" } ?: "미입력", fontWeight = FontWeight.Bold)
                    }
                    Text(delta?.let { "${if (it > 0) "+" else ""}${formatMaintenanceWon(it)}원" } ?: "비교 불가", style = MaterialTheme.typography.labelMedium, color = when { delta == null -> MoaLogColors.Outline; delta > 0 -> MaterialTheme.colorScheme.error; else -> Color(0xFF2A69AC) })
                }
                if (index < state.comparisonPoints.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
fun MaintenanceEditorRoute(
    args: MaintenanceEditorArgs,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    stateHolder: MaintenanceEditorStateHolder = koinInject(parameters = { parametersOf(args) }),
) {
    val state by stateHolder.state.collectAsStateWithLifecycle()
    DisposableEffect(stateHolder) { onDispose(stateHolder::close) }
    LaunchedEffect(state.completed) { if (state.completed) onSaved() }
    MaintenanceEditorScreen(state, stateHolder::onAction, onBack)
}

@Composable
fun MaintenanceEditorScreen(state: MaintenanceEditorUiState, onAction: (MaintenanceEditorAction) -> Unit, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MoaLogColors.Linen)) {
        Column(Modifier.fillMaxSize()) {
            MaintenanceTopBar("관리비 입력", onBack)
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.loadError != null -> LoadError(state.loadError.orEmpty()) { onAction(MaintenanceEditorAction.Retry) }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 112.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item {
                        Text("${state.month.year}년 ${state.month.month}월", style = MaterialTheme.typography.labelMedium, color = MoaLogColors.MutedInk)
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("입력 중 합계", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(state.calculatedTotalWon?.let { "${formatMaintenanceWon(it)}원" } ?: "미입력", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MoaLogColors.DeepTeal)
                        }
                        Text("21개 중 ${state.enteredCount}개 입력", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = MoaLogColors.Outline)
                    }
                    item { EditorGroup("공용·관리비", state.fields.take(11), initiallyExpanded = true, onAction) }
                    item { EditorGroup("세대 사용료", state.fields.drop(11).take(8), initiallyExpanded = false, onAction) }
                    item { EditorGroup("차감 및 기타", state.fields.drop(19).take(2), initiallyExpanded = false, onAction) }
                    state.persistenceError?.let { item { Text(it, Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) } }
                }
            }
        }
        if (!state.isLoading && state.loadError == null) {
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = Color.White.copy(alpha = .98f), shadowElevation = 5.dp) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.widthIn(min = 105.dp)) {
                        Text("총 관리비", style = MaterialTheme.typography.labelSmall, color = MoaLogColors.MutedInk)
                        Text(state.calculatedTotalWon?.let { "${formatMaintenanceWon(it)}원" } ?: "미입력", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Button({ onAction(MaintenanceEditorAction.Save) }, Modifier.weight(1f).height(52.dp), enabled = !state.isSaving, shape = RoundedCornerShape(12.dp)) {
                        if (state.isSaving) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text("저장", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorGroup(title: String, fields: List<MaintenanceEditorField>, initiallyExpanded: Boolean, onAction: (MaintenanceEditorAction) -> Unit) {
    var expanded by remember(title) { mutableStateOf(initiallyExpanded) }
    val entered = fields.mapNotNull { it.text.replace(",", "").toLongOrNull() }
    val subtotal = entered.takeIf { it.isNotEmpty() }?.sum()
    MaintenanceSurface(padding = PaddingValues(0.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 66.dp).clickable { expanded = !expanded }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Bold); Text(subtotal?.let { "소계 ${formatMaintenanceWon(it)}원" } ?: "모두 미입력", style = MaterialTheme.typography.labelMedium, color = MoaLogColors.MutedInk) }
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "접기" else "펼치기", tint = MoaLogColors.Outline)
        }
        if (expanded) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                fields.forEachIndexed { index, field -> MaintenanceAmountField(field, index == fields.lastIndex, onAction) }
            }
        }
    }
}

@Composable
private fun MaintenanceAmountField(field: MaintenanceEditorField, isLast: Boolean, onAction: (MaintenanceEditorAction) -> Unit) {
    val focus = LocalFocusManager.current
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(field.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            if (field.label == "관리비차감") Text("차감액은 마이너스(-)로 입력", style = MaterialTheme.typography.labelSmall, color = MoaLogColors.MutedInk)
        }
        OutlinedTextField(
            value = field.text,
            onValueChange = { onAction(MaintenanceEditorAction.AmountChanged(field.key, it)) },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            suffix = { Text("원") },
            singleLine = true,
            isError = field.error != null,
            supportingText = field.error?.let { error -> { Text(error) } },
            keyboardOptions = KeyboardOptions(keyboardType = if (field.label == "관리비차감") KeyboardType.Number else KeyboardType.Number, imeAction = if (isLast) ImeAction.Done else ImeAction.Next),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            shape = RoundedCornerShape(12.dp),
        )
    }
}

@Composable
private fun LoadError(message: String, retry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = MaterialTheme.colorScheme.error)
            TextButton(retry) { Text("다시 시도") }
        }
    }
}

@Composable
private fun MaintenanceSurface(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(16.dp), content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(padding), content = content)
    }
}

internal fun formatMaintenanceWon(value: Long): String {
    val text = value.toString()
    val negative = text.startsWith('-')
    val digits = text.removePrefix("-")
    val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
    return if (negative) "-$grouped" else grouped
}
