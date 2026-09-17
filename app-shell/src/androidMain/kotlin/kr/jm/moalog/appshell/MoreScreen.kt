package kr.jm.moalog.appshell

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kr.jm.moalog.auth.AuthSessionState
import kr.jm.moalog.auth.MoaLogAuthSessionManager
import kr.jm.moalog.core.contracts.HouseholdInvitationResponse
import kr.jm.moalog.core.contracts.HouseholdRole
import kr.jm.moalog.core.designsystem.MoaLogColors
import kr.jm.moalog.core.model.LedgerSetup
import kr.jm.moalog.household.HouseholdSessionState
import kr.jm.moalog.household.MoaLogHouseholdSessionManager
import kr.jm.moalog.sync.MoaLogSyncManager
import kr.jm.moalog.sync.SyncRunResult
import kr.jm.moalog.sync.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun MoreRoute(
    setup: LedgerSetup,
    versionName: String,
    authManager: MoaLogAuthSessionManager,
    householdManager: MoaLogHouseholdSessionManager,
    syncManager: MoaLogSyncManager,
    onOpenItemManagement: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenLedgerSettings: () -> Unit,
    onOpenMaintenance: () -> Unit,
) {
    val authState by authManager.state.collectAsStateWithLifecycle()
    val householdState by householdManager.state.collectAsStateWithLifecycle()
    val syncStatus by syncManager.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var actionInProgress by remember { mutableStateOf<MoreAction?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { householdManager.refresh() }

    fun runAction(action: MoreAction, block: suspend () -> Unit) {
        if (actionInProgress != null) return
        actionInProgress = action
        actionError = null
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                actionError = failure.message ?: "요청을 처리하지 못했어요"
            } finally {
                actionInProgress = null
            }
        }
    }

    MoreScreen(
        setup = setup,
        versionName = versionName,
        authState = authState,
        householdState = householdState,
        syncStatus = syncStatus,
        actionInProgress = actionInProgress,
        actionError = actionError,
        onOpenItemManagement = onOpenItemManagement,
        onOpenGuide = onOpenGuide,
        onOpenLedgerSettings = onOpenLedgerSettings,
        onOpenMaintenance = onOpenMaintenance,
        onSynchronize = { runAction(MoreAction.Synchronize) { syncManager.synchronize() } },
        onCreateInvitation = { order ->
            runAction(MoreAction.CreateInvitation) { householdManager.createInvitation(order) }
        },
        onRevokeInvitation = {
            runAction(MoreAction.RevokeInvitation) { householdManager.revokeInvitation() }
        },
        onShareInvitation = { invitation -> shareInvitation(context, setup.ledgerName, invitation.invitationToken) },
        onSignOut = { runAction(MoreAction.SignOut) { authManager.signOut() } },
        onDeleteAccount = { runAction(MoreAction.DeleteAccount) { authManager.deleteAccount() } },
        onDismissError = { actionError = null },
    )
}

internal enum class MoreAction {
    Synchronize,
    CreateInvitation,
    RevokeInvitation,
    SignOut,
    DeleteAccount,
}

