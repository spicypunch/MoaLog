package kr.jm.moalog.feature.plan.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kr.jm.moalog.core.designsystem.MoaLogColors
import kr.jm.moalog.core.model.*
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun SalaryAllocationRoute(args: SalaryAllocationArgs, setup: LedgerSetup, onBack:(YearMonthKey)->Unit, onOpenDetail:(Long,YearMonthKey)->Unit, registerSystemBackRequest:((()->Unit)?)->Unit, initialEditCategoryId:Long?=null, onInitialEditConsumed:()->Unit={}) {
    var savedMonth by rememberSaveable(args.month.year,args.month.month) { mutableStateOf(SalaryAllocationMonthSavedStateCodec.encode(args.month)) }
    val selectedMonth = SalaryAllocationMonthSavedStateCodec.decode(savedMonth) ?: args.month
    val latestMonth by rememberUpdatedState(selectedMonth)
    val latestOnBack by rememberUpdatedState(onBack)
    DisposableEffect(registerSystemBackRequest) {
        registerSystemBackRequest { latestOnBack(latestMonth) }
        onDispose { registerSystemBackRequest(null) }
    }
    key(selectedMonth) {
        SalaryAllocationObservedRoute(
            args = args.copy(month = selectedMonth),
            setup = setup,
            onBack = { onBack(selectedMonth) },
            onOpenDetail = { categoryId -> onOpenDetail(categoryId, selectedMonth) },
            onSelectMonth = { savedMonth = SalaryAllocationMonthSavedStateCodec.encode(it) },
            initialEditCategoryId = initialEditCategoryId,
            onInitialEditConsumed = onInitialEditConsumed,
        )
    }
}

@Composable
private fun SalaryAllocationObservedRoute(args: SalaryAllocationArgs, setup: LedgerSetup, onBack:()->Unit, onOpenDetail:(Long)->Unit, onSelectMonth:(YearMonthKey)->Unit, initialEditCategoryId:Long?, onInitialEditConsumed:()->Unit, stateHolder:SalaryAllocationStateHolder=koinInject(parameters={parametersOf(args)})) {
    val state by stateHolder.state.collectAsStateWithLifecycle()
    DisposableEffect(stateHolder){stateHolder.start();onDispose{stateHolder.close()}}
    LaunchedEffect(initialEditCategoryId,state.sheet.categories){initialEditCategoryId?.let{id->if(state.categoryDraft==null)state.sheet.categories.firstOrNull{it.id==id}?.let{stateHolder.onAction(SalaryAllocationAction.OpenCategoryEditor(it));onInitialEditConsumed()}}}
    SalaryAllocationScreen(state,setup,stateHolder::onAction,onBack,onOpenDetail,onSelectMonth)
}

@Composable
fun SalaryAllocationScreen(state:SalaryAllocationUiState, setup:LedgerSetup, onAction:(SalaryAllocationAction)->Unit, onBack:()->Unit, onOpenDetail:(Long)->Unit, onSelectMonth:(YearMonthKey)->Unit, modifier:Modifier=Modifier){
    LazyColumn(modifier.fillMaxSize().background(MoaLogColors.Canvas),contentPadding=PaddingValues(start=20.dp,end=20.dp,top=4.dp,bottom=32.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        item{TopBar("월급 배분",onBack)}
        item{MonthSelector(state.month,onSelectMonth)}
        item{SalarySummary(state,setup,onAction)}
        item{AllocationSummary(state)}
        item{Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("대분류별 배분 계획",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold);Text("${state.month.year}년 ${state.month.month}월 · 실제 월급 기준",color=MoaLogColors.MutedInk,style=MaterialTheme.typography.labelSmall)};Text("총 ${state.sheet.categories.size}개 항목",style=MaterialTheme.typography.labelSmall,color=MoaLogColors.MutedInk)}}
        if(state.isLoading)item{Box(Modifier.fillMaxWidth().padding(40.dp),contentAlignment=Alignment.Center){CircularProgressIndicator()}}
        else if(state.sheet.categories.isEmpty())item{EmptyAllocation()}
        else items(state.sheet.categories,key={it.id}){category->CategoryCard(category,state.targets[category.id],onOpenDetail={onOpenDetail(category.id)},onEdit={onAction(SalaryAllocationAction.OpenCategoryEditor(category))})}
        item{OutlinedButton({onAction(SalaryAllocationAction.OpenCategoryEditor())},Modifier.fillMaxWidth().heightIn(min=52.dp),shape=RoundedCornerShape(12.dp)){Icon(Icons.Default.Add,null);Text("배분 항목 추가",Modifier.padding(start=8.dp))}}
        item{Text("저장한 계획은 ${state.month.month}월 월급 배분표에만 저장돼요",Modifier.fillMaxWidth(),textAlign=TextAlign.Center,color=MoaLogColors.MutedInk,style=MaterialTheme.typography.labelSmall)}
        state.error?.let{message->item{Text(message,Modifier.fillMaxWidth().semantics{liveRegion=LiveRegionMode.Assertive},color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}}
    }
    if(state.salaryEditorOpen) SalaryEditorDialog(state,setup,onAction)
    state.categoryDraft?.let{CategoryEditorDialog(it,state.sheet.categories,setup,state.error,state.isSaving,onAction)}
}

