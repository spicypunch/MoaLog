package kr.jm.moalog.appshell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import kr.jm.moalog.core.designsystem.MoaLogColors
import kr.jm.moalog.core.model.LedgerMember
import kr.jm.moalog.feature.plan.presentation.*
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

internal class ItemManagementAndroidViewModel(
    val holder: ItemManagementStateHolder,
    private val handle: SavedStateHandle,
) : ViewModel() {
    init {
        handle.get<String>(TYPE_KEY)?.let { value ->
            ManagedItemType.entries.firstOrNull { it.name == value }
        }?.let { holder.onAction(ItemManagementAction.SelectType(it)) }
        handle.get<Boolean>(ARCHIVED_KEY)?.let { holder.onAction(ItemManagementAction.ShowArchived(it)) }
        ItemManagementDraftCodec.read(handle)?.let { holder.onAction(ItemManagementAction.RestoreDraft(it)) }
        holder.start()
        viewModelScope.launch {
            holder.state.collect { state ->
                handle[TYPE_KEY] = state.selectedType.name
                handle[ARCHIVED_KEY] = state.showArchived
                state.editor?.let { ItemManagementDraftCodec.write(handle, it) }
                    ?: ItemManagementDraftCodec.clear(handle)
                val editorId = state.editor?.stableId
                if (!state.isLoading && editorId != null &&
                    state.allItems.none { it.stableId == editorId }
                ) holder.onAction(ItemManagementAction.DismissEditor)
            }
        }
    }

    override fun onCleared() = holder.close()

    private companion object {
        const val TYPE_KEY = "item.management.type"
        const val ARCHIVED_KEY = "item.management.archived"
    }
}

internal object ItemManagementDraftCodec {
    private const val PRESENT = "item.management.editor.present"
    fun write(handle: SavedStateHandle, draft: ItemManagementDraft) {
        handle[PRESENT] = true
        handle["item.management.editor.id"] = draft.stableId
        handle["item.management.editor.name"] = draft.name
        handle["item.management.editor.classification"] = draft.classification
        handle["item.management.editor.owner"] = draft.ownerMemberOrder
        handle["item.management.editor.purpose"] = draft.includePurposeAccount
        handle["item.management.editor.net"] = draft.includeNetSavings
    }

    fun read(handle: SavedStateHandle): ItemManagementDraft? {
        if (handle.get<Boolean>(PRESENT) != true) return null
        return ItemManagementDraft(
            stableId = handle.get<String>("item.management.editor.id"),
            name = handle.get<String>("item.management.editor.name") ?: "",
            classification = handle.get<String>("item.management.editor.classification") ?: "",
            ownerMemberOrder = handle.get<Int>("item.management.editor.owner"),
            includePurposeAccount = handle.get<Boolean>("item.management.editor.purpose") ?: false,
            includeNetSavings = handle.get<Boolean>("item.management.editor.net") ?: false,
        )
    }

    fun clear(handle: SavedStateHandle) {
        handle.remove<Boolean>(PRESENT)
        handle.remove<String>("item.management.editor.id")
    }
}

