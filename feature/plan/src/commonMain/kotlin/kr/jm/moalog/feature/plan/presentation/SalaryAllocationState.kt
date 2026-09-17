package kr.jm.moalog.feature.plan.presentation

import kr.jm.moalog.core.model.SalaryAllocationCategory
import kr.jm.moalog.core.model.SalaryAllocationChild
import kr.jm.moalog.core.model.SalaryAllocationMethod
import kr.jm.moalog.core.model.SalaryAllocationSheet
import kr.jm.moalog.core.model.SalaryIncome
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.core.model.categoryTargets
import kr.jm.moalog.core.model.childTotal
import kr.jm.moalog.core.model.hasChildTotalOverflow
import kr.jm.moalog.core.model.sumWonOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kr.jm.moalog.feature.plan.domain.SalaryAllocationRepository
import kotlin.math.roundToInt

data class SalaryAllocationArgs(val month: YearMonthKey, val memberOrders: List<Int> = listOf(0, 1)) {
    init { require(memberOrders.size == 2 && memberOrders.distinct().size == 2) }
}

data class SalaryCategoryDraft(
    val id: Long = 0,
    val name: String = "",
    val sourceMemberOrder: Int? = null,
    val method: SalaryAllocationMethod = SalaryAllocationMethod.FixedAmount,
    val value: String = "",
    val memo: String = "",
    val displayOrder: Int = 0,
    val deductedCategoryIds:Set<Long> = emptySet(),
)

data class SalaryAllocationUiState(
    val month: YearMonthKey,
    val sheet: SalaryAllocationSheet = SalaryAllocationSheet(month, emptyList(), emptyList()),
    val isLoading: Boolean = true,
    val error: String? = null,
    val salaryEditorOpen: Boolean = false,
    val salaryInputs: List<String> = listOf("", ""),
    val categoryDraft: SalaryCategoryDraft? = null,
    val isSaving: Boolean = false,
) {
    val totalSalaryWon: Long? get() = if (sheet.incomes.size != 2 || sheet.incomes.any { it.amountWon == null }) null else sheet.incomes.mapNotNull { it.amountWon }.sumWonOrNull()
    val hasSalaryOverflow: Boolean get() = sheet.incomes.size == 2 && sheet.incomes.none { it.amountWon == null } && totalSalaryWon == null
    val targets: Map<Long, Long?> get() = sheet.categoryTargets()
    val allocatedTotalWon: Long? get() = targets.values.takeIf { values -> values.none { it == null } }?.mapNotNull { it }?.sumWonOrNull()
    val hasAllocationOverflow: Boolean get() = targets.isNotEmpty() && targets.values.none { it == null } && allocatedTotalWon == null
    val allocationDifferenceWon: Long? get() {
        val salary = totalSalaryWon ?: return null
        val allocated = allocatedTotalWon ?: return null
        return salary - allocated
    }
    val allocationProgressBasisPoints: Int? get() {
        val salary = totalSalaryWon?.takeIf { it > 0 } ?: return null
        val allocated = allocatedTotalWon ?: return null
        return (allocated.toDouble() / salary.toDouble() * 10_000.0).roundToInt()
    }
}

sealed interface SalaryAllocationAction {
    data object OpenSalaryEditor : SalaryAllocationAction
    data object CloseSalaryEditor : SalaryAllocationAction
    data class ChangeSalary(val memberOrder: Int, val input: String) : SalaryAllocationAction
    data object SaveSalaries : SalaryAllocationAction
    data class OpenCategoryEditor(val category: SalaryAllocationCategory? = null) : SalaryAllocationAction
    data object CloseCategoryEditor : SalaryAllocationAction
    data class ChangeCategoryName(val value: String) : SalaryAllocationAction
    data class ChangeCategorySource(val memberOrder: Int?) : SalaryAllocationAction
    data class ChangeCategoryMethod(val method: SalaryAllocationMethod) : SalaryAllocationAction
    data class ChangeCategoryValue(val value: String) : SalaryAllocationAction
    data class ChangeCategoryMemo(val value: String) : SalaryAllocationAction
    data class ToggleDeductedCategory(val categoryId:Long):SalaryAllocationAction
    data object SaveCategory : SalaryAllocationAction
    data object DeleteCategory : SalaryAllocationAction
}