@Composable
private fun MonthSelector(month:YearMonthKey,onSelectMonth:(YearMonthKey)->Unit){
    val previous=month.plusMonthsOrNull(-1)
    val next=month.plusMonthsOrNull(1)
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
        IconButton(onClick={previous?.let(onSelectMonth)},enabled=previous!=null,modifier=Modifier.size(48.dp).semantics{contentDescription="이전 달";role=Role.Button}){Icon(Icons.Default.ChevronLeft,null)}
        Text("${month.year}년 ${month.month}월",Modifier.semantics{contentDescription="선택한 월 ${month.year}년 ${month.month}월"},style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,textAlign=TextAlign.Center)
        IconButton(onClick={next?.let(onSelectMonth)},enabled=next!=null,modifier=Modifier.size(48.dp).semantics{contentDescription="다음 달";role=Role.Button}){Icon(Icons.Default.ChevronRight,null)}
    }
}

internal object SalaryAllocationMonthSavedStateCodec {
    fun encode(month:YearMonthKey):String="${month.year}:${month.month}"
    fun decode(value:String):YearMonthKey? {
        val parts=value.split(':')
        if(parts.size!=2)return null
        val year=parts[0].toIntOrNull()?:return null
        val month=parts[1].toIntOrNull()?:return null
        return runCatching{YearMonthKey(year,month)}.getOrNull()
    }
}

@Composable private fun TopBar(title:String,onBack:()->Unit){Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onBack){Icon(Icons.AutoMirrored.Filled.ArrowBack,"뒤로")};Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold);Icon(Icons.Default.HelpOutline,"도움말",tint=MoaLogColors.MutedInk,modifier=Modifier.size(24.dp))}}

@Composable private fun SalarySummary(state:SalaryAllocationUiState,setup:LedgerSetup,onAction:(SalaryAllocationAction)->Unit){Surface(color=Color.White,shape=RoundedCornerShape(16.dp),border=BorderStroke(1.dp,MoaLogColors.CardBorder),shadowElevation=1.dp){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Favorite,null,tint=MoaLogColors.DeepTeal);Column(Modifier.padding(start=9.dp).weight(1f)){Text("부부 총 합산 급여",style=MaterialTheme.typography.labelMedium);Text("${state.month.month}월 실제 수령액",color=MoaLogColors.MutedInk,style=MaterialTheme.typography.labelSmall)};TextButton({onAction(SalaryAllocationAction.OpenSalaryEditor)},Modifier.heightIn(min=48.dp)){Icon(Icons.Default.Tune,null,Modifier.size(18.dp));Text("수정",Modifier.padding(start=4.dp))}};Text(if(state.hasSalaryOverflow)"합계 범위 초과" else state.totalSalaryWon?.let(::wonNumber)?:"미입력",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold,color=if(state.hasSalaryOverflow)MoaLogColors.Overspend else MoaLogColors.TealInk);setup.members.sortedBy{it.order}.forEach{m->val income=state.sheet.incomes.firstOrNull{it.memberOrder==m.order};Row(Modifier.fillMaxWidth()){Text("${m.displayName} 급여",Modifier.weight(1f),color=MoaLogColors.MutedInk);Text(income?.amountWon?.let(::formatWon)?:"미입력",fontWeight=FontWeight.SemiBold)}}}}
}

