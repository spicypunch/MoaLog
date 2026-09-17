package kr.jm.moalog.feature.assets.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kr.jm.moalog.core.designsystem.MoaLogColors
import kr.jm.moalog.core.model.AssetType
import kr.jm.moalog.core.model.LedgerMember
import kr.jm.moalog.core.model.YearMonthKey
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

internal class AssetScreenSavedStateViewModel(private val handle: SavedStateHandle) : ViewModel() {
    fun restoredMonth(): YearMonthKey? = runCatching {
        val year = handle.get<Int>("asset.screen.year") ?: return@runCatching null
        val month = handle.get<Int>("asset.screen.month") ?: return@runCatching null
        YearMonthKey(year, month)
    }.getOrNull()
    fun restoredSort(): AssetSort? = handle.get<String>("asset.screen.sort")?.let { runCatching { AssetSort.valueOf(it) }.getOrNull() }
    fun save(month: YearMonthKey, sort: AssetSort? = null) {
        handle["asset.screen.year"] = month.year
        handle["asset.screen.month"] = month.month
        if (sort != null) handle["asset.screen.sort"] = sort.name
    }
}

@Composable
fun AssetsRoute(
    initialMonth: YearMonthKey,
    members: List<LedgerMember>,
    onAddAsset: (YearMonthKey) -> Unit,
    onOpenAsset: (Long, YearMonthKey) -> Unit,
    onOpenPurposeAccounts: (YearMonthKey) -> Unit,
    onOpenSavings: (YearMonthKey) -> Unit,
) {
    val holder: AssetsStateHolder = koinInject(parameters = { parametersOf(initialMonth) })
    val savedState = viewModel<AssetScreenSavedStateViewModel>(
        factory = remember { viewModelFactory { initializer { AssetScreenSavedStateViewModel(createSavedStateHandle()) } } },
    )
    val state by holder.state.collectAsStateWithLifecycle()
    LaunchedEffect(holder) {
        savedState.restoredMonth()?.let { holder.onAction(AssetsAction.SelectMonth(it)) }
        if (savedState.restoredSort() == AssetSort.Name) holder.onAction(AssetsAction.ToggleSort)
    }
    LaunchedEffect(state.month, state.sort, state.isLoading) {
        if (!state.isLoading) savedState.save(state.month, state.sort)
    }
    DisposableEffect(holder) { onDispose(holder::close) }
    AssetsScreen(
        state,
        members,
        holder::onAction,
        { onAddAsset(state.month) },
        { onOpenAsset(it, state.month) },
        { onOpenPurposeAccounts(state.month) },
        { onOpenSavings(state.month) },
    )
}

@Composable
fun AssetsScreen(
    state: AssetsUiState,
    members: List<LedgerMember>,
    onAction: (AssetsAction) -> Unit,
    onAddAsset: () -> Unit,
    onOpenAsset: (Long) -> Unit,
    onOpenPurposeAccounts: () -> Unit,
    onOpenSavings: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MoaLogColors.Linen),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { AssetsHeader(state.month, onAction) }
        when {
            state.isLoading -> item { Box(Modifier.fillParentMaxHeight(.7f), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            state.loadError != null -> item {
                Column(Modifier.fillParentMaxHeight(.7f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(state.loadError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { onAction(AssetsAction.Retry) }) { Text("다시 시도") }
                }
            }
            state.isEmpty -> {
                item { AssetEmptyContent(onAddAsset) }
                item { ShortcutCards(state, onOpenPurposeAccounts, onOpenSavings) }
            }
            else -> {
                item { TotalAssetCard(state) }
                item { TrendCard(state.trend) }
                item { ShortcutCards(state, onOpenPurposeAccounts, onOpenSavings) }
                item { AssetListCard(state.rows, members, onOpenAsset, onAddAsset) { onAction(AssetsAction.ToggleSort) } }
                item { Button(onClick = onAddAsset, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.AddCircle, null); Spacer(Modifier.width(6.dp)); Text("+ 자산 항목 추가", fontWeight = FontWeight.Bold) } }
            }
        }
    }
}