class SalaryAllocationStateHolder(private val args: SalaryAllocationArgs, private val repository: SalaryAllocationRepository, parentScope: CoroutineScope) {
    private val holderJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + holderJob)
    private val mutable = MutableStateFlow(SalaryAllocationUiState(args.month))
    val state: StateFlow<SalaryAllocationUiState> = mutable
    private var job: Job? = null

    fun start() { if (job != null) return; job = scope.launch { repository.observe(args.month).catch { e -> if(e is CancellationException) throw e else mutable.update { it.copy(isLoading=false, error=e.message ?: "월급 배분을 불러오지 못했어요") } }.collect { sheet -> mutable.update { it.copy(sheet=sheet,isLoading=false,error=null) } } } }
    fun close() { holderJob.cancel(); job=null }
    fun onAction(action: SalaryAllocationAction) { when(action) {
        SalaryAllocationAction.OpenSalaryEditor -> mutable.update { s -> s.copy(salaryEditorOpen=true, salaryInputs=args.memberOrders.map { order -> s.sheet.incomes.firstOrNull { it.memberOrder==order }?.amountWon?.toString() ?: "" }, error=null) }
        SalaryAllocationAction.CloseSalaryEditor -> mutable.update { it.copy(salaryEditorOpen=false,error=null) }
        is SalaryAllocationAction.ChangeSalary -> mutable.update { s ->
            val inputIndex = args.memberOrders.indexOf(action.memberOrder)
            if (inputIndex < 0) s.copy(error = "구성원 정보를 찾을 수 없어요")
            else s.copy(salaryInputs=s.salaryInputs.mapIndexed { i,v -> if(i==inputIndex) digits(action.input) else v }, error=null)
        }
        SalaryAllocationAction.SaveSalaries -> saveSalaries()
        is SalaryAllocationAction.OpenCategoryEditor -> mutable.update { s -> val c=action.category; val eligible=s.sheet.categories.filter{it.id!=c?.id&&it.sourceMemberOrder==c?.sourceMemberOrder&&it.method!=SalaryAllocationMethod.RemainingFromSource}.mapTo(mutableSetOf()){it.id}; s.copy(categoryDraft=if(c==null) SalaryCategoryDraft(displayOrder=s.sheet.categories.size) else SalaryCategoryDraft(c.id,c.name,c.sourceMemberOrder,c.method,c.amountWon?.toString() ?: c.rateBasisPoints?.let(::basisPointsInput).orEmpty(),c.memo.orEmpty(),c.displayOrder,c.deductedCategoryIds?:eligible),error=null) }
        SalaryAllocationAction.CloseCategoryEditor -> mutable.update { it.copy(categoryDraft=null,error=null) }
        is SalaryAllocationAction.ChangeCategoryName -> updateDraft { it.copy(name=action.value) }
        is SalaryAllocationAction.ChangeCategorySource -> mutable.update { s -> s.copy(categoryDraft=s.categoryDraft?.let{d->d.copy(sourceMemberOrder=action.memberOrder,deductedCategoryIds=s.sheet.categories.filter{it.id!=d.id&&it.sourceMemberOrder==action.memberOrder&&it.method!=SalaryAllocationMethod.RemainingFromSource}.mapTo(mutableSetOf()){it.id})},error=null) }
        is SalaryAllocationAction.ChangeCategoryMethod -> mutable.update { s -> s.copy(categoryDraft=s.categoryDraft?.let{d->d.copy(method=action.method,value="",deductedCategoryIds=if(action.method==SalaryAllocationMethod.RemainingFromSource)s.sheet.categories.filter{it.id!=d.id&&it.sourceMemberOrder==d.sourceMemberOrder&&it.method!=SalaryAllocationMethod.RemainingFromSource}.mapTo(mutableSetOf()){it.id}else d.deductedCategoryIds)},error=null) }
        is SalaryAllocationAction.ChangeCategoryValue -> updateDraft { it.copy(value=if(it.method==SalaryAllocationMethod.SalaryRatio) decimalInput(action.value) else digits(action.value)) }
        is SalaryAllocationAction.ChangeCategoryMemo -> updateDraft { it.copy(memo=action.value) }
        is SalaryAllocationAction.ToggleDeductedCategory -> updateDraft { d -> d.copy(deductedCategoryIds=if(action.categoryId in d.deductedCategoryIds)d.deductedCategoryIds-action.categoryId else d.deductedCategoryIds+action.categoryId) }
        SalaryAllocationAction.SaveCategory -> saveCategory()
        SalaryAllocationAction.DeleteCategory -> deleteCategory()
    } }
    private fun updateDraft(block:(SalaryCategoryDraft)->SalaryCategoryDraft)=mutable.update { it.copy(categoryDraft=it.categoryDraft?.let(block),error=null) }
    private fun saveSalaries() { val inputs=state.value.salaryInputs; val parsed=inputs.map { parseOptionalLong(it) ?: if(it.isBlank()) null else return invalid("금액이 너무 커요") }; scope.launchSave { repository.saveIncomes(parsed.mapIndexed { i,v -> SalaryIncome(args.month,args.memberOrders[i],v) }); mutable.update { it.copy(salaryEditorOpen=false) } } }
    private fun saveCategory() { val d=state.value.categoryDraft ?: return; if(d.name.isBlank()) return invalid("이름을 입력해 주세요"); val amount=if(d.method==SalaryAllocationMethod.FixedAmount) parseOptionalLong(d.value) else null; val rate=if(d.method==SalaryAllocationMethod.SalaryRatio) parseBasisPoints(d.value) else null; if(d.method==SalaryAllocationMethod.FixedAmount&&amount==null) return invalid("금액을 입력해 주세요"); if(d.method==SalaryAllocationMethod.SalaryRatio&&rate==null) return invalid("비율은 0%부터 100%까지 입력해 주세요"); val c=SalaryAllocationCategory(d.id,1,args.month,d.name.trim(),d.sourceMemberOrder,d.method,amount,rate,d.memo.trim().ifEmpty{null},d.displayOrder,deductedCategoryIds=if(d.method==SalaryAllocationMethod.RemainingFromSource)d.deductedCategoryIds else null); scope.launchSave { repository.saveCategory(c); mutable.update { it.copy(categoryDraft=null) } } }
    private fun deleteCategory() { val id=state.value.categoryDraft?.id ?: return; if(id==0L){ mutable.update{it.copy(categoryDraft=null)}; return }; scope.launchSave { repository.deleteCategory(id); mutable.update { it.copy(categoryDraft=null) } } }
    private fun invalid(message:String){ mutable.update { it.copy(error=message) } }
    private fun claimSaving():Boolean { while(true){val current=mutable.value;if(current.isSaving)return false;if(mutable.compareAndSet(current,current.copy(isSaving=true,error=null)))return true} }
    private fun CoroutineScope.launchSave(block:suspend()->Unit) { if(!claimSaving())return;launch { try { block() } catch(e:CancellationException){ throw e } catch(e:Throwable){ mutable.update{it.copy(error=e.message ?: "저장하지 못했어요")} } finally { mutable.update{it.copy(isSaving=false)} } } }
}