@Composable private fun AllocationSummary(state: SalaryAllocationUiState) {
    val difference = state.allocationDifferenceWon
    Surface(color = Color.White, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, MoaLogColors.CardBorder)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("이번 달 배분 현황", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text(state.allocationProgressBasisPoints?.let(::formatBasisPoints) ?: "계산 대기", color = MoaLogColors.DeepTeal, fontWeight = FontWeight.Bold)
            }
            LinearProgressIndicator(
                progress = { ((state.allocationProgressBasisPoints ?: 0) / 10_000f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "총 급여 중 배분 비율 ${state.allocationProgressBasisPoints?.let(::formatBasisPoints) ?: "계산 대기"}"
                },
                color = MoaLogColors.DeepTeal,
                trackColor = Color(0xFFE2E8E5),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MiniMetric("배분 총액", if (state.hasAllocationOverflow) "합계 범위 초과" else state.allocatedTotalWon?.let(::formatWon) ?: "계산 불가", Modifier.weight(1f), state.hasAllocationOverflow)
                MiniMetric(if ((difference ?: 0L) < 0L) "초과" else "남은 금액", difference?.let(::signedWon) ?: "계산 불가", Modifier.weight(1f), difference != null && difference < 0L)
            }
        }
    }
}

@Composable private fun CategoryCard(category:SalaryAllocationCategory,target:Long?,onOpenDetail:()->Unit,onEdit:()->Unit){val subtotal=category.childTotal();val subtotalOverflow=category.hasChildTotalOverflow();val diff=if(target!=null&&subtotal!=null)target-subtotal else null;Surface(Modifier.fillMaxWidth(),color=Color.White,shape=RoundedCornerShape(14.dp),border=BorderStroke(1.dp,if(diff!=null&&diff!=0L)MoaLogColors.Overspend else MoaLogColors.CardBorder)){Column(Modifier.clickable(role=Role.Button,onClickLabel="${category.name} 상세 보기",onClick=onOpenDetail).padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Row(verticalAlignment=Alignment.CenterVertically){Surface(shape=CircleShape,color=Color(0xFFE7F0ED)){Icon(Icons.Default.AccountBalanceWallet,null,Modifier.padding(9.dp).size(20.dp),tint=MoaLogColors.DeepTeal)};Column(Modifier.padding(start=10.dp).weight(1f)){Text(category.name,fontWeight=FontWeight.Bold);Text(methodLabel(category),color=MoaLogColors.MutedInk,style=MaterialTheme.typography.labelSmall)};IconButton(onEdit,Modifier.size(48.dp)){Icon(Icons.Default.Edit,"${category.name} 편집")}};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){MiniMetric("배분액",target?.let(::formatWon)?:"계산 불가",Modifier.weight(1f));MiniMetric("세부 합계",if(subtotalOverflow)"합계 범위 초과" else subtotal?.let(::formatWon)?:if(category.children.isEmpty())"항목 없음" else "미입력",Modifier.weight(1f),subtotalOverflow);MiniMetric(if(diff!=null&&diff<0)"초과" else "차액",diff?.let(::signedWon)?:"계산 불가",Modifier.weight(1f),diff!=null&&diff!=0L)};if(diff!=null&&diff!=0L)Row(Modifier.fillMaxWidth().background(Color(0xFFFDECEE),RoundedCornerShape(10.dp)).padding(10.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Warning,null,tint=MoaLogColors.Overspend,modifier=Modifier.size(18.dp));Text("세부 합계와 ${formatWon(kotlin.math.abs(diff))} 차이가 있어요. 자동 조정되지 않아요.",Modifier.padding(start=7.dp),color=Color(0xFF9B1C1F),style=MaterialTheme.typography.labelSmall)}}}}

@Composable private fun MiniMetric(label:String,value:String,modifier:Modifier,warn:Boolean=false){Column(modifier){Text(label,color=MoaLogColors.MutedInk,style=MaterialTheme.typography.labelSmall);Text(value,color=if(warn)MoaLogColors.Overspend else MoaLogColors.TealInk,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.bodySmall)}}
@Composable private fun EmptyAllocation(){Surface(Modifier.fillMaxWidth(),color=Color.White,shape=RoundedCornerShape(16.dp),border=BorderStroke(1.dp,MoaLogColors.CardBorder)){Column(Modifier.padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Default.AccountBalanceWallet,null,tint=MoaLogColors.DeepTeal);Text("아직 배분 항목이 없어요",Modifier.padding(top=10.dp),fontWeight=FontWeight.Bold);Text("이번 달 급여를 어디에 배분할지 추가해 주세요",Modifier.padding(top=4.dp),textAlign=TextAlign.Center,color=MoaLogColors.MutedInk,style=MaterialTheme.typography.bodySmall)}}}

