package kr.jm.moalog.feature.records.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kr.jm.moalog.core.designsystem.MoaLogColors
import kr.jm.moalog.core.model.ExpenseCategory
import kr.jm.moalog.core.model.ExpenseRecord
import kr.jm.moalog.core.model.LocalDateKey
import kr.jm.moalog.core.model.YearMonthKey
import org.koin.compose.koinInject
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal class RecordsAndroidViewModel(
    val stateHolder: RecordsStateHolder,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private var pending = RecordsSavedStateCodec.read(savedStateHandle)

    init {
        pending?.let { stateHolder.onAction(RecordsAction.RestorePreferences(it)) }
        stateHolder.start()
        viewModelScope.launch {
            stateHolder.state.collect { state ->
                pending?.let {
                    if (state.isLoading) return@collect
                    pending = null
                }
                RecordsSavedStateCodec.write(savedStateHandle, state.toPreferencesSnapshot())
            }
        }
    }
}

internal object RecordsSavedStateCodec {
    private const val YEAR = "records.year"
    private const val MONTH = "records.month"
    private const val CATEGORIES = "records.categories"
    private const val OVERSPENT = "records.overspent"
    private const val SORT = "records.sort"

    fun write(handle: SavedStateHandle, snapshot: RecordsPreferencesSnapshot) {
        handle[YEAR] = snapshot.month.year
        handle[MONTH] = snapshot.month.month
        handle[CATEGORIES] = ArrayList(snapshot.filters.categoryIds.sorted())
        handle[OVERSPENT] = snapshot.filters.overspentOnly
        handle[SORT] = snapshot.filters.sort.name
    }

    fun read(handle: SavedStateHandle): RecordsPreferencesSnapshot? {
        val year = handle.get<Int>(YEAR) ?: return null
        val month = handle.get<Int>(MONTH) ?: return null
        val selectedMonth = runCatching { YearMonthKey(year, month) }.getOrNull() ?: return null
        val sort = handle.get<String>(SORT)?.let { saved -> ExpenseSort.entries.firstOrNull { it.name == saved } } ?: ExpenseSort.Latest
        return RecordsPreferencesSnapshot(
            month = selectedMonth,
            filters = ExpenseFilters(
                categoryIds = handle.get<ArrayList<String>>(CATEGORIES).orEmpty().toSet(),
                overspentOnly = handle[OVERSPENT] ?: false,
                sort = sort,
            ),
        )
    }
}

