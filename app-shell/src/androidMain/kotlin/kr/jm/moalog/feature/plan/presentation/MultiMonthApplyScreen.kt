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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kr.jm.moalog.core.database.ExistingPlanPolicy
import kr.jm.moalog.core.database.PlanCopyPreviewRow
import kr.jm.moalog.core.designsystem.MoaLogColors
import kr.jm.moalog.core.model.MonthlyPlanItem
import kr.jm.moalog.core.model.YearMonthKey
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.koin.compose.getKoin
import org.koin.core.parameter.parametersOf

internal class MultiMonthApplyAndroidViewModel(
    val stateHolder: MultiMonthApplyStateHolder,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    init {
        MultiMonthApplySavedStateCodec.read(savedStateHandle)?.let { snapshot ->
            stateHolder.onAction(MultiMonthApplyAction.RestoreDraft(snapshot))
        }
        stateHolder.start()
        viewModelScope.launch {
            stateHolder.state.collect { state ->
                if (state.step == MultiMonthApplyStep.Complete) MultiMonthApplySavedStateCodec.clear(savedStateHandle)
                else MultiMonthApplySavedStateCodec.write(savedStateHandle, state.toDraftSnapshot())
            }
        }
    }

    override fun onCleared() = stateHolder.close()
}

internal object MultiMonthApplySavedStateCodec {
    private const val PRESENT = "multi_month_apply.draft.present"
    private const val YEAR = "multi_month_apply.draft.year"
    private const val MONTHS = "multi_month_apply.draft.months"
    private const val POLICY = "multi_month_apply.draft.policy"
    private const val PREVIEW = "multi_month_apply.draft.preview"

    fun write(handle: SavedStateHandle, snapshot: MultiMonthApplyDraftSnapshot) {
        handle[PRESENT] = true
        handle[YEAR] = snapshot.displayedYear
        handle[MONTHS] = ArrayList(snapshot.selectedMonths.map { "${it.year}:${it.month}" })
        handle[POLICY] = snapshot.policy.name
        handle[PREVIEW] = snapshot.previewRequested
    }

    fun read(handle: SavedStateHandle): MultiMonthApplyDraftSnapshot? {
        if (handle.get<Boolean>(PRESENT) != true) return null
        val year = handle.get<Int>(YEAR) ?: return null
        val policy = handle.get<String>(POLICY)?.let { name ->
            ExistingPlanPolicy.entries.firstOrNull { it.name == name }
        } ?: return null
        val months = handle.get<ArrayList<String>>(MONTHS).orEmpty().mapNotNull(::decodeMonth)
        return MultiMonthApplyDraftSnapshot(
            displayedYear = year,
            selectedMonths = months,
            policy = policy,
            previewRequested = handle.get<Boolean>(PREVIEW) ?: false,
        )
    }

    fun clear(handle: SavedStateHandle) {
        handle.remove<Boolean>(PRESENT)
    }

    private fun decodeMonth(value: String): YearMonthKey? {
        val parts = value.split(':')
        if (parts.size != 2) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        return runCatching { YearMonthKey(year, month) }.getOrNull()
    }
}

@Composable
fun MultiMonthApplyRoute(
    args: MultiMonthApplyArgs,
    onBack: () -> Unit,
    onComplete: (YearMonthKey, PlanTab) -> Unit,
    registerSystemBackRequest: ((() -> Unit)?) -> Unit,
) {
    val koin = getKoin()
    val androidViewModel = viewModel<MultiMonthApplyAndroidViewModel>(
        factory = remember(args, koin) {
            viewModelFactory {
                initializer {
                    MultiMonthApplyAndroidViewModel(
                        stateHolder = koin.get { parametersOf(args) },
                        savedStateHandle = createSavedStateHandle(),
                    )
                }
            }
        },
    )
    val stateHolder = androidViewModel.stateHolder
    val state by stateHolder.state.collectAsStateWithLifecycle()
    val latestState by rememberUpdatedState(state)
    DisposableEffect(stateHolder) {
        registerSystemBackRequest {
            if (latestState.step == MultiMonthApplyStep.Preview) stateHolder.onAction(MultiMonthApplyAction.PreviousStep)
            else onBack()
        }
        onDispose { registerSystemBackRequest(null) }
    }
    MultiMonthApplyScreen(
        state = state,
        onAction = stateHolder::onAction,
        onBack = onBack,
        onDone = { onComplete(args.sourceMonth, args.type.toPlanTab()) },
    )
}