@Composable
internal fun MoreScreen(
    setup: LedgerSetup,
    versionName: String,
    authState: AuthSessionState,
    householdState: HouseholdSessionState,
    syncStatus: SyncStatus,
    actionInProgress: MoreAction?,
    actionError: String?,
    onOpenItemManagement: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenLedgerSettings: () -> Unit,
    onOpenMaintenance: () -> Unit,
    onSynchronize: () -> Unit,
    onCreateInvitation: (memberOrder: Int) -> Unit,
    onRevokeInvitation: () -> Unit,
    onShareInvitation: (HouseholdInvitationResponse) -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    onDismissError: () -> Unit,
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var hiddenInvitationId by remember { mutableStateOf<String?>(null) }
    val invitation = householdState.invitation
    val invitationId = invitation?.invitation?.invitationId?.value
    val showInvitationDialog = invitation != null && hiddenInvitationId != invitationId
    val remoteHousehold = householdState.activeHousehold
    val inviteTarget = remoteHousehold?.members?.firstOrNull { it.linkedUserId == null }
    val canInvite = remoteHousehold?.currentUserRole == HouseholdRole.OWNER && inviteTarget != null
    val busy = actionInProgress != null

    LaunchedEffect(invitationId) {
        if (invitationId != null && hiddenInvitationId != invitationId) hiddenInvitationId = null
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { if (!busy) showDeleteConfirmation = false },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("모아로그 계정을 삭제할까요?") },
            text = {
                Text(
                    "로그인 정보와 서버 계정이 삭제됩니다. 혼자 쓰는 서버 가계부는 함께 삭제되고, 배우자가 연결된 가계부는 배우자에게 소유권이 이전됩니다. 이 기기에 저장된 기록은 남습니다.",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        showDeleteConfirmation = false
                        onDeleteAccount()
                    },
                ) { Text("계정 삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { showDeleteConfirmation = false }) { Text("취소") } },
        )
    }

    if (showInvitationDialog) {
        val visibleInvitation = requireNotNull(invitation)
        AlertDialog(
            onDismissRequest = { hiddenInvitationId = invitationId },
            icon = { Icon(Icons.Default.GroupAdd, contentDescription = null, tint = MoaLogColors.DeepTeal) },
            title = { Text("배우자 초대 코드") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("상대방이 모아로그의 ‘초대 코드로 참여’ 화면에 아래 코드를 입력하면 같은 가계부를 사용할 수 있어요.")
                    Surface(Modifier.fillMaxWidth(), color = MoaLogColors.Linen, shape = RoundedCornerShape(12.dp)) {
                        Text(
                            visibleInvitation.invitationToken,
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MoaLogColors.DeepTeal,
                        )
                    }
                    Text("만료: ${visibleInvitation.invitation.expiresAt.value}", style = MaterialTheme.typography.labelSmall, color = MoaLogColors.MutedInk)
                }
            },
            confirmButton = {
                Button(onClick = { onShareInvitation(visibleInvitation) }, enabled = !busy) {
                    Icon(Icons.Default.Share, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("공유하기")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = onRevokeInvitation, enabled = !busy) {
                        Text("초대 취소", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { hiddenInvitationId = invitationId }, enabled = !busy) { Text("닫기") }
                }
            },
        )
    }

    LazyColumn(
        Modifier.fillMaxSize().background(MoaLogColors.Linen),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("모아로그", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MoaLogColors.DeepTeal)
                    Surface(Modifier.size(6.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {}
                }
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(
                        setup.members.sortedBy { it.order }.joinToString(" · ") { it.displayName },
                        Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MoaLogColors.DeepTeal,
                        maxLines = 1,
                    )
                }
            }
        }
        item {
            Surface(
                Modifier.fillMaxWidth().clickable(onClick = onOpenLedgerSettings),
                color = Color.White,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(Modifier.size(48.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Favorite, null, tint = MoaLogColors.DeepTeal) }
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(setup.ledgerName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MoaLogColors.DeepTeal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${setup.members.sortedBy { it.order }.joinToString(" · ") { it.displayName }} · ${setup.baseYear}년 기준",
                            style = MaterialTheme.typography.bodySmall,
                            color = MoaLogColors.MutedInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(Icons.Default.ChevronRight, "가계부 기본 정보 수정", tint = MoaLogColors.Outline)
                }
            }
        }

        item { MoreSectionTitle("함께 쓰기", "계정 및 동기화") }
        item {
            MoreMenuSurface {
                AccountSummary(authState, remoteHousehold?.currentUserRole)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MoreMenuRow(
                    icon = if (syncStatus is SyncStatus.Running) Icons.Default.CloudSync else Icons.Default.CloudDone,
                    title = "데이터 동기화",
                    subtitle = syncStatus.displayText(),
                    badge = if (syncStatus is SyncStatus.Running) "진행 중" else null,
                    enabled = !busy && syncStatus !is SyncStatus.Running,
                    onClick = onSynchronize,
                    trailingIcon = Icons.Default.Sync,
                    trailingDescription = "지금 동기화",
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                when {
                    invitation != null -> MoreMenuRow(
                        icon = Icons.Default.Share,
                        title = "초대 코드 공유",
                        subtitle = "발급된 코드를 다시 확인하고 배우자에게 공유합니다",
                        badge = "발급됨",
                        enabled = !busy,
                        onClick = { hiddenInvitationId = null },
                    )
                    canInvite -> MoreMenuRow(
                        icon = Icons.Default.GroupAdd,
                        title = "배우자 초대",
                        subtitle = "${inviteTarget.displayName} 님이 같은 가계부를 사용하도록 초대합니다",
                        badge = null,
                        enabled = !busy,
                        onClick = { onCreateInvitation(inviteTarget.order) },
                    )
                    remoteHousehold == null -> MoreMenuRow(
                        icon = Icons.Default.CloudOff,
                        title = "배우자 연결 상태",
                        subtitle = "인터넷에 연결되면 구성원 상태를 확인할 수 있어요",
                        badge = "오프라인",
                        enabled = false,
                        onClick = {},
                    )
                    inviteTarget == null -> MoreMenuRow(
                        icon = Icons.Default.GroupAdd,
                        title = "배우자 연결",
                        subtitle = "두 구성원이 모두 가계부에 연결되어 있어요",
                        badge = "연결됨",
                        enabled = false,
                        onClick = {},
                    )
                    else -> MoreMenuRow(
                        icon = Icons.Default.GroupAdd,
                        title = "배우자 초대",
                        subtitle = "가계부 관리자만 초대 코드를 발급할 수 있어요",
                        badge = "관리자 전용",
                        enabled = false,
                        onClick = {},
                    )
                }
            }
        }

        val visibleError = actionError ?: householdState.errorMessage
        if (visibleError != null) {
            item {
                Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(14.dp)) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(visibleError, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        if (actionError != null) TextButton(onClick = onDismissError) { Text("닫기") }
                    }
                }
            }
        }

        item { MoreSectionTitle("생활 관리", "정기 지출 & 체계") }
        item {
            MoreMenuSurface {
                MoreMenuRow(Icons.Default.Apartment, "관리비 현황", "매월 고지서 내역과 최근 증감을 확인합니다", null, true, onOpenMaintenance)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MoreMenuRow(Icons.Default.Tune, "항목 관리", "수입·지출·저축 카테고리를 직접 구성하고 관리합니다", null, true, onOpenItemManagement)
            }
        }
        item { MoreSectionTitle("가이드 및 지원", "규칙 및 도움말") }
        item {
            MoreMenuSurface {
                MoreMenuRow(Icons.AutoMirrored.Filled.MenuBook, "사용 가이드", "월급 배분부터 자산 점검까지 5단계 작성법", null, true, onOpenGuide)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MoreMenuRow(Icons.Default.Settings, "가계부 기본 설정", "가계부와 구성원 이름을 직접 설정합니다", null, true, onOpenLedgerSettings)
            }
        }
        item { MoreSectionTitle("계정", "로그인 관리") }
        item {
            MoreMenuSurface {
                MoreMenuRow(Icons.AutoMirrored.Filled.Logout, "로그아웃", "이 기기의 로그인 정보를 지웁니다", null, !busy, onSignOut)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MoreMenuRow(
                    Icons.Default.DeleteForever,
                    "계정 삭제",
                    "서버의 모아로그 계정과 로그인 정보를 삭제합니다",
                    null,
                    !busy,
                    { showDeleteConfirmation = true },
                    destructive = true,
                )
            }
        }
        if (busy) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("모아로그 부부가계부 · v$versionName", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MoaLogColors.MutedInk)
                Text("평온하고 맑은 둘만의 자산 기록 습관", style = MaterialTheme.typography.bodySmall, color = MoaLogColors.Outline)
            }
        }
    }
}