data class SalaryAllocationDetailArgs(val categoryId: Long, val month: YearMonthKey)
data class SalaryChildDraft(val id:Long=0,val name:String="",val amount:String="",val memo:String="",val displayOrder:Int=0,val usesGrandchildSubtotal:Boolean=false)
data class SalaryGrandchildDraft(val id:Long=0,val childId:Long,val name:String="",val amount:String="",val memo:String="",val displayOrder:Int=0)
data class SalaryAllocationDetailUiState(val month:YearMonthKey,val category:SalaryAllocationCategory?=null,val targetAmountWon:Long?=null,val incomes:List<SalaryIncome> = emptyList(),val draft:SalaryChildDraft?=null,val grandchildDraft:SalaryGrandchildDraft?=null,val expandedChildIds:Set<Long> = emptySet(),val isLoading:Boolean=true,val isSaving:Boolean=false,val error:String?=null) {
    val childTotalWon: Long? get() = category?.childTotal()
    val hasChildTotalOverflow: Boolean get() = category?.hasChildTotalOverflow() == true
}
sealed interface SalaryAllocationDetailAction { data class OpenChildEditor(val child:SalaryAllocationChild?=null):SalaryAllocationDetailAction; data object CloseEditor:SalaryAllocationDetailAction; data class ChangeName(val value:String):SalaryAllocationDetailAction; data class ChangeAmount(val value:String):SalaryAllocationDetailAction; data class ChangeMemo(val value:String):SalaryAllocationDetailAction; data object Save:SalaryAllocationDetailAction; data object Delete:SalaryAllocationDetailAction; data class ToggleChild(val childId:Long):SalaryAllocationDetailAction;data class OpenGrandchildEditor(val childId:Long,val item:kr.jm.moalog.core.model.SalaryAllocationGrandchild?=null):SalaryAllocationDetailAction;data object CloseGrandchildEditor:SalaryAllocationDetailAction;data class ChangeGrandchildName(val value:String):SalaryAllocationDetailAction;data class ChangeGrandchildAmount(val value:String):SalaryAllocationDetailAction;data class ChangeGrandchildMemo(val value:String):SalaryAllocationDetailAction;data object SaveGrandchild:SalaryAllocationDetailAction;data object DeleteGrandchild:SalaryAllocationDetailAction }
class SalaryAllocationDetailStateHolder(private val args:SalaryAllocationDetailArgs,private val repository:SalaryAllocationRepository,parentScope:CoroutineScope){
 private val holderJob=SupervisorJob(parentScope.coroutineContext[Job]); private val scope=CoroutineScope(parentScope.coroutineContext+holderJob)
 private val mutable=MutableStateFlow(SalaryAllocationDetailUiState(args.month)); val state:StateFlow<SalaryAllocationDetailUiState> = mutable; private var job:Job?=null
 fun start(){if(job!=null)return;job=scope.launch{repository.observe(args.month).catch{e->if(e is CancellationException)throw e else mutable.update{it.copy(isLoading=false,error=e.message?:"배분 상세를 불러오지 못했어요")}}.collect{s->mutable.update{it.copy(category=s.categories.firstOrNull{c->c.id==args.categoryId},targetAmountWon=s.categoryTargets()[args.categoryId],incomes=s.incomes,isLoading=false,error=null)}}}}
 fun close(){holderJob.cancel();job=null}
 fun onAction(a:SalaryAllocationDetailAction){when(a){is SalaryAllocationDetailAction.OpenChildEditor->mutable.update{s->val c=a.child;s.copy(draft=if(c==null)SalaryChildDraft(displayOrder=s.category?.children?.size?:0)else SalaryChildDraft(c.id,c.name,c.amountWon?.toString().orEmpty(),c.memo.orEmpty(),c.displayOrder,c.grandchildren.isNotEmpty()),error=null)};SalaryAllocationDetailAction.CloseEditor->mutable.update{it.copy(draft=null,error=null)};is SalaryAllocationDetailAction.ChangeName->edit{it.copy(name=a.value)};is SalaryAllocationDetailAction.ChangeAmount->edit{if(it.usesGrandchildSubtotal)it else it.copy(amount=digits(a.value))};is SalaryAllocationDetailAction.ChangeMemo->edit{it.copy(memo=a.value)};SalaryAllocationDetailAction.Save->save();SalaryAllocationDetailAction.Delete->delete();is SalaryAllocationDetailAction.ToggleChild->mutable.update{it.copy(expandedChildIds=if(a.childId in it.expandedChildIds)it.expandedChildIds-a.childId else it.expandedChildIds+a.childId)};is SalaryAllocationDetailAction.OpenGrandchildEditor->mutable.update{s->val i=a.item;s.copy(grandchildDraft=if(i==null)SalaryGrandchildDraft(childId=a.childId,displayOrder=s.category?.children?.firstOrNull{it.id==a.childId}?.grandchildren?.size?:0)else SalaryGrandchildDraft(i.id,a.childId,i.name,i.amountWon?.toString().orEmpty(),i.memo.orEmpty(),i.displayOrder),error=null)};SalaryAllocationDetailAction.CloseGrandchildEditor->mutable.update{it.copy(grandchildDraft=null,error=null)};is SalaryAllocationDetailAction.ChangeGrandchildName->editGrandchild{it.copy(name=a.value)};is SalaryAllocationDetailAction.ChangeGrandchildAmount->editGrandchild{it.copy(amount=digits(a.value))};is SalaryAllocationDetailAction.ChangeGrandchildMemo->editGrandchild{it.copy(memo=a.value)};SalaryAllocationDetailAction.SaveGrandchild->saveGrandchild();SalaryAllocationDetailAction.DeleteGrandchild->deleteGrandchild()}}
 private fun edit(f:(SalaryChildDraft)->SalaryChildDraft)=mutable.update{it.copy(draft=it.draft?.let(f),error=null)}
 private fun editGrandchild(f:(SalaryGrandchildDraft)->SalaryGrandchildDraft)=mutable.update{it.copy(grandchildDraft=it.grandchildDraft?.let(f),error=null)}
 private fun claimSaving():Boolean{while(true){val current=mutable.value;if(current.isSaving)return false;if(mutable.compareAndSet(current,current.copy(isSaving=true,error=null)))return true}}
 private fun launchSaving(block:suspend()->Unit){if(!claimSaving())return;scope.launch{try{block()}catch(e:CancellationException){throw e}catch(e:Throwable){fail(e.message?:"저장하지 못했어요")}finally{saving(false)}}}
 private fun save(){val d=state.value.draft?:return;if(d.name.isBlank())return fail("이름을 입력해 주세요");val amount=if(d.usesGrandchildSubtotal)null else parseOptionalLong(d.amount);if(!d.usesGrandchildSubtotal&&d.amount.isNotBlank()&&amount==null)return fail("금액이 너무 커요");launchSaving{repository.saveChild(SalaryAllocationChild(d.id,args.categoryId,args.month,d.name.trim(),amount,d.memo.trim().ifEmpty{null},d.displayOrder));mutable.update{it.copy(draft=null)}}}
 private fun delete(){val id=state.value.draft?.id?:return;if(id==0L){mutable.update{it.copy(draft=null)};return};launchSaving{repository.deleteChild(id);mutable.update{it.copy(draft=null)}}}
 private fun saveGrandchild(){val d=state.value.grandchildDraft?:return;if(d.name.isBlank())return fail("이름을 입력해 주세요");val amount=parseOptionalLong(d.amount);if(d.amount.isNotBlank()&&amount==null)return fail("금액이 너무 커요");launchSaving{repository.saveGrandchild(kr.jm.moalog.core.model.SalaryAllocationGrandchild(d.id,d.childId,args.month,d.name.trim(),amount,d.memo.trim().ifEmpty{null},d.displayOrder));mutable.update{it.copy(grandchildDraft=null,expandedChildIds=it.expandedChildIds+d.childId)}}}
 private fun deleteGrandchild(){val d=state.value.grandchildDraft?:return;if(d.id==0L){mutable.update{it.copy(grandchildDraft=null)};return};launchSaving{repository.deleteGrandchild(d.id);mutable.update{it.copy(grandchildDraft=null)}}}
 private fun saving(v:Boolean)=mutable.update{it.copy(isSaving=v)};private fun fail(m:String)=mutable.update{it.copy(error=m)}
}
private fun digits(value:String)=value.filter(Char::isDigit)
private fun parseOptionalLong(value:String):Long?=if(value.isBlank())null else value.toLongOrNull()
private fun decimalInput(value:String):String { val filtered=value.filter{it.isDigit()||it=='.'}; val first=filtered.indexOf('.'); return if(first<0) filtered else filtered.substring(0,first+1)+filtered.substring(first+1).replace(".","").take(2) }
private fun parseBasisPoints(value:String):Int? { val parts=value.split('.'); if(parts.size>2)return null; val whole=parts[0].ifBlank{"0"}.toLongOrNull()?:return null; val fraction=parts.getOrNull(1).orEmpty().padEnd(2,'0').take(2).toIntOrNull()?:0; if(whole !in 0L..100L)return null; val result=whole*100L+fraction; return result.takeIf{it in 0L..10_000L}?.toInt() }
private fun basisPointsInput(value:Int):String = if(value%100==0) (value/100).toString() else "${value/100}.${(value%100).toString().padStart(2,'0').trimEnd('0')}"
