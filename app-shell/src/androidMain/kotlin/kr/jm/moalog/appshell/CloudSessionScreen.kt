package kr.jm.moalog.appshell

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kr.jm.moalog.auth.AuthSessionState
import kr.jm.moalog.auth.AuthSessionStatus
import kr.jm.moalog.auth.MoaLogAuthSessionManager
import kr.jm.moalog.core.contracts.AuthProvider
import kr.jm.moalog.core.designsystem.MoaLogColors
import kr.jm.moalog.core.model.LedgerSetup
import kr.jm.moalog.household.HouseholdSessionState
import kr.jm.moalog.household.HouseholdSessionStatus
import kr.jm.moalog.household.MoaLogHouseholdSessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun LoginScreen(
    state: AuthSessionState,
    manager: MoaLogAuthSessionManager,
) {
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as? Activity
    val google = remember(activity) { activity?.let(::AndroidGoogleSignIn) }
    val busy = state.status == AuthSessionStatus.SIGNING_IN

    Box(
        Modifier.fillMaxSize().background(MoaLogColors.Linen).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Surface(Modifier.size(76.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.AccountBalanceWallet,
                        contentDescription = null,
                        modifier = Modifier.size(38.dp),
                        tint = MoaLogColors.DeepTeal,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("모아로그", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MoaLogColors.DeepTeal)
                Text(
                    "둘이 함께 기록하는 부부가계부",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MoaLogColors.MutedInk,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val provider = google ?: return@Button
                    scope.launch {
                        try {
                            val challenge = manager.beginSignIn()
                            val token = provider.idToken(challenge.nonce)
                            manager.completeSignIn(AuthProvider.GOOGLE, challenge, token)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            manager.reportSignInFailure(failure.message ?: "Google 로그인에 실패했어요")
                        }
                    }
                },
                enabled = !busy && google != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .semantics { contentDescription = "Google 계정으로 로그인" },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MoaLogColors.DeepTeal),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text("Google로 계속하기", fontWeight = FontWeight.SemiBold)
                }
            }
            state.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
            Text(
                "로그인하면 두 사람의 기록을 안전하게 동기화할 수 있어요.",
                style = MaterialTheme.typography.bodySmall,
                color = MoaLogColors.MutedInk,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun HouseholdConnectionScreen(
    state: HouseholdSessionState,
    localSetup: LedgerSetup?,
    manager: MoaLogHouseholdSessionManager,
    authManager: MoaLogAuthSessionManager,
) {
    val scope = rememberCoroutineScope()
    var invitationToken by remember { mutableStateOf("") }
    var showReconnectConfirmation by remember { mutableStateOf(false) }
    val busy = state.status == HouseholdSessionStatus.LOADING

    if (showReconnectConfirmation) {
        AlertDialog(
            onDismissRequest = { if (!busy) showReconnectConfirmation = false },
            icon = { Icon(Icons.Default.WarningAmber, contentDescription = null) },
            title = { Text("이 기기의 클라우드 연결을 바꿀까요?") },
            text = { Text("다른 가계부의 기록이 섞이지 않도록 이 기기의 현재 가계부 기록과 클라우드 연결을 모두 삭제합니다.") },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        showReconnectConfirmation = false
                        scope.launch { manager.resetLocalCloudConnection() }
                    },
                ) { Text("기기 기록 삭제") }
            },
            dismissButton = { TextButton(onClick = { showReconnectConfirmation = false }) { Text("취소") } },
        )
    }

    Column(
        Modifier.fillMaxSize().background(MoaLogColors.Linen).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("함께 쓸 가계부 연결", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MoaLogColors.DeepTeal)
            Text("새 가계부를 만들거나 상대방이 보낸 초대 코드를 입력해 주세요.", style = MaterialTheme.typography.bodyMedium, color = MoaLogColors.MutedInk)
        }

        if (state.requiresLocalReconnection) {
            CloudCard(title = "이전 연결 확인", icon = Icons.Default.WarningAmber) {
                Text(
                    "현재 로그인한 계정은 이 기기에 연결돼 있던 가계부에 접근할 수 없어요. 다른 가계부를 연결하려면 이 기기의 현재 기록을 삭제해야 해요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MoaLogColors.MutedInk,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { showReconnectConfirmation = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                ) { Text("기기 기록 삭제 후 다시 설정") }
            }
        }

        if (state.availableHouseholds.isNotEmpty()) {
            CloudCard(title = "내 가계부", icon = Icons.Default.Group) {
                state.availableHouseholds.forEachIndexed { index, household ->
                    OutlinedButton(
                        onClick = { scope.launch { manager.connectExisting(household.householdId) } },
                        enabled = !busy && !state.requiresLocalReconnection,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("${household.name} · ${household.baseYear}년", Modifier.weight(1f), textAlign = TextAlign.Start)
                        Text("연결")
                    }
                    if (index != state.availableHouseholds.lastIndex) Spacer(Modifier.height(8.dp))
                }
            }
        }

        if (localSetup != null) {
            CloudCard(title = "새 가계부 만들기", icon = Icons.Default.AccountBalanceWallet) {
                Text("이 계정이 누구인지 선택하면 현재 기기의 기록을 서버에 연결해요.", style = MaterialTheme.typography.bodySmall, color = MoaLogColors.MutedInk)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    localSetup.members.sortedBy { it.order }.forEach { member ->
                        Button(
                            onClick = { scope.launch { manager.createFromLocal(localSetup, member.order) } },
                            enabled = !busy && !state.requiresLocalReconnection,
                            modifier = Modifier.weight(1f).heightIn(min = 50.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) { Text("내가 ${member.displayName}") }
                    }
                }
            }
        }

        CloudCard(title = "초대 코드로 참여", icon = Icons.Default.Link) {
            OutlinedTextField(
                value = invitationToken,
                onValueChange = { invitationToken = it.trim().take(512) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("초대 코드") },
                singleLine = true,
                enabled = !busy && !state.requiresLocalReconnection,
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { scope.launch { manager.acceptInvitation(invitationToken) } },
                enabled = !busy && !state.requiresLocalReconnection && invitationToken.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                shape = RoundedCornerShape(12.dp),
            ) { Text("초대받은 가계부 연결") }
        }

        state.errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = { scope.launch { manager.refresh() } },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("다시 불러오기") }
        }

        if (busy) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        OutlinedButton(
            onClick = { scope.launch { authManager.signOut() } },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("다른 계정으로 로그인") }
    }
}

@Composable
private fun CloudCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth(),
        color = Color.White,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = MoaLogColors.DeepTeal)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}