@Composable
private fun AccountSummary(state: AuthSessionState, role: HouseholdRole?) {
    val user = state.user
    Row(Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(44.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, contentDescription = null, tint = MoaLogColors.DeepTeal) }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(user?.displayName?.takeIf(String::isNotBlank) ?: "로그인 계정", fontWeight = FontWeight.SemiBold)
            Text(user?.email?.takeIf(String::isNotBlank) ?: "소셜 로그인으로 연결됨", style = MaterialTheme.typography.bodySmall, color = MoaLogColors.MutedInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        role?.let {
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    if (it == HouseholdRole.OWNER) "관리자" else "구성원",
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MoaLogColors.DeepTeal,
                )
            }
        }
    }
}

@Composable
private fun MoreSectionTitle(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MoaLogColors.MutedInk)
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MoaLogColors.Outline)
    }
}

@Composable
private fun MoreMenuSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(content = content)
    }
}

@Composable
private fun MoreMenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badge: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    trailingIcon: ImageVector = Icons.Default.ChevronRight,
    trailingDescription: String? = null,
    destructive: Boolean = false,
) {
    val accent = when {
        destructive -> MaterialTheme.colorScheme.error
        enabled -> MoaLogColors.DeepTeal
        else -> MoaLogColors.MutedInk
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp).clickable(enabled = enabled, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            Modifier.size(40.dp),
            shape = CircleShape,
            color = if (destructive) MaterialTheme.colorScheme.errorContainer else if (enabled) MaterialTheme.colorScheme.secondaryContainer else Color(0xFFF1F2EF),
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = accent) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
                badge?.let {
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(it, Modifier.padding(horizontal = 7.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MoaLogColors.DeepTeal)
                    }
                }
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MoaLogColors.MutedInk, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (enabled) {
            Icon(
                trailingIcon,
                contentDescription = trailingDescription,
                modifier = Modifier.size(24.dp).semantics {
                    if (trailingDescription != null) contentDescription = trailingDescription
                },
                tint = accent,
            )
        }
    }
}

private fun SyncStatus.displayText(): String = when (this) {
    SyncStatus.Idle -> "변경 사항을 안전하게 서버에 동기화합니다"
    SyncStatus.Running -> "기기의 변경 사항을 확인하고 있어요"
    is SyncStatus.Finished -> when (val value = result) {
        is SyncRunResult.Completed -> when {
            value.pushedMutations == 0 && value.pulledChanges == 0 -> "최신 상태입니다"
            else -> "동기화 완료 · 올림 ${value.pushedMutations}건 · 받음 ${value.pulledChanges}건"
        }
        is SyncRunResult.RetryScheduled -> "동기화 보류 · 연결 상태를 확인해 주세요"
        is SyncRunResult.Failed -> "동기화 중단 · ${value.reason}"
        SyncRunResult.SkippedNoSession -> "가계부 연결을 확인해 주세요"
    }
}

private fun shareInvitation(context: Context, ledgerName: String, token: String) {
    val message = "모아로그 ‘$ledgerName’ 가계부에 초대했어요. 앱에서 초대 코드를 입력해 주세요.\n\n$token"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "모아로그 가계부 초대")
        putExtra(Intent.EXTRA_TEXT, message)
    }
    context.startActivity(Intent.createChooser(intent, "초대 코드 공유"))
}
