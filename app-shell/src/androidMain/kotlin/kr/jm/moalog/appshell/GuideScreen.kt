package kr.jm.moalog.appshell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kr.jm.moalog.core.designsystem.MoaLogColors

internal enum class GuideDestination { SalaryAllocation, FixedCostCheck, MonthlyPlan, Records, Assets }

private data class GuideStep(
    val number: Int,
    val title: String,
    val badge: String,
    val description: String,
    val icon: ImageVector,
    val destination: GuideDestination,
)

private val guideSteps = listOf(
    GuideStep(1, "월급 배분", "첫걸음", "부부의 이번 달 수입을 확인하고 주거·생활·여유·목표·기타 배분액을 입력합니다.", Icons.Default.PieChart, GuideDestination.SalaryAllocation),
    GuideStep(2, "고정비 점검", "정기 지출 확인", "등록한 고정지출 항목의 이번 달 금액을 점검하고 빠진 값을 채웁니다.", Icons.Default.Checklist, GuideDestination.FixedCostCheck),
    GuideStep(3, "월별 계획", "4개 계획 탭", "수입·고정지출·변동지출·저축 계획을 같은 귀속 월 기준으로 작성합니다.", Icons.Default.CalendarMonth, GuideDestination.MonthlyPlan),
    GuideStep(4, "지출 기록", "과소비 직접 표시", "변동지출을 기록하고 필요할 때 사용자가 과소비 여부를 직접 표시합니다.", Icons.Default.ReceiptLong, GuideDestination.Records),
    GuideStep(5, "저축·자산 확인", "월말 확인", "이번 달 저축 계획과 직접 입력한 자산·목적통장 평가액을 함께 확인합니다.", Icons.Default.AccountBalanceWallet, GuideDestination.Assets),
)

private data class GuideFaq(val question: String, val answer: String)

private val guideFaqs = listOf(
    GuideFaq("귀속 월과 실제 지출일은 어떻게 다른가요?", "귀속 월은 월별 합계에 반영할 달이고, 실제 지출일은 돈을 쓴 날짜입니다. 카드 결제처럼 두 시점이 다르면 각각 입력할 수 있습니다."),
    GuideFaq("예상과 확정은 무엇인가요?", "예상은 아직 확정되지 않은 계획 또는 계산값이고, 확정은 사용자가 직접 입력해 확정한 금액입니다. 화면에서는 두 상태를 구분해 표시합니다."),
    GuideFaq("과소비는 어떻게 표시하나요?", "지출 기록에서 사용자가 과소비로 직접 표시한 거래만 과소비 합계와 필터에 포함됩니다. 금액만으로 자동 판정하지 않습니다."),
    GuideFaq("목적통장 포함 저축과 순저축의 차이는 무엇인가요?", "목적통장 포함은 특정 지출 목적을 위해 모으는 금액까지 저축률에 포함합니다. 순저축은 항목에서 순저축 포함을 켠 금액만 따로 합산합니다."),
    GuideFaq("저축액과 자산 잔액은 어떻게 다른가요?", "저축액은 해당 월의 계획 흐름이고, 자산 잔액은 사용자가 월별로 직접 입력하거나 증가 규칙으로 계산한 평가액입니다."),
)

@Composable
internal fun GuideScreen(
    onBack: () -> Unit,
    onOpenDestination: (GuideDestination) -> Unit,
) {
    var expandedStep by rememberSaveable { mutableStateOf<Int?>(null) }
    var expandedFaq by rememberSaveable { mutableStateOf<Int?>(0) }
    Column(Modifier.fillMaxSize().background(MoaLogColors.Linen)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack, Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
            Text("사용 가이드", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("복잡한 시트 없이, 부부의 템포로 시작해요", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MoaLogColors.DeepTeal)
                        Text("같은 월 기준으로 계획하고 기록할 수 있도록 다섯 화면을 순서대로 연결했습니다.", style = MaterialTheme.typography.bodyMedium, color = MoaLogColors.MutedInk)
                    }
                }
            }
            item { Text("이 순서로 시작해 보세요", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(guideSteps.size) { index ->
                val step = guideSteps[index]
                GuideStepCard(step, expandedStep == index, onToggle = { expandedStep = if (expandedStep == index) null else index }) {
                    onOpenDestination(step.destination)
                }
            }
            item { Spacer(Modifier.height(4.dp)); Text("자주 묻는 질문과 계산 기준", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(guideFaqs.size) { index ->
                GuideFaqCard(index, guideFaqs[index], expandedFaq == index) {
                    expandedFaq = if (expandedFaq == index) null else index
                }
            }
        }
    }
}

@Composable
private fun GuideStepCard(step: GuideStep, expanded: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color.White) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggle).semantics { stateDescription = if (expanded) "펼쳐짐" else "접힘" }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(Modifier.size(38.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Box(contentAlignment = Alignment.Center) { Text(step.number.toString(), fontWeight = FontWeight.Bold, color = MoaLogColors.DeepTeal) }
                }
                Icon(step.icon, null, Modifier.padding(start = 10.dp).size(22.dp), tint = MoaLogColors.DeepTeal)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(step.title, fontWeight = FontWeight.Bold)
                    Text(step.badge, style = MaterialTheme.typography.labelSmall, color = MoaLogColors.DeepTeal)
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "설명 접기" else "설명 펼치기")
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(step.description, style = MaterialTheme.typography.bodyMedium, color = MoaLogColors.MutedInk)
                    OutlinedButton(onOpen, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("${step.title} 화면 바로가기")
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.ArrowForward, null)
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideFaqCard(index: Int, faq: GuideFaq, expanded: Boolean, onToggle: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = Color.White) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggle).semantics { stateDescription = if (expanded) "펼쳐짐" else "접힘" }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Q${index + 1}.", color = MoaLogColors.DeepTeal, fontWeight = FontWeight.Bold)
                Text(faq.question, Modifier.weight(1f).padding(horizontal = 8.dp), fontWeight = FontWeight.SemiBold)
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, if (expanded) "답변 접기" else "답변 펼치기")
            }
            if (expanded) Text(faq.answer, Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), style = MaterialTheme.typography.bodyMedium, color = MoaLogColors.MutedInk)
        }
    }
}