@Composable private fun SalaryEditorDialog(state:SalaryAllocationUiState,setup:LedgerSetup,onAction:(SalaryAllocationAction)->Unit){Dialog(onDismissRequest={onAction(SalaryAllocationAction.CloseSalaryEditor)}){Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),color=MoaLogColors.Canvas){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){Row(verticalAlignment=Alignment.CenterVertically){Text("월급 수정",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);IconButton({onAction(SalaryAllocationAction.CloseSalaryEditor)}){Icon(Icons.Default.Close,"닫기")}};Text("은행 연동 없이 ${state.month.month}월 실제 수령액을 직접 입력해주세요.",color=MoaLogColors.MutedInk,style=MaterialTheme.typography.bodySmall);setup.members.sortedBy{it.order}.forEachIndexed{index,m->LabeledMoneyField("${m.displayName} 월급",state.salaryInputs.getOrElse(index){""},{onAction(SalaryAllocationAction.ChangeSalary(m.order,it))},"미입력 가능")};Surface(color=Color(0xFFE7F0ED),shape=RoundedCornerShape(12.dp)){Row(Modifier.fillMaxWidth().padding(14.dp)){Text("직접 입력된 합계",Modifier.weight(1f));Text(salaryInputTotalLabel(state.salaryInputs),fontWeight=FontWeight.Bold,color=MoaLogColors.TealInk)}};Button({onAction(SalaryAllocationAction.SaveSalaries)},Modifier.fillMaxWidth().heightIn(min=52.dp),enabled=!state.isSaving){Text("저장하기")}}}}}

@Composable private fun CategoryEditorDialog(draft:SalaryCategoryDraft,categories:List<SalaryAllocationCategory>,setup:LedgerSetup,error:String?,saving:Boolean,onAction:(SalaryAllocationAction)->Unit){Dialog(onDismissRequest={onAction(SalaryAllocationAction.CloseCategoryEditor)}){Surface(Modifier.fillMaxWidth().heightIn(max=700.dp),shape=RoundedCornerShape(20.dp),color=MoaLogColors.Canvas){LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){item{Row(verticalAlignment=Alignment.CenterVertically){Text("배분 항목 편집",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);IconButton({onAction(SalaryAllocationAction.CloseCategoryEditor)}){Icon(Icons.Default.Close,"닫기")}}};item{LabeledTextField("이름",draft.name,{onAction(SalaryAllocationAction.ChangeCategoryName(it))},"예: 생활비")};item{Text("급여 출처",style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.SemiBold);FlowChips(listOf(null to "공동 예산")+setup.members.sortedBy{it.order}.map{it.order to "${it.displayName} 급여"},draft.sourceMemberOrder){onAction(SalaryAllocationAction.ChangeCategorySource(it))}};item{Text("계산 방식",style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.SemiBold);Column(verticalArrangement=Arrangement.spacedBy(6.dp)){SalaryAllocationMethod.entries.forEach{m->MethodRow(m,m==draft.method){onAction(SalaryAllocationAction.ChangeCategoryMethod(m))}}}};if(draft.method==SalaryAllocationMethod.FixedAmount||draft.method==SalaryAllocationMethod.SalaryRatio)item{LabeledMoneyField(if(draft.method==SalaryAllocationMethod.FixedAmount)"직접 금액" else "월급 비율",draft.value,{onAction(SalaryAllocationAction.ChangeCategoryValue(it))},if(draft.method==SalaryAllocationMethod.FixedAmount)"원" else "% (소수 둘째 자리까지)",draft.method==SalaryAllocationMethod.SalaryRatio)};if(draft.method==SalaryAllocationMethod.RemainingFromSource)item{Column(verticalArrangement=Arrangement.spacedBy(6.dp)){Text("차감할 배분 항목",fontWeight=FontWeight.SemiBold);val candidates=categories.filter{it.id!=draft.id&&it.sourceMemberOrder==draft.sourceMemberOrder&&it.method!=SalaryAllocationMethod.RemainingFromSource};if(candidates.isEmpty())Text("선택할 수 있는 같은 급여 출처 항목이 없어요",color=MoaLogColors.MutedInk,style=MaterialTheme.typography.bodySmall) else candidates.forEach{category->Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable(role=Role.Checkbox){onAction(SalaryAllocationAction.ToggleDeductedCategory(category.id))},verticalAlignment=Alignment.CenterVertically){Checkbox(category.id in draft.deductedCategoryIds,onCheckedChange=null);Text(category.name,Modifier.padding(start=8.dp))}}}};item{LabeledTextField("거래수단·메모 (선택)",draft.memo,{onAction(SalaryAllocationAction.ChangeCategoryMemo(it))},"자동이체는 실행되지 않아요")};error?.let{item{Text(it,Modifier.semantics{liveRegion=LiveRegionMode.Assertive},color=MaterialTheme.colorScheme.error)}};if(draft.id!=0L)item{TextButton({onAction(SalaryAllocationAction.DeleteCategory)},Modifier.fillMaxWidth().heightIn(min=48.dp),colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){Text("배분 항목 삭제")}};item{Button({onAction(SalaryAllocationAction.SaveCategory)},Modifier.fillMaxWidth().heightIn(min=52.dp),enabled=!saving){Text("저장하기")}}}}}}