@Composable
fun MultiMonthApplyScreen(
    state: MultiMonthApplyUiState,
    onAction: (MultiMonthApplyAction) -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(MoaLogColors.Canvas)) {
        ApplyToolbar(state, onAction, onBack)
        when {
            state.isSourceLoading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MoaLogColors.DeepTeal)
            }
            state.initialLoadFailed -> SourceLoadFailure(state.error, onAction, onBack, Modifier.weight(1f))
            else -> when (state.step) {
                MultiMonthApplyStep.SelectMonths -> SelectMonthsStep(state, onAction, Modifier.weight(1f))
                MultiMonthApplyStep.Preview -> PreviewStep(state, onAction, Modifier.weight(1f))
                MultiMonthApplyStep.Complete -> CompletionStep(state, onDone, Modifier.weight(1f))
            }
        }
        if (!state.isSourceLoading && !state.initialLoadFailed && state.step != MultiMonthApplyStep.Complete) ApplyFooter(state, onAction)
    }
    if (state.showApplyConfirmation) ApplyConfirmation(state, onAction)
}

@Composable
private fun SourceLoadFailure(message: String?, onAction: (MultiMonthApplyAction) -> Unit, onBack: () -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(Modifier.size(56.dp), shape = CircleShape, color = MaterialTheme.colorScheme.errorContainer) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Info, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.error)
            }
        }
        Text("계획 항목을 불러오지 못했어요", Modifier.padding(top = 18.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            message ?: "잠시 후 다시 시도해 주세요.",
            Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Assertive },
            color = MoaLogColors.MutedInk,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Button(
            { onAction(MultiMonthApplyAction.Retry) },
            Modifier.fillMaxWidth().padding(top = 24.dp).heightIn(min = 52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MoaLogColors.DeepTeal),
        ) { Text("다시 시도", fontWeight = FontWeight.Bold) }
        androidx.compose.material3.TextButton(onBack, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("계획으로 돌아가기", color = MoaLogColors.DeepTeal)
        }
    }
}

@Composable
private fun ApplyToolbar(
    state: MultiMonthApplyUiState,
    onAction: (MultiMonthApplyAction) -> Unit,
    onBack: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = if (state.step == MultiMonthApplyStep.Preview) ({ onAction(MultiMonthApplyAction.PreviousStep) }) else onBack,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                if (state.step == MultiMonthApplyStep.Preview) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Close,
                when (state.step) {
                    MultiMonthApplyStep.Preview -> "이전 단계"
                    MultiMonthApplyStep.Complete -> "완료 화면 닫기"
                    MultiMonthApplyStep.SelectMonths -> "닫기"
                },
                tint = MoaLogColors.DeepTeal,
            )
        }
        Text(
            if (state.step == MultiMonthApplyStep.Preview) "여러 달에 적용" else "여러 달 적용",
            Modifier.weight(1f),
            color = MoaLogColors.DeepTeal,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        if (state.step != MultiMonthApplyStep.Complete) {
            Text(
                if (state.step == MultiMonthApplyStep.SelectMonths) "1 / 2" else "2 / 2",
                Modifier.size(48.dp).padding(top = 15.dp),
                color = MoaLogColors.MutedInk,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
            )
        } else Spacer(Modifier.size(48.dp))
    }
}