@Composable private fun AssetsHeader(month: YearMonthKey, onAction: (AssetsAction) -> Unit) {
    var showMonthDialog by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(Modifier.size(32.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) { Box(contentAlignment = Alignment.Center) { Text("부부", style = MaterialTheme.typography.labelSmall, color = MoaLogColors.DeepTeal) } }
            Text("모아로그", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MoaLogColors.DeepTeal)
        }
        TextButton(onClick = { showMonthDialog = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("${month.year}.${month.month.toString().padStart(2, '0')}", style = MaterialTheme.typography.labelMedium, color = MoaLogColors.DeepTeal)
            Icon(Icons.Default.ArrowDropDown, "조회 월 선택", tint = MoaLogColors.DeepTeal)
        }
    }
    if (showMonthDialog) AssetMonthPickerDialog(month, { showMonthDialog = false }) {
        onAction(AssetsAction.SelectMonth(it))
        showMonthDialog = false
    }
}

@Composable
internal fun AssetMonthPickerDialog(current: YearMonthKey, onDismiss: () -> Unit, onSelected: (YearMonthKey) -> Unit) {
    var year by remember(current) { mutableIntStateOf(current.year) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton({ if (year > YearMonthKey.MIN_YEAR) year-- }, enabled = year > YearMonthKey.MIN_YEAR) { Icon(Icons.Default.ChevronLeft, "이전 연도") }
                Text("${year}년", fontWeight = FontWeight.Bold)
                IconButton({ if (year < YearMonthKey.MAX_YEAR) year++ }, enabled = year < YearMonthKey.MAX_YEAR) { Icon(Icons.Default.ChevronRight, "다음 연도") }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..12).chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { month ->
                            val selected = current.year == year && current.month == month
                            Text(
                                "${month}월",
                                Modifier.weight(1f).heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton) { onSelected(YearMonthKey(year, month)) }.wrapContentSize(),
                                color = if (selected) MoaLogColors.DeepTeal else LocalContentColor.current,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable private fun AssetEmptyContent(onAddAsset: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text("우리의 총 자산", style = MaterialTheme.typography.bodyLarge, color = MoaLogColors.Outline)
        Text("0원", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
        Surface(shape = CircleShape, color = Color(0xFFF1F4F0), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Info, null, Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)); Text("직접 입력한 평가액의 합계입니다", style = MaterialTheme.typography.labelSmall) }
        }
        Surface(Modifier.fillMaxWidth().padding(top = 24.dp), shape = RoundedCornerShape(24.dp), color = Color.White, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 24.dp).heightIn(min = 208.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(64.dp)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Filled.TrendingUp, null, Modifier.size(30.dp), tint = MoaLogColors.DeepTeal) } }
                Text("아직 등록된 자산이 없어요", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
                Text("현금, 예적금, 투자 등 부부의 공동 자산을\n등록하고 한눈에 관리해 보세요.", style = MaterialTheme.typography.bodySmall, color = MoaLogColors.Outline, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp, bottom = 24.dp))
                Button(onClick = onAddAsset, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.AddCircle, null); Spacer(Modifier.width(6.dp)); Text("첫 자산 항목 추가", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable private fun TotalAssetCard(state: AssetsUiState) = AssetSurface {
    val totalWon = state.totalWon
    Text("부부 합산 자산", style = MaterialTheme.typography.labelMedium, color = MoaLogColors.MutedInk)
    Text(
        when {
            state.hasTotalOverflow -> "합계 범위 초과"
            totalWon == null -> "미입력"
            else -> "${formatWon(totalWon)}원"
        },
        style = MaterialTheme.typography.displaySmall,
        color = MoaLogColors.DeepTeal,
        modifier = Modifier.padding(top = 5.dp),
    )
    state.previousMonthDeltaWon?.let { delta ->
        val sign = if (delta >= 0) "+" else ""
        Text("전월 말 대비 $sign${formatWon(delta)}원", style = MaterialTheme.typography.labelSmall, color = if (delta >= 0) Color(0xFF23826C) else MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
    } ?: Text("전월 평가액이 없어 증감액을 계산하지 않았어요", style = MaterialTheme.typography.labelSmall, color = MoaLogColors.Outline, modifier = Modifier.padding(top = 8.dp))
    val statusCopy = when (state.totalStatus) {
        AssetTrendStatus.Confirmed -> "모든 금액이 직접 입력한 확정 평가액입니다."
        AssetTrendStatus.Estimated -> "모든 금액이 증가 규칙으로 계산한 예상 평가액입니다."
        AssetTrendStatus.Mixed -> if (state.missingValueCount > 0) {
            "확정·예상 또는 미입력 금액이 함께 있어요. 미입력 ${state.missingValueCount}개는 합계에서 제외했어요."
        } else {
            "직접 입력한 확정 평가액과 증가 규칙으로 계산한 예상 평가액이 함께 있어요."
        }
        AssetTrendStatus.Missing -> "이 달에 입력되거나 예상된 평가액이 없습니다."
    }
    Surface(color = Color(0xFFF1F4F0), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text(statusCopy, style = MaterialTheme.typography.bodySmall, color = MoaLogColors.MutedInk, modifier = Modifier.padding(10.dp)) }
}

@Composable private fun TrendCard(trend: List<AssetTrendPoint>) = AssetSurface {
    Text("월별 자산 추이", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MoaLogColors.DeepTeal)
    Text("최근 6개월간의 자산 형성 곡선", style = MaterialTheme.typography.labelSmall, color = MoaLogColors.MutedInk)
    val available = trend.mapNotNull { it.amountWon }
    val description = trend.joinToString { point ->
        "${point.month}: ${if (point.hasOverflow) "범위 초과" else point.amountWon?.let(::formatWon) ?: "미입력"}${if (point.amountWon != null) "원" else ""}, ${point.status.displayName()}"
    }
    Canvas(Modifier.fillMaxWidth().height(150.dp).padding(top = 18.dp).semantics { contentDescription = description }) {
        repeat(3) { i -> drawLine(Color(0xFFECEFEA), Offset(0f, size.height * (i + 1) / 4), Offset(size.width, size.height * (i + 1) / 4)) }
        if (available.isNotEmpty()) {
            val min = available.min(); val max = available.max(); val span = (max.toDouble() - min.toDouble()).coerceAtLeast(1.0)
            var previous: Offset? = null
            trend.forEachIndexed { index, point ->
                val amount = point.amountWon
                val current = amount?.let { Offset(size.width * index / 5f, size.height - (((it.toDouble() - min.toDouble()) / span).toFloat()) * size.height * .8f - size.height * .1f) }
                if (current != null) {
                    previous?.let { drawLine(if (point.status != AssetTrendStatus.Confirmed) MoaLogColors.Outline else MoaLogColors.DeepTeal, it, current, 5f, StrokeCap.Round) }
                    drawCircle(if (point.status == AssetTrendStatus.Confirmed) MoaLogColors.DeepTeal else Color.White, 7f, current, style = if (point.status == AssetTrendStatus.Confirmed) androidx.compose.ui.graphics.drawscope.Fill else Stroke(4f))
                }
                previous = current
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { trend.forEach { Text("${it.month.month}월", style = MaterialTheme.typography.labelSmall, color = if (it.amountWon == null) MoaLogColors.Outline else MoaLogColors.MutedInk) } }
    Text("채운 점은 확정, 빈 점과 회색 선은 예상·혼합, 미입력은 선에서 제외", style = MaterialTheme.typography.labelSmall, color = MoaLogColors.Outline, modifier = Modifier.padding(top = 8.dp))
}

private fun AssetTrendStatus.displayName() = when (this) { AssetTrendStatus.Confirmed -> "확정"; AssetTrendStatus.Estimated -> "예상"; AssetTrendStatus.Mixed -> "혼합"; AssetTrendStatus.Missing -> "미입력" }

@Composable private fun ShortcutCards(state: AssetsUiState, onPurpose: () -> Unit, onSavings: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val purposeAccountTotalWon = state.purposeAccountTotalWon
        val purposeTotal = when {
            state.hasPurposeAccountOverflow -> "합계 범위 초과"
            purposeAccountTotalWon == null -> "현황 보기"
            else -> "${formatWon(purposeAccountTotalWon)}원${if (state.purposeAccountMissingCount > 0) " 외 미입력 ${state.purposeAccountMissingCount}개" else ""}"
        }
        ShortcutCard("목적통장 현황 보기", purposeTotal, Icons.Default.Savings, Modifier.weight(1f), onPurpose)
        ShortcutCard("이번 달 저축 기록", "기록 보기", Icons.Default.HistoryEdu, Modifier.weight(1f), onSavings)
    }
}

@Composable private fun ShortcutCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier.clickable(onClick = onClick), shape = RoundedCornerShape(12.dp), color = Color.White, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(14.dp)) { Icon(icon, null, tint = MoaLogColors.DeepTeal); Text(title, style = MaterialTheme.typography.labelSmall, color = MoaLogColors.MutedInk, modifier = Modifier.padding(top = 12.dp)); Text(subtitle, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MoaLogColors.DeepTeal) }
    }
}

@Composable private fun AssetListCard(rows: List<AssetRow>, members: List<LedgerMember>, onOpen: (Long) -> Unit, onAdd: () -> Unit, onToggleSort: () -> Unit) {
    AssetSurface {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("자산별 상세 목록", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MoaLogColors.DeepTeal)
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) { Text("${rows.size}개", Modifier.padding(horizontal = 7.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MoaLogColors.DeepTeal) }
            }
            TextButton(onClick = onToggleSort, modifier = Modifier.heightIn(min = 48.dp)) { Text("정렬 변경", style = MaterialTheme.typography.labelSmall); Icon(Icons.Default.SwapVert, "금액순과 이름순 전환", Modifier.size(16.dp)) }
        }
        rows.forEachIndexed { index, row ->
            if (index == 0) Spacer(Modifier.height(8.dp)) else HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
            AssetRowContent(row, members, onOpen)
        }
        OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(10.dp)) {
            Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("자산 항목 추가")
        }
    }
}

