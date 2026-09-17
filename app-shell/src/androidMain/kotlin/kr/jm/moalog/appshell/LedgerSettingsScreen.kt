package kr.jm.moalog.appshell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
import kr.jm.moalog.feature.setup.presentation.LedgerSettingsAction
import kr.jm.moalog.feature.setup.presentation.LedgerSettingsDraft
import kr.jm.moalog.feature.setup.presentation.LedgerSettingsStateHolder
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

internal class LedgerSettingsAndroidViewModel(
    val holder: LedgerSettingsStateHolder,
    private val handle: SavedStateHandle,
) : ViewModel() {
    init {
        val restored = LedgerSettingsDraftCodec.read(handle)
        var awaitingRestore = restored != null
        restored?.let { holder.onAction(LedgerSettingsAction.RestoreDraft(it)) }
        viewModelScope.launch {
            holder.state.collect { state ->
                if (awaitingRestore && state.isLoading) return@collect
                awaitingRestore = false
                if (state.isDirty && !state.saved) LedgerSettingsDraftCodec.write(handle, state.draft())
                else LedgerSettingsDraftCodec.clear(handle)
            }
        }
    }

    override fun onCleared() = holder.close()
}

internal object LedgerSettingsDraftCodec {
    private const val PRESENT = "ledger.settings.present"
    fun write(handle: SavedStateHandle, draft: LedgerSettingsDraft) {
        handle[PRESENT] = true
        handle["ledger.settings.name"] = draft.ledgerName
        handle["ledger.settings.first"] = draft.firstMemberName
        handle["ledger.settings.second"] = draft.secondMemberName
    }
    fun read(handle: SavedStateHandle): LedgerSettingsDraft? {
        if (handle.get<Boolean>(PRESENT) != true) return null
        return LedgerSettingsDraft(
            handle.get<String>("ledger.settings.name") ?: return null,
            handle.get<String>("ledger.settings.first") ?: return null,
            handle.get<String>("ledger.settings.second") ?: return null,
        )
    }
    fun clear(handle: SavedStateHandle) { handle.remove<Boolean>(PRESENT) }
}

@Composable
internal fun LedgerSettingsRoute(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    registerSystemBackRequest: ((() -> Unit)?) -> Unit,
    stateHolder: LedgerSettingsStateHolder = koinInject(),
) {
    val androidViewModel = viewModel<LedgerSettingsAndroidViewModel>(
        factory = remember(stateHolder) {
            viewModelFactory { initializer { LedgerSettingsAndroidViewModel(stateHolder, createSavedStateHandle()) } }
        },
    )
    val state by androidViewModel.holder.state.collectAsStateWithLifecycle()
    var confirmDiscard by remember { mutableStateOf(false) }
    val requestBack = { if (state.isDirty && !state.saved) confirmDiscard = true else onBack() }
    BackHandler(onBack = requestBack)
    DisposableEffect(requestBack) {
        registerSystemBackRequest(requestBack)
        onDispose { registerSystemBackRequest(null) }
    }
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }

    Column(Modifier.fillMaxSize().background(MoaLogColors.Linen)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(requestBack, Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
            Text("가계부 기본 설정", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.error != null && state.ledgerName.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    Button({ androidViewModel.holder.onAction(LedgerSettingsAction.Retry) }) { Text("다시 시도") }
                }
            }
            else -> {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Color.White) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(Modifier.size(46.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Favorite, null, tint = MoaLogColors.DeepTeal) }
                            }
                            Column(Modifier.padding(start = 12.dp)) {
                                Text("현재 가계부 정보", fontWeight = FontWeight.Bold)
                                Text("${state.baseYear ?: "-"}년 기준", style = MaterialTheme.typography.bodySmall, color = MoaLogColors.MutedInk)
                            }
                        }
                    }
                    SettingsTextField("가계부 이름", state.ledgerName) { androidViewModel.holder.onAction(LedgerSettingsAction.ChangeLedgerName(it)) }
                    Text("구성원 이름", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    SettingsTextField("첫 번째 구성원", state.firstMemberName) { androidViewModel.holder.onAction(LedgerSettingsAction.ChangeFirstMemberName(it)) }
                    SettingsTextField("두 번째 구성원", state.secondMemberName) { androidViewModel.holder.onAction(LedgerSettingsAction.ChangeSecondMemberName(it)) }
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = Color(0xFFF1F4F0)) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.Info, null, Modifier.size(18.dp), tint = MoaLogColors.DeepTeal)
                            Text(" 가계부와 구성원 이름만 변경합니다. 기준 연도와 모든 연도별 저축 목표는 그대로 유지됩니다.", style = MaterialTheme.typography.bodySmall, color = MoaLogColors.MutedInk)
                        }
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                Surface(shadowElevation = 4.dp, color = Color.White) {
                    Button(
                        { androidViewModel.holder.onAction(LedgerSettingsAction.Save) },
                        Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding().heightIn(min = 52.dp),
                        enabled = state.canSave,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        if (state.isSaving) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        else Text("저장하기", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("변경 내용을 취소할까요?") },
        text = { Text("저장하지 않은 이름 변경이 사라집니다.") },
        confirmButton = { TextButton({ confirmDiscard = false; onBack() }) { Text("취소하기") } },
        dismissButton = { TextButton({ confirmDiscard = false }) { Text("계속 편집") } },
    )
}

@Composable
private fun SettingsTextField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value,
        onChange,
        Modifier.fillMaxWidth().heightIn(min = 56.dp),
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
    )
}