@Composable
private fun SelectMonthsStep(
    state: MultiMonthApplyUiState,
    onAction: (MultiMonthApplyAction) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${state.args.sourceMonth.year}년 ${state.args.sourceMonth.month}월 계획을\n적용할 달을 선택해 주세요",
                    color = MoaLogColors.Ink,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    state.sourceItemName?.let { "$it 항목을 복사해요" }
                        ?: "기준월의 ${multiMonthTypeLabel(state.args.type)} 계획 전체를 복사해요",
                    color = MoaLogColors.MutedInk,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    { onAction(MultiMonthApplyAction.PreviousYear) },
                    Modifier.size(48.dp),
                    enabled = state.displayedYear > YearMonthKey.MIN_YEAR,
                ) { Icon(Icons.Default.ChevronLeft, "이전 연도") }
                Text(
                    "${state.displayedYear}년",
                    Modifier.weight(1f),
                    color = MoaLogColors.DeepTeal,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                IconButton(
                    { onAction(MultiMonthApplyAction.NextYear) },
                    Modifier.size(48.dp),
                    enabled = state.displayedYear < YearMonthKey.MAX_YEAR,
                ) { Icon(Icons.Default.ChevronRight, "다음 연도") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    "전체 선택",
                    Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { onAction(MultiMonthApplyAction.SelectDisplayedYear) }
                        .padding(horizontal = 12.dp, vertical = 15.dp),
                    color = MoaLogColors.DeepTeal,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "선택 해제",
                    Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { onAction(MultiMonthApplyAction.ClearSelection) }
                        .padding(horizontal = 12.dp, vertical = 15.dp),
                    color = MoaLogColors.MutedInk,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        items(4) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                (1..3).forEach { column ->
                    val monthNumber = row * 3 + column
                    val month = YearMonthKey(state.displayedYear, monthNumber)
                    MonthTile(
                        month = month,
                        isSource = month == state.args.sourceMonth,
                        selected = month in state.selectedMonths,
                        modifier = Modifier.weight(1f),
                    ) { onAction(MultiMonthApplyAction.ToggleMonth(month)) }
                }
            }
        }
        state.error?.let { message -> item { ApplyError(message) } }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, Modifier.size(18.dp), tint = MoaLogColors.MutedInk)
                Text("다음 단계에서 적용 전 변경 내용을 확인합니다.", Modifier.padding(start = 8.dp), color = MoaLogColors.MutedInk, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun MonthTile(month: YearMonthKey, isSource: Boolean, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val border = when {
        selected -> MoaLogColors.DeepTeal
        else -> MoaLogColors.CardBorder
    }
    Surface(
        modifier = modifier.heightIn(min = 76.dp).selectable(
            selected = selected,
            enabled = !isSource,
            role = Role.Checkbox,
            onClick = onClick,
        ).semantics {
            contentDescription = when {
                isSource -> "${month.month}월, 기준월"
                selected -> "${month.month}월, 선택됨"
                else -> "${month.month}월, 선택 안 됨"
            }
        },
        color = when {
            isSource -> Color(0xFFEAE6DF)
            selected -> Color(0xFFE7F0EE)
            else -> Color.White
        },
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, border),
    ) {
        Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (selected) Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = MoaLogColors.DeepTeal)
                Text("${month.month}월", color = if (selected) MoaLogColors.DeepTeal else MoaLogColors.Ink, fontWeight = FontWeight.Bold)
                if (isSource) Text("기준월", color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun PreviewStep(state: MultiMonthApplyUiState, onAction: (MultiMonthApplyAction) -> Unit, modifier: Modifier) {
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = Color(0xFFDDEEEB), shape = CircleShape) {
                        Text("2단계 중 2단계", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = MoaLogColors.DeepTeal, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("완료 직전", color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelSmall)
                }
                Surface(Modifier.fillMaxWidth().heightIn(min = 6.dp), color = Color(0xFFDDE3DF), shape = CircleShape) {
                    Box(Modifier.fillMaxSize().background(MoaLogColors.DeepTeal))
                }
                Text("변경 내용 확인", color = MoaLogColors.Ink, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("기존 값과 적용 후 금액을 확인하고 충돌 처리 방식을 선택해 주세요.", color = MoaLogColors.MutedInk, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item { SourceSummary(state) }
        item { PolicySelector(state.policy, onAction) }
        if ((state.preview?.conflictCount ?: 0) > 0) item {
            Surface(color = Color(0xFFFFF5DD), shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, Modifier.size(18.dp), tint = Color(0xFF765B16))
                    Text(
                        if (state.policy == ExistingPlanPolicy.Overwrite) "기존 값 ${state.preview?.conflictCount}개를 새 값으로 바꿉니다."
                        else "기존 값 ${state.preview?.conflictCount}개는 유지하고 나머지만 적용합니다.",
                        Modifier.padding(start = 8.dp), color = Color(0xFF5D4815), style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("월별 변경 내역", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${state.selectedMonths.size}개월", color = MoaLogColors.DeepTeal, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
        state.preview?.targetMonths?.forEach { target ->
            item(key = target.month.toString()) {
                TargetMonthCard(target.month, target.rows, state.policy)
            }
        }
        state.error?.let { message -> item { ApplyError(message) } }
        if (state.isLoading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
    }
}

@Composable
private fun SourceSummary(state: MultiMonthApplyUiState) {
    Surface(color = Color.White, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MoaLogColors.CardBorder)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CalendarMonth, null, Modifier.size(20.dp), tint = MoaLogColors.DeepTeal)
                Text("기준월  ${state.args.sourceMonth.year}.${state.args.sourceMonth.month.toString().padStart(2, '0')}", Modifier.padding(start = 8.dp), color = MoaLogColors.DeepTeal, fontWeight = FontWeight.Bold)
            }
            state.preview?.sourceItems?.forEach { item ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(multiMonthTypeLabel(item.type), color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelSmall)
                        Text(item.name, fontWeight = FontWeight.SemiBold)
                    }
                    Text(item.amountLabel(), color = MoaLogColors.DeepTeal, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PolicySelector(policy: ExistingPlanPolicy, onAction: (MultiMonthApplyAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("충돌 처리 방식", color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Row(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PolicyChoice("기존 값 유지", policy == ExistingPlanPolicy.KeepExisting, Modifier.weight(1f)) {
                onAction(MultiMonthApplyAction.SelectPolicy(ExistingPlanPolicy.KeepExisting))
            }
            PolicyChoice("덮어쓰기 적용", policy == ExistingPlanPolicy.Overwrite, Modifier.weight(1f)) {
                onAction(MultiMonthApplyAction.SelectPolicy(ExistingPlanPolicy.Overwrite))
            }
        }
        Text(
            if (policy == ExistingPlanPolicy.KeepExisting) "이미 작성된 금액이 있는 달은 기존 값을 유지합니다."
            else "이미 작성된 금액이 있는 달은 기준월 금액으로 바뀝니다.",
            color = MoaLogColors.MutedInk,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PolicyChoice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick).heightIn(min = 52.dp),
        color = if (selected) MoaLogColors.DeepTeal else Color.White,
        contentColor = if (selected) Color.White else MoaLogColors.MutedInk,
        shape = RoundedCornerShape(12.dp),
        border = if (selected) null else BorderStroke(1.dp, MoaLogColors.CardBorder),
    ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(label, fontWeight = FontWeight.Bold) } }
}

@Composable
private fun TargetMonthCard(month: YearMonthKey, rows: List<PlanCopyPreviewRow>, policy: ExistingPlanPolicy) {
    Surface(color = Color.White, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, MoaLogColors.CardBorder)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${month.year}년 ${month.month}월", color = MoaLogColors.DeepTeal, fontWeight = FontWeight.Bold)
            rows.forEach { row ->
                val existingItem = row.existingItem
                val appliedAmount = if (existingItem != null && policy == ExistingPlanPolicy.KeepExisting) {
                    existingItem.amountLabel()
                } else {
                    row.sourceItem.amountLabel()
                }
                Row(
                    Modifier.fillMaxWidth().semantics {
                        contentDescription = "${row.sourceItem.name}, 기존 ${existingItem?.amountLabel() ?: "미입력"}, 적용 후 $appliedAmount"
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(row.sourceItem.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            "기존 ${existingItem?.amountLabel() ?: "미입력"} → 적용 후 $appliedAmount",
                            color = MoaLogColors.MutedInk,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    val badge = when {
                        existingItem == null -> "신규 적용"
                        policy == ExistingPlanPolicy.KeepExisting -> "유지"
                        else -> "덮어쓰기"
                    }
                    Surface(color = if (existingItem == null) Color(0xFFE7F0EE) else Color(0xFFFFF5DD), shape = CircleShape) {
                        Text(badge, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = if (existingItem == null) MoaLogColors.DeepTeal else Color(0xFF5D4815), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ApplyFooter(state: MultiMonthApplyUiState, onAction: (MultiMonthApplyAction) -> Unit) {
    Surface(color = Color.White, shadowElevation = 6.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.step == MultiMonthApplyStep.Preview) {
                OutlinedButton(
                    { onAction(MultiMonthApplyAction.PreviousStep) },
                    Modifier.weight(0.55f).heightIn(min = 52.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("이전 단계", maxLines = 1) }
            }
            Button(
                onClick = {
                    if (state.step == MultiMonthApplyStep.SelectMonths) onAction(MultiMonthApplyAction.Continue)
                    else onAction(MultiMonthApplyAction.RequestApply)
                },
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                enabled = if (state.step == MultiMonthApplyStep.SelectMonths) state.canContinue else state.canApply,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MoaLogColors.DeepTeal),
            ) {
                if (state.isLoading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                else Text(
                    if (state.step == MultiMonthApplyStep.SelectMonths) "다음 · ${state.selectedMonths.size}개월 선택"
                    else "${state.selectedMonths.size}개 월에 적용하기",
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun ApplyConfirmation(state: MultiMonthApplyUiState, onAction: (MultiMonthApplyAction) -> Unit) {
    Dialog(onDismissRequest = { onAction(MultiMonthApplyAction.DismissApply) }) {
        Surface(color = Color.White, shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Default.ContentCopy, null, tint = MoaLogColors.DeepTeal)
                Text("${state.selectedMonths.size}개 월에 적용할까요?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    if (state.policy == ExistingPlanPolicy.Overwrite) "같은 항목의 기존 값은 새 값으로 바뀝니다. 적용 중 오류가 나면 모두 저장되지 않습니다."
                    else "같은 항목의 기존 값은 유지합니다. 적용 중 오류가 나면 모두 저장되지 않습니다.",
                    color = MoaLogColors.MutedInk,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ onAction(MultiMonthApplyAction.DismissApply) }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("취소") }
                    Button(
                        { onAction(MultiMonthApplyAction.ConfirmApply) },
                        Modifier.weight(1f).heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MoaLogColors.DeepTeal),
                    ) { Text("적용") }
                }
            }
        }
    }
}

@Composable
private fun CompletionStep(state: MultiMonthApplyUiState, onDone: () -> Unit, modifier: Modifier) {
    Box(
        modifier.fillMaxWidth().padding(20.dp).semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center,
    ) {
        Surface(color = Color.White, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, MoaLogColors.CardBorder)) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(Modifier.size(52.dp), color = Color(0xFFE7F0EE), shape = CircleShape) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Check, null, tint = MoaLogColors.DeepTeal) }
                }
                val result = state.result
                val changedCount = result?.changedItemCount ?: 0
                val skippedCount = result?.skippedConflictCount ?: 0
                Text(
                    when {
                        skippedCount > 0 && changedCount == 0 -> "기존 값을 그대로 유지했어요"
                        skippedCount > 0 -> "일부 항목을 적용했어요"
                        else -> "계획을 적용했어요"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    result?.let(::planCopyCompletionMessage)
                        ?: "적용 결과를 확인할 수 없어요.",
                    color = MoaLogColors.MutedInk,
                    textAlign = TextAlign.Center,
                )
                Button(
                    onDone,
                    Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MoaLogColors.DeepTeal),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("계획으로 돌아가기", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

internal fun planCopyCompletionMessage(result: kr.jm.moalog.core.database.PlanCopyResult): String =
    "${result.targetMonthCount}개월 · 신규 ${result.insertedItemCount}개 추가 · " +
        "기존 ${result.overwrittenItemCount}개 덮어쓰기 · 기존 ${result.skippedConflictCount}개 유지"

@Composable
private fun ApplyError(message: String) {
    Surface(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive; error(message) },
        color = Color(0xFFFDECEE),
        shape = RoundedCornerShape(12.dp),
    ) { Text(message, Modifier.padding(14.dp), color = Color(0xFF9D2426), style = MaterialTheme.typography.bodySmall) }
}

private fun MonthlyPlanItem.amountLabel(): String = amountWon?.let(::formatApplyWon) ?: "미입력"
private fun formatApplyWon(value: Long): String = value.toString().reversed().chunked(3).joinToString(",").reversed() + "원"
private fun kr.jm.moalog.core.model.PlanItemType.toPlanTab(): PlanTab = when (this) {
    kr.jm.moalog.core.model.PlanItemType.Income -> PlanTab.Income
    kr.jm.moalog.core.model.PlanItemType.FixedExpense -> PlanTab.FixedExpense
    kr.jm.moalog.core.model.PlanItemType.Savings -> PlanTab.Savings
}