@Composable private fun AssetRowContent(row: AssetRow, members: List<LedgerMember>, onOpen: (Long) -> Unit) {
    val owner = row.asset.ownerMemberOrder?.let { order -> members.firstOrNull { it.order == order }?.displayName?.let { "개인($it)" } ?: "개인" } ?: "공동"
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable { onOpen(row.asset.id) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp)) { Box(contentAlignment = Alignment.Center) { Icon(row.asset.type.icon(), null, tint = MoaLogColors.DeepTeal) } }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(row.asset.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold); Text("$owner · ${row.asset.type.displayName}${if (row.status == AssetTrendStatus.Estimated) " · 예상" else ""}", style = MaterialTheme.typography.labelSmall, color = if (row.status == AssetTrendStatus.Estimated) Color(0xFF2A69AC) else MoaLogColors.Outline) }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (row.hasOverflow) "범위 초과" else row.amountWon?.let { "${formatWon(it)}원" } ?: "미입력", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = if (row.amountWon == null) MoaLogColors.Outline else MoaLogColors.DeepTeal)
                Text(row.asset.memo.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MoaLogColors.Outline, maxLines = 1)
            }
    }
}

private fun AssetType.icon() = when (this) { AssetType.Cash -> Icons.Default.AccountBalanceWallet; AssetType.Deposit -> Icons.Default.Savings; AssetType.Investment -> Icons.AutoMirrored.Filled.ShowChart; AssetType.Housing -> Icons.Default.HomeWork; AssetType.Other -> Icons.Default.Shield }

@Composable private fun AssetSurface(content: @Composable ColumnScope.() -> Unit) { Surface(Modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { Column(Modifier.padding(16.dp), content = content) } }

internal fun formatWon(value: Long): String {
    val negative = value < 0
    val digits = if (negative) value.toString().removePrefix("-") else value.toString()
    val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
    return if (negative) "-$grouped" else grouped
}