@Composable
internal fun ItemManagementRoute(
    members: List<LedgerMember>,
    onBack: () -> Unit,
    stateHolder: ItemManagementStateHolder = koinInject(),
) {
    val androidViewModel = viewModel<ItemManagementAndroidViewModel>(
        factory = remember(stateHolder) {
            viewModelFactory { initializer { ItemManagementAndroidViewModel(stateHolder, createSavedStateHandle()) } }
        },
    )
    val state by androidViewModel.holder.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    BackHandler(enabled = state.editor != null) {
        androidViewModel.holder.onAction(ItemManagementAction.DismissEditor)
    }
    LaunchedEffect(state.resultAnnouncement) {
        state.resultAnnouncement?.let {
            snackbar.showSnackbar(it)
            androidViewModel.holder.onAction(ItemManagementAction.ClearResultAnnouncement)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().background(MoaLogColors.Linen),
        containerColor = MoaLogColors.Linen,
        snackbarHost = { SnackbarHost(snackbar, Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) },
        topBar = {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack, Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
                Text("항목 관리", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        },
        floatingActionButton = {
            if (!state.showArchived && !state.isLoading && state.error == null) {
                ExtendedFloatingActionButton(
                    onClick = { androidViewModel.holder.onAction(ItemManagementAction.Add) },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("항목 추가") },
                    expanded = true,
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScrollableTabRow(
                selectedTabIndex = ManagedItemType.entries.indexOf(state.selectedType),
                edgePadding = 12.dp,
                containerColor = Color.Transparent,
            ) {
                ManagedItemType.entries.forEach { type ->
                    Tab(
                        selected = state.selectedType == type,
                        onClick = { androidViewModel.holder.onAction(ItemManagementAction.SelectType(type)) },
                        text = { Text(type.label, maxLines = 1) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !state.showArchived,
                    onClick = { androidViewModel.holder.onAction(ItemManagementAction.ShowArchived(false)) },
                    label = { Text("사용 중") },
                    leadingIcon = if (!state.showArchived) ({ Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }) else null,
                )
                FilterChip(
                    selected = state.showArchived,
                    onClick = { androidViewModel.holder.onAction(ItemManagementAction.ShowArchived(true)) },
                    label = { Text("보관함") },
                    leadingIcon = if (state.showArchived) ({ Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }) else null,
                )
            }
            Surface(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Info, null, Modifier.size(18.dp), tint = MoaLogColors.DeepTeal)
                    Text(
                        if (state.selectedType == ManagedItemType.VariableExpense) "보관한 분류도 기존 지출 기록과 필터에서 유지됩니다. 새 기록에는 사용 중 분류만 표시됩니다."
                        else "항목 정보는 월별 계획의 같은 항목과 연결됩니다. 보관해도 기존 월의 계획은 유지됩니다.",
                        Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MoaLogColors.MutedInk,
                    )
                }
            }
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.error != null && state.allItems.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        Button({ androidViewModel.holder.onAction(ItemManagementAction.Retry) }) { Text("다시 시도") }
                    }
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    state.error?.let { message ->
                        item { Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                    if (state.items.isEmpty()) {
                        item {
                            Box(Modifier.fillParentMaxHeight(.55f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(if (state.showArchived) Icons.Default.Inventory2 else Icons.Default.ListAlt, null, Modifier.size(42.dp), tint = MoaLogColors.Outline)
                                    Text(if (state.showArchived) "보관한 항목이 없어요" else "사용 중인 항목이 없어요", color = MoaLogColors.MutedInk)
                                }
                            }
                        }
                    } else {
                        itemsIndexed(state.items, key = { _, item -> item.stableId }) { index, item ->
                            ManagedItemCard(
                                item = item,
                                ownerName = members.firstOrNull { it.order == item.ownerMemberOrder }?.displayName,
                                canMoveUp = state.canReorder && index > 0,
                                canMoveDown = state.canReorder && index < state.items.lastIndex,
                                isMutating = state.isMutating,
                                onEdit = { androidViewModel.holder.onAction(ItemManagementAction.Edit(item.stableId)) },
                                onArchive = { androidViewModel.holder.onAction(ItemManagementAction.RequestArchive(item.stableId)) },
                                onRestore = { androidViewModel.holder.onAction(ItemManagementAction.Restore(item.stableId)) },
                                onMove = { direction -> androidViewModel.holder.onAction(ItemManagementAction.Move(item.stableId, direction)) },
                            )
                        }
                    }
                }
            }
        }
    }

    state.editor?.let { draft ->
        ItemEditorSheet(
            type = state.selectedType,
            draft = draft,
            members = members,
            savingsClassifications = state.savingsClassifications,
            error = state.editorError ?: state.error,
            saving = state.isMutating,
            onAction = androidViewModel.holder::onAction,
        )
    }
    if (state.pendingArchiveId != null) {
        val item = state.allItems.firstOrNull { it.stableId == state.pendingArchiveId }
        AlertDialog(
            onDismissRequest = { androidViewModel.holder.onAction(ItemManagementAction.DismissArchive) },
            title = { Text("${item?.name ?: "이 항목"}을 보관할까요?") },
            text = { Text("기존 월별 계획과 지출 기록은 그대로 유지됩니다. 언제든 보관함에서 복원할 수 있어요.") },
            confirmButton = {
                TextButton({ androidViewModel.holder.onAction(ItemManagementAction.ConfirmArchive) }) { Text("보관") }
            },
            dismissButton = {
                TextButton({ androidViewModel.holder.onAction(ItemManagementAction.DismissArchive) }) { Text("취소") }
            },
        )
    }
}

@Composable
private fun ManagedItemCard(
    item: ManagedItem,
    ownerName: String?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    isMutating: Boolean,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onMove: (Int) -> Unit,
) {
    Surface(Modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(38.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Box(contentAlignment = Alignment.Center) { Icon(item.type.icon, null, Modifier.size(20.dp), tint = MoaLogColors.DeepTeal) }
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val details = buildList {
                    if (item.type != ManagedItemType.VariableExpense) add(item.classification)
                    ownerName?.let(::add)
                    if (item.includePurposeAccount) add("목적통장 포함")
                    if (item.includeNetSavings) add("순저축 포함")
                }
                if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MoaLogColors.MutedInk, maxLines = 2)
            }
            if (item.archived) {
                TextButton(onRestore, enabled = !isMutating, modifier = Modifier.heightIn(min = 48.dp)) { Text("복원") }
            } else {
                IconButton({ onMove(-1) }, enabled = canMoveUp && !isMutating, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.KeyboardArrowUp, "${item.name} 위로 이동") }
                IconButton({ onMove(1) }, enabled = canMoveDown && !isMutating, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.KeyboardArrowDown, "${item.name} 아래로 이동") }
                IconButton(onEdit, enabled = !isMutating, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Edit, "${item.name} 편집") }
                IconButton(onArchive, enabled = !isMutating, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Archive, "${item.name} 보관") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemEditorSheet(
    type: ManagedItemType,
    draft: ItemManagementDraft,
    members: List<LedgerMember>,
    savingsClassifications: List<String>,
    error: String?,
    saving: Boolean,
    onAction: (ItemManagementAction) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { if (!saving) onAction(ItemManagementAction.DismissEditor) },
        containerColor = MoaLogColors.Linen,
        sheetState = sheetState,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(if (draft.stableId == null) "${type.label} 항목 추가" else "${type.label} 항목 편집", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = draft.name,
                onValueChange = { onAction(ItemManagementAction.NameChanged(it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("항목 이름") },
                singleLine = true,
                enabled = !saving,
            )
            if (type == ManagedItemType.Savings) {
                Text("저축 분류", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    savingsClassifications.forEach { classification ->
                        FilterChip(
                            selected = draft.classification == classification,
                            onClick = { onAction(ItemManagementAction.ClassificationChanged(classification)) },
                            label = { Text(classification) },
                            enabled = !saving,
                        )
                    }
                }
            } else if (type != ManagedItemType.VariableExpense) {
                OutlinedTextField(
                    value = draft.classification,
                    onValueChange = { onAction(ItemManagementAction.ClassificationChanged(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("분류") },
                    singleLine = true,
                    enabled = !saving,
                )
            }
            if (type != ManagedItemType.VariableExpense) {
                Text("담당", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = draft.ownerMemberOrder == null,
                        onClick = { onAction(ItemManagementAction.OwnerChanged(null)) },
                        label = { Text("공동") },
                        enabled = !saving,
                    )
                    members.sortedBy { it.order }.forEach { member ->
                        FilterChip(
                            selected = draft.ownerMemberOrder == member.order,
                            onClick = { onAction(ItemManagementAction.OwnerChanged(member.order)) },
                            label = { Text(member.displayName) },
                            enabled = !saving,
                        )
                    }
                }
            }
            if (type == ManagedItemType.Savings) {
                SettingSwitch("목적통장 포함", "목적 지출을 위해 모으는 금액에 포함합니다.", draft.includePurposeAccount, !saving) {
                    onAction(ItemManagementAction.PurposeChanged(it))
                }
                SettingSwitch("순저축 포함", "순저축 합계와 저축률 계산에 포함합니다.", draft.includeNetSavings, !saving) {
                    onAction(ItemManagementAction.NetSavingsChanged(it))
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(
                onClick = { onAction(ItemManagementAction.Save) },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                else Text("저장")
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, enabled: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MoaLogColors.MutedInk)
        }
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}

private val ManagedItemType.label: String get() = when (this) {
    ManagedItemType.Income -> "수입"
    ManagedItemType.FixedExpense -> "고정지출"
    ManagedItemType.VariableExpense -> "변동지출"
    ManagedItemType.Savings -> "저축"
}

private val ManagedItemType.icon get() = when (this) {
    ManagedItemType.Income -> Icons.Default.Payments
    ManagedItemType.FixedExpense -> Icons.Default.HomeWork
    ManagedItemType.VariableExpense -> Icons.Default.ReceiptLong
    ManagedItemType.Savings -> Icons.Default.Savings
}