@Composable private fun <T> FlowChips(values:List<Pair<T,String>>,selected:T,onSelect:(T)->Unit){Column(verticalArrangement=Arrangement.spacedBy(6.dp)){values.chunked(2).forEach{row->Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){row.forEach{(v,label)->FilterChip(v==selected,{onSelect(v)},{Text(label)},Modifier.weight(1f).heightIn(min=48.dp))};if(row.size==1)Spacer(Modifier.weight(1f))}}}}
@Composable private fun MethodRow(method:SalaryAllocationMethod,selected:Boolean,onClick:()->Unit){Surface(Modifier.fillMaxWidth().heightIn(min=48.dp).selectable(selected,role=Role.RadioButton,onClick=onClick),shape=RoundedCornerShape(10.dp),border=BorderStroke(1.dp,if(selected)MoaLogColors.DeepTeal else MoaLogColors.CardBorder),color=if(selected)Color(0xFFE7F0ED) else Color.White){Row(Modifier.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(selected,onClick=null);Text(methodLabel(method),Modifier.padding(start=8.dp))}}}
@Composable private fun LabeledTextField(label:String,value:String,onChange:(String)->Unit,placeholder:String){Column(verticalArrangement=Arrangement.spacedBy(6.dp)){Text(label,style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.SemiBold);OutlinedTextField(value,onChange,Modifier.fillMaxWidth().heightIn(min=52.dp).semantics{contentDescription=label},placeholder={Text(placeholder)},singleLine=true,shape=RoundedCornerShape(10.dp))}}
@Composable private fun LabeledMoneyField(label:String,value:String,onChange:(String)->Unit,suffix:String,decimal:Boolean=false){Column(verticalArrangement=Arrangement.spacedBy(6.dp)){Text(label,style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.SemiBold);OutlinedTextField(value,onChange,Modifier.fillMaxWidth().heightIn(min=52.dp).semantics{contentDescription=label},suffix={Text(suffix)},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=if(decimal)KeyboardType.Decimal else KeyboardType.Number),shape=RoundedCornerShape(10.dp))}}
private fun methodLabel(c:SalaryAllocationCategory)=when(c.method){SalaryAllocationMethod.FixedAmount->"고정 ${c.amountWon?.let(::formatWon)?:"미입력"}";SalaryAllocationMethod.SalaryRatio->"${if(c.sourceMemberOrder==null)"총월급" else "선택 급여"}의 ${c.rateBasisPoints?.let(::formatBasisPoints)?:"미입력"}";SalaryAllocationMethod.ChildTotal->"하위 항목 합계";SalaryAllocationMethod.RemainingFromSource->"상위 급여에서 차감한 잔액"}
private fun methodLabel(m:SalaryAllocationMethod)=when(m){SalaryAllocationMethod.FixedAmount->"직접 금액 입력";SalaryAllocationMethod.SalaryRatio->"월급 비율";SalaryAllocationMethod.ChildTotal->"하위 항목 합계";SalaryAllocationMethod.RemainingFromSource->"상위에서 차감 (잔액)"}
private fun formatBasisPoints(v:Int)=if(v%100==0)"${v/100}%" else "${v/100}.${(v%100).toString().padStart(2,'0').trimEnd('0')}%"
private fun wonNumber(v:Long)=v.toString().reversed().chunked(3).joinToString(",").reversed()+"원"
private fun formatWon(v:Long)=wonNumber(v)
private fun signedWon(v:Long)=(if(v>0)"+" else "")+formatWon(v)
private fun salaryInputTotalLabel(inputs: List<String>): String {
    if (inputs.all(String::isBlank)) return "미입력"
    val amounts = inputs.map { input -> if(input.isBlank()) 0L else input.toLongOrNull() ?: return "합계 범위 초과" }
    return amounts.sumWonOrNull()?.let(::formatWon) ?: "합계 범위 초과"
}
