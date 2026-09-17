package kr.jm.moalog.feature.setup.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kr.jm.moalog.core.designsystem.MoaLogColors
import org.koin.compose.koinInject

@Composable
fun SetupRoute(stateHolder: SetupStateHolder = koinInject()) {
    val state by stateHolder.state.collectAsStateWithLifecycle()
    SetupScreen(state = state, onAction = stateHolder::onAction)
}

@Composable
fun SetupScreen(state: SetupUiState, onAction: (SetupAction) -> Unit, modifier: Modifier = Modifier) {
    var showYearPicker by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    LaunchedEffect(state.errors.hasAny, state.persistenceError) {
        if (state.errors.hasAny || state.persistenceError != null) scrollState.animateScrollTo(0)
    }
    Column(modifier.fillMaxSize().background(MoaLogColors.Canvas).safeContentPadding().imePadding()) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0xFFE6E9E4))) {
            Box(Modifier.fillMaxWidth(.66f).height(4.dp).background(MoaLogColors.DeepTeal))
        }
        Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 20.dp)) {
            Column(Modifier.padding(top = 28.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(MoaLogColors.DeepTeal).height(32.dp).padding(horizontal = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.Spa, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                    Text("모아로그", color = MoaLogColors.TealInk, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Surface(shape = CircleShape, color = MoaLogColors.DeepTealContainer) {
                        Text("시작하기", Modifier.padding(horizontal = 9.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text("우리의 돈 관리를 시작해요", style = MaterialTheme.typography.headlineLarge)
                Text("수입부터 저축까지, 두 사람이 함께 투명하게 정리해요", color = MoaLogColors.MutedInk, style = MaterialTheme.typography.bodyMedium)
            }

            setupErrorSummary(state)?.let { message ->
                Text(
                    message,
                    Modifier.fillMaxWidth().padding(bottom = 12.dp).semantics {
                        liveRegion = LiveRegionMode.Assertive
                        error(message)
                    },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth().border(1.dp, MoaLogColors.CardBorder, RoundedCornerShape(16.dp)),
                color = Color.White,
                shape = RoundedCornerShape(16.dp),
                shadowElevation = 1.dp,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    SetupField("가계부 이름", state.ledgerName, state.errors.ledgerName, "우리 부부 가계부", labelIcon = Icons.Default.AutoStories) {
                        onAction(SetupAction.LedgerNameChanged(it))
                    }
                    FormDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Groups, contentDescription = null, tint = MoaLogColors.MutedInk, modifier = Modifier.size(18.dp))
                            Text("두 사람의 이름", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text(" *", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "동등한 공동 기록",
                                Modifier.background(MoaLogColors.Linen, RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
                                color = MoaLogColors.MutedInk,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SetupField("첫 번째 이름", state.firstMemberName, state.errors.firstMemberName, "첫 번째 이름", modifier = Modifier.weight(1f), prefix = "1") {
                                onAction(SetupAction.FirstMemberNameChanged(it))
                            }
                            SetupField("두 번째 이름", state.secondMemberName, state.errors.secondMemberName, "두 번째 이름", modifier = Modifier.weight(1f), prefix = "2") {
                                onAction(SetupAction.SecondMemberNameChanged(it))
                            }
                        }
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = MoaLogColors.Outline, modifier = Modifier.size(15.dp))
                            Text("각 내역마다 작성자와 부담자를 분류하기 위해 필요해요.", color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    FormDivider()
                    SetupSelector(
                        label = "기준 연도",
                        value = state.baseYear.takeIf(String::isNotBlank)?.let { "${it}년" } ?: "기준 연도를 선택해 주세요",
                        error = state.errors.baseYear,
                        icon = Icons.Default.CalendarMonth,
                        onClick = { showYearPicker = true },
                    )
                    FormDivider()
                    SetupField(
                        label = "연 저축 목표   선택 항목",
                        value = state.annualSavingsTarget,
                        error = state.errors.annualSavingsTarget,
                        placeholder = "0",
                        keyboardType = KeyboardType.Number,
                        labelIcon = Icons.Default.Savings,
                        suffix = "원",
                        supporting = "나중에 홈 화면에서 언제든지 설정하거나 수정할 수 있어요",
                    ) { onAction(SetupAction.AnnualSavingsTargetChanged(it)) }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp).border(1.dp, Color(0x66C0C8C6), RoundedCornerShape(12.dp)),
                color = Color(0x99E6F2F0),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Favorite, contentDescription = null, tint = MoaLogColors.DeepTeal, modifier = Modifier.size(20.dp))
                    Text("매달 정기 점검과 목표 공유만으로도 두 사람이 함께하는 완벽한 가계부를 만들 수 있어요.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
            }

            Button(
                onClick = { onAction(SetupAction.Save) },
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp).height(54.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MoaLogColors.DeepTeal),
            ) {
                if (state.isSaving) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.height(20.dp).padding(end = 8.dp))
                Text(if (state.isSaving) "가계부 공간 생성 중..." else "가계부 시작하기", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                if (!state.isSaving) Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Text(
                "시작 후 모든 설정은 '더보기' 탭에서 다시 변경 가능합니다",
                Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 28.dp),
                color = MoaLogColors.Outline,
                style = MaterialTheme.typography.labelSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
    if (showYearPicker) {
        YearPickerDialog(
            selectedYear = state.baseYear.toIntOrNull(),
            onDismiss = { showYearPicker = false },
            onYearSelected = { year -> onAction(SetupAction.BaseYearChanged(year.toString())); showYearPicker = false },
        )
    }
}

private fun setupErrorSummary(state: SetupUiState): String? = state.persistenceError
    ?: state.errors.ledgerName
    ?: state.errors.firstMemberName
    ?: state.errors.secondMemberName
    ?: state.errors.baseYear
    ?: state.errors.annualSavingsTarget

@Composable
private fun SetupField(
    label: String,
    value: String,
    error: String?,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    labelIcon: ImageVector? = null,
    modifier: Modifier = Modifier,
    prefix: String? = null,
    suffix: String? = null,
    supporting: String? = null,
    onValueChange: (String) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (prefix == null) Row(verticalAlignment = Alignment.CenterVertically) {
            if (labelIcon != null) Icon(labelIcon, contentDescription = null, tint = MoaLogColors.MutedInk, modifier = Modifier.size(18.dp))
            Text(label, Modifier.padding(start = if (labelIcon == null) 0.dp else 6.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = MoaLogColors.Outline) },
            prefix = prefix?.let { number -> ({
                Box(Modifier.background(MoaLogColors.DeepTealContainer, CircleShape).padding(horizontal = 7.dp, vertical = 2.dp)) {
                    Text(number, color = MoaLogColors.TealInk, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }) },
            suffix = suffix?.let { unit -> ({ Text(unit, color = MoaLogColors.MutedInk) }) },
            modifier = Modifier.fillMaxWidth().height(if (error == null) 52.dp else 76.dp).semantics { contentDescription = label }
                .then(if (error == null) Modifier else Modifier.semantics { error(error) }),
            singleLine = true,
            isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            supportingText = error?.let { message -> ({ Text(message) }) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MoaLogColors.DeepTeal, unfocusedBorderColor = MoaLogColors.CardBorder, focusedContainerColor = Color.White, unfocusedContainerColor = Color.White),
        )
        if (supporting != null && error == null) Text(supporting, color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SetupSelector(label: String, value: String, error: String?, icon: ImageVector, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MoaLogColors.MutedInk, modifier = Modifier.size(18.dp))
            Text(label, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
        Surface(
            modifier = Modifier.fillMaxWidth().height(52.dp).clickable(onClickLabel = "$label 선택", role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
                .then(if (error == null) Modifier else Modifier.semantics { error(error) }),
            color = Color.White,
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (error == null) MoaLogColors.CardBorder else MoaLogColors.Overspend),
        ) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(value, Modifier.weight(1f), color = if (value.startsWith("기준")) MoaLogColors.Outline else MoaLogColors.Ink, style = MaterialTheme.typography.bodyLarge)
                Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = MoaLogColors.MutedInk, modifier = Modifier.size(20.dp))
            }
        }
        if (error != null) Text(error, color = MoaLogColors.Overspend, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun YearPickerDialog(selectedYear: Int?, onDismiss: () -> Unit, onYearSelected: (Int) -> Unit) {
    val firstVisibleYear = ((selectedYear ?: 2026) - 2002).coerceIn(0, 96)
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = Color.White, shape = RoundedCornerShape(16.dp), shadowElevation = 8.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("기준 연도 선택", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                    IconButton(onClick = onDismiss) { Text("닫기", color = MoaLogColors.MutedInk, style = MaterialTheme.typography.labelMedium) }
                }
                LazyColumn(Modifier.fillMaxWidth().height(360.dp), state = rememberLazyListState(firstVisibleYear)) {
                    items((2000..2100).toList()) { year ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().height(52.dp).clickable(onClickLabel = "${year}년 선택") { onYearSelected(year) },
                            color = if (year == selectedYear) MoaLogColors.DeepTealContainer else Color.Transparent,
                            shape = RoundedCornerShape(10.dp),
                        ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { Text("${year}년", Modifier.padding(horizontal = 16.dp), color = MoaLogColors.Ink, style = MaterialTheme.typography.bodyLarge, fontWeight = if (year == selectedYear) FontWeight.Bold else FontWeight.Normal) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun FormDivider() = HorizontalDivider(color = Color(0xFFECEFEA))