@Composable
fun RecordsRoute(
    onOpenEditor: (recordId: Long?, month: YearMonthKey) -> Unit,
    stateHolder: RecordsStateHolder = koinInject(),
) {
    val androidViewModel = viewModel<RecordsAndroidViewModel>(
        factory = remember(stateHolder) {
            viewModelFactory {
                initializer { RecordsAndroidViewModel(stateHolder, createSavedStateHandle()) }
            }
        },
    )
    val restoredHolder = androidViewModel.stateHolder
    val state by restoredHolder.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(restoredHolder) {
        restoredHolder.start()
        onDispose(restoredHolder::pause)
    }
    DisposableEffect(lifecycleOwner, restoredHolder) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) restoredHolder.onAction(RecordsAction.RefreshCurrentDate)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    RecordsScreen(state, restoredHolder::onAction, onOpenEditor)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordsScreen(
    state: RecordsUiState,
    onAction: (RecordsAction) -> Unit,
    onOpenEditor: (recordId: Long?, month: YearMonthKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMonthMenu by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize().background(MoaLogColors.Canvas)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 84.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).background(MoaLogColors.DeepTealContainer, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Spa, contentDescription = null, tint = MoaLogColors.DeepTeal, modifier = Modifier.size(18.dp))
                    }
                    Text("모아로그", Modifier.padding(start = 8.dp), color = MoaLogColors.TealInk, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Box {
                        Surface(
                            modifier = Modifier.heightIn(min = 48.dp).clickable { showMonthMenu = true },
                            color = MoaLogColors.Linen,
                            shape = CircleShape,
                        ) {
                            Row(Modifier.padding(horizontal = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${state.month.year}년 ${state.month.month}월", color = MoaLogColors.TealInk, style = MaterialTheme.typography.labelMedium)
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MoaLogColors.DeepTeal, modifier = Modifier.padding(start = 2.dp).size(18.dp))
                            }
                        }
                        DropdownMenu(expanded = showMonthMenu, onDismissRequest = { showMonthMenu = false }) {
                            DropdownMenuItem(text = { Text("이전 달") }, onClick = { showMonthMenu = false; onAction(RecordsAction.PreviousMonth) })
                            DropdownMenuItem(text = { Text("다음 달") }, onClick = { showMonthMenu = false; onAction(RecordsAction.NextMonth) })
                        }
                    }
                }
            }

            if (!state.isLoading && state.loadError == null) item {
                Surface(
                    modifier = Modifier.fillMaxWidth().border(1.dp, MoaLogColors.CardBorder, RoundedCornerShape(16.dp)),
                    color = Color.White,
                    shape = RoundedCornerShape(16.dp),
                    shadowElevation = 1.dp,
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${state.month.month}월 변동지출 총액", Modifier.weight(1f), color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelMedium)
                            state.budgetUsagePercent?.let { percent ->
                                Text("예산 대비 ${percent.toInt()}%", color = MoaLogColors.DeepTeal, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (state.hasFullMonthTotalOverflow) "합계 범위 초과" else formatWon(state.fullMonthTotalWon), color = MoaLogColors.TealInk, style = MaterialTheme.typography.displaySmall)
                            Spacer(Modifier.weight(1f))
                            if (state.fullMonthOverspentWon > 0 || state.hasFullMonthOverspentOverflow) {
                                Surface(color = MoaLogColors.OverspendSurface, contentColor = MoaLogColors.Overspend, shape = CircleShape, border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x55D9383A))) {
                                    Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Text("과소비 ${if (state.hasFullMonthOverspentOverflow) "합계 범위 초과" else formatWon(state.fullMonthOverspentWon)}", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = Color(0x99E6E9E4))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = MoaLogColors.DeepTeal, modifier = Modifier.size(18.dp))
                            Text(
                                comparisonDescription(state),
                                Modifier.padding(start = 6.dp),
                                color = MoaLogColors.MutedInk,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.weight(1f))
                            Text("총 ${state.records.size}건", color = MoaLogColors.Outline, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            if (!state.isLoading && state.loadError == null && state.records.isNotEmpty()) item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    QuickFilterChip("과소비만", state.filters.overspentOnly, isError = true) { onAction(RecordsAction.OverspentOnlyToggled) }
                    Box(Modifier.width(1.dp).height(20.dp).background(MoaLogColors.CardBorder))
                    QuickFilterChip("전체", state.filters.categoryIds.isEmpty()) { onAction(RecordsAction.ClearCategories) }
                    state.categories.forEach { category ->
                        QuickFilterChip(category.name, category.id in state.filters.categoryIds) { onAction(RecordsAction.CategoryToggled(category.id)) }
                    }
                }
            }

            if (!state.isLoading && state.loadError == null && state.records.isNotEmpty()) item {
                Row(
                    Modifier.fillMaxWidth().semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = if (state.filters.hasSubtotalFilter) "필터 결과 ${state.visibleRecords.size}건" else "전체 기록 ${state.visibleRecords.size}건"
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (state.filters.hasSubtotalFilter) "필터된 내역 · ${if (state.hasFilteredSubtotalOverflow) "합계 범위 초과" else formatWon(state.filteredSubtotalWon)}" else "날짜순 기록 피드",
                        color = MoaLogColors.Outline,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onAction(RecordsAction.SortToggled) }, modifier = Modifier.height(48.dp)) {
                        Text(if (state.filters.sort == ExpenseSort.Latest) "최신순" else "오래된순", color = MoaLogColors.DeepTeal, style = MaterialTheme.typography.labelMedium)
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MoaLogColors.DeepTeal, modifier = Modifier.size(18.dp))
                    }
                    OutlinedButton(
                        onClick = { showFilters = true },
                        modifier = Modifier.height(48.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MoaLogColors.DeepTeal),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (state.filters.hasSubtotalFilter) MoaLogColors.DeepTeal else MoaLogColors.CardBorder),
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("필터${if (state.filters.hasSubtotalFilter) " 적용됨" else ""}", Modifier.padding(start = 5.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            when {
                state.isLoading -> item { Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                state.loadError != null -> item {
                    Column(Modifier.fillMaxWidth().padding(24.dp).semantics { error(state.loadError.orEmpty()) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(state.loadError.orEmpty(), color = MaterialTheme.colorScheme.error)
                        Button(onClick = { onAction(RecordsAction.Retry) }) { Text("다시 시도") }
                    }
                }
                state.records.isEmpty() -> item {
                    MonthEmptyState(
                        month = state.month,
                        onPrevious = { onAction(RecordsAction.PreviousMonth) },
                        onNext = { onAction(RecordsAction.NextMonth) },
                        onAdd = { onOpenEditor(null, state.month) },
                    )
                }
                state.visibleRecords.isEmpty() -> item {
                    FilterEmptyState(
                        state = state,
                        onAdd = { onOpenEditor(null, state.month) },
                        onClear = { onAction(RecordsAction.ClearFilters) },
                        onOpenFilters = { showFilters = true },
                        onRemoveCategory = { onAction(RecordsAction.CategoryToggled(it)) },
                        onRemoveOverspent = { onAction(RecordsAction.OverspentOnlyToggled) },
                    )
                }
                else -> {
                    val groups = recordGroups(state.visibleRecords)
                    groups.forEach { group ->
                        item(key = "header-${group.key}") { DateHeader(group) }
                        items(group.records, key = ExpenseRecord::id) { record ->
                            ExpenseRecordRow(record) { onOpenEditor(record.id, state.month) }
                        }
                    }
                }
            }
        }

        if (!state.isLoading && state.loadError == null && state.records.isNotEmpty()) {
            Button(
                onClick = { onOpenEditor(null, state.month) },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MoaLogColors.DeepTeal),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 7.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(22.dp))
                Text("지출 추가하기", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showFilters) {
        FilterBottomSheet(
            state = state,
            onDismiss = { showFilters = false },
            onApply = { categories, overspent, sort ->
                onAction(RecordsAction.FiltersApplied(ExpenseFilters(categories, overspent, sort)))
                showFilters = false
            },
        )
    }
}

@Composable
private fun MonthEmptyState(month: YearMonthKey, onPrevious: () -> Unit, onNext: () -> Unit, onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.White,
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MoaLogColors.CardBorder),
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPrevious, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "이전 달")
                }
                Text(
                    "${month.year}년 ${month.month}월",
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = onNext, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "다음 달")
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Box(Modifier.size(112.dp).background(MoaLogColors.Linen, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = MoaLogColors.MutedInk, modifier = Modifier.size(48.dp))
        }
        Text("이번 달 지출 내역이 없습니다.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(
            "첫 지출을 기록하고 두 사람만의 따뜻한 가계부를 시작해보세요.",
            Modifier.width(280.dp),
            color = MoaLogColors.MutedInk,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp).height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MoaLogColors.DeepTeal),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
            Text("첫 지출 기록하기", Modifier.padding(start = 6.dp), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun FilterEmptyState(
    state: RecordsUiState,
    onAdd: () -> Unit,
    onClear: () -> Unit,
    onOpenFilters: () -> Unit,
    onRemoveCategory: (String) -> Unit,
    onRemoveOverspent: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onOpenFilters, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Tune, contentDescription = "지출 필터 열기", tint = MoaLogColors.DeepTeal)
            }
            state.categories.filter { it.id in state.filters.categoryIds }.forEach { category ->
                ActiveFilterChip(category.name) { onRemoveCategory(category.id) }
            }
            if (state.filters.overspentOnly) {
                ActiveFilterChip("과소비", isError = true, onRemove = onRemoveOverspent)
            }
        }
        Spacer(Modifier.height(42.dp))
        Box(Modifier.size(100.dp).background(MoaLogColors.Linen, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.SearchOff, contentDescription = null, tint = MoaLogColors.Outline, modifier = Modifier.size(48.dp))
        }
        Text("조건에 맞는 지출 내역이 없습니다", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(
            filterEmptyDescription(state),
            Modifier.width(300.dp),
            color = MoaLogColors.MutedInk,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MoaLogColors.DeepTeal),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                Text("지출 추가하기", Modifier.padding(start = 6.dp), fontWeight = FontWeight.SemiBold)
            }
            Button(
                onClick = onClear,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MoaLogColors.DeepTealContainer, contentColor = MoaLogColors.DeepTeal),
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(20.dp))
                Text("필터 초기화", Modifier.padding(start = 6.dp), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ActiveFilterChip(label: String, isError: Boolean = false, onRemove: () -> Unit) {
    Surface(
        modifier = Modifier.height(48.dp).clickable(role = Role.Button, onClickLabel = "$label 필터 해제", onClick = onRemove),
        color = if (isError) MoaLogColors.OverspendSurface else MoaLogColors.DeepTeal,
        contentColor = if (isError) MoaLogColors.Overspend else Color.White,
        shape = CircleShape,
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.padding(start = 4.dp).size(15.dp))
        }
    }
}

private data class RecordGroup(val key: String, val date: LocalDateKey?, val records: List<ExpenseRecord>)

private fun recordGroups(records: List<ExpenseRecord>): List<RecordGroup> = records
    .groupBy(ExpenseRecord::actualDate)
    .map { (date, values) -> RecordGroup(date?.toString() ?: "undated", date, values) }

@Composable
private fun DateHeader(group: RecordGroup) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            group.date?.let { "${it.month}월 ${it.day}일 ${koreanWeekday(it)}" } ?: "날짜 미입력",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.weight(1f))
        Text(expenseGroupTotalWon(group.records)?.let(::formatWon) ?: "합계 범위 초과", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

private fun comparisonDescription(state: RecordsUiState): String {
    val comparison = state.comparison ?: return if (state.fullMonthOverspentWon == 0L) "사용자가 과소비로 표시한 지출 0원" else "월 전체 합계 기준"
    if (comparison.hasOverflow) return "지난달 비교 합계 범위 초과"
    val prefix = if (comparison.period == ExpenseComparisonPeriod.SameDayPriorMonth) "지난달 같은 날짜 대비" else "지난달 전체 대비"
    return when {
        comparison.isEqual -> "$prefix 같음"
        comparison.selectedSpentMore -> "$prefix ${formatWon(comparison.differenceWon)} 더 씀"
        else -> "$prefix ${formatWon(comparison.differenceWon)} 덜 씀"
    }
}

@Composable
private fun ExpenseRecordRow(record: ExpenseRecord, onClick: () -> Unit) {
    val monthMetadata = recordMonthMetadata(record)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(record.categoryName)
                    append(", ")
                    append(record.detail ?: "내용 없음")
                    append(", ")
                    append(formatWon(record.amountWon))
                    monthMetadata?.let { append(", "); append(it) }
                    if (record.overspent) append(", 과소비")
                }
            }
            .clickable(role = Role.Button, onClick = onClick)
            .border(1.dp, MoaLogColors.CardBorder, RoundedCornerShape(12.dp)),
        color = Color.White,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 1.dp,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(MoaLogColors.DeepTealContainer.copy(alpha = .7f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(categoryIcon(record.categoryName), contentDescription = null, tint = MoaLogColors.DeepTeal, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
                Text(record.categoryName, color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                Text(record.detail ?: "내용 없음", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                monthMetadata?.let { Text(it, color = MoaLogColors.DeepTeal, style = MaterialTheme.typography.labelSmall) }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(formatWon(record.amountWon), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (record.overspent) {
                    Text("과소비", Modifier.background(MoaLogColors.OverspendSurface, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp), color = MoaLogColors.Overspend, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

internal fun categoryIcon(name: String): ImageVector = when {
    "생활" in name -> Icons.Default.ShoppingCart
    "데이트" in name -> Icons.Default.Favorite
    "여행" in name -> Icons.Default.Flight
    "경조" in name -> Icons.Default.CardGiftcard
    "효도" in name -> Icons.Default.VolunteerActivism
    else -> Icons.Default.MoreHoriz
}

@Composable
private fun QuickFilterChip(label: String, selected: Boolean, isError: Boolean = false, onClick: () -> Unit) {
    val selectedColor = if (isError) MoaLogColors.OverspendSurface else MoaLogColors.DeepTeal
    val selectedContent = if (isError) MoaLogColors.OverspendInk else Color.White
    Surface(
        modifier = Modifier.height(48.dp).toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() }),
        color = if (selected) selectedColor else Color.White,
        contentColor = if (selected) selectedContent else MoaLogColors.MutedInk,
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected && !isError) MoaLogColors.DeepTeal else if (isError) Color(0x66D9383A) else MoaLogColors.CardBorder),
    ) {
        Row(Modifier.padding(horizontal = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isError) Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(label, Modifier.padding(start = if (isError) 5.dp else 0.dp), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBottomSheet(
    state: RecordsUiState,
    onDismiss: () -> Unit,
    onApply: (Set<String>, Boolean, ExpenseSort) -> Unit,
) {
    var categories by remember(state.filters) { mutableStateOf(state.filters.categoryIds) }
    var overspent by remember(state.filters) { mutableStateOf(state.filters.overspentOnly) }
    var sort by remember(state.filters) { mutableStateOf(state.filters.sort) }
    val categoryColumnCount = if (LocalDensity.current.fontScale >= 1.3f) 2 else 3
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.82f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("필터 및 정렬", Modifier.padding(start = 20.dp).weight(1f), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss, modifier = Modifier.padding(end = 12.dp).size(48.dp)) { Icon(Icons.Default.Close, contentDescription = "필터 닫기", tint = MoaLogColors.MutedInk) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                FilterSectionTitle("카테고리")
                state.categories.chunked(categoryColumnCount).forEach { rowCategories ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowCategories.forEach { category ->
                            FilterChoice(category, category.id in categories, Modifier.weight(1f)) {
                                categories = if (category.id in categories) categories - category.id else categories + category.id
                            }
                        }
                        repeat(categoryColumnCount - rowCategories.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                FilterSectionTitle("과소비 선택")
                SegmentedChoices(first = "전체", second = "사용자가 체크한 지출만", firstSelected = !overspent, onFirst = { overspent = false }, onSecond = { overspent = true })
                FilterSectionTitle("정렬")
                SegmentedChoices("최신순", "오래된순", sort == ExpenseSort.Latest, { sort = ExpenseSort.Latest }, { sort = ExpenseSort.Oldest })
                Spacer(Modifier.height(20.dp))
            }
            HorizontalDivider(color = MoaLogColors.CardBorder)
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { categories = emptySet(); overspent = false; sort = ExpenseSort.Latest },
                    modifier = Modifier.width(if (LocalDensity.current.fontScale >= 1.3f) 112.dp else 100.dp).heightIn(min = 52.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MoaLogColors.CardBorder),
                ) { Text("초기화", color = MoaLogColors.Ink) }
                Button(
                    onClick = { onApply(categories, overspent, sort) },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MoaLogColors.DeepTeal),
                ) { Text("필터 적용", style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
}

@Composable
private fun FilterSectionTitle(text: String) {
    Text(text, Modifier.padding(top = 20.dp, bottom = 12.dp), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun FilterChoice(category: ExpenseCategory, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.heightIn(min = 48.dp).toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() }),
        color = if (selected) MoaLogColors.DeepTeal else MoaLogColors.Canvas,
        contentColor = if (selected) Color.White else MoaLogColors.MutedInk,
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) MoaLogColors.DeepTeal else MoaLogColors.CardBorder),
    ) { Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(category.name, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    } }
}

@Composable
private fun SegmentedChoices(first: String, second: String, firstSelected: Boolean, onFirst: () -> Unit, onSecond: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MoaLogColors.Linen, RoundedCornerShape(16.dp)).padding(4.dp)) {
        Segment(first, firstSelected, Modifier.weight(1f), onFirst)
        Segment(second, !firstSelected, Modifier.weight(1f), onSecond)
    }
}

@Composable
private fun Segment(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier.heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick), color = if (selected) Color.White else Color.Transparent, shape = RoundedCornerShape(12.dp), shadowElevation = if (selected) 1.dp else 0.dp) {
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(text, color = if (selected) MoaLogColors.Ink else MoaLogColors.Outline, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

fun formatWon(amount: Long): String {
    val formatted = amount.toString().reversed().chunked(3).joinToString(",").reversed()
    return "${formatted}원"
}
