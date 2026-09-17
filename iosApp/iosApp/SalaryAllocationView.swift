import SharedKit
import SwiftUI

@MainActor
private final class SalaryAllocationViewModel: ObservableObject {
    @Published private(set) var state: SalaryAllocationUiState

    private let store: IosSalaryAllocationStore
    private var observation: IosObservation?

    init(dependencies: IosDependencies, setup: LedgerSetup, month: YearMonthKey) {
        let members = setup.members.sorted { $0.order < $1.order }
        let store = dependencies.salaryAllocationStore(
            month: month,
            firstMemberOrder: members[0].order,
            secondMemberOrder: members[1].order
        )
        self.store = store
        state = store.currentState
        observation = store.observe { [weak self] state in
            DispatchQueue.main.async { self?.state = state }
        }
    }

    deinit { observation?.cancel(); store.close() }

    func targetAmount(_ categoryId: Int64) -> Int64? {
        store.targetAmount(categoryId: categoryId)?.int64Value
    }

    func openSalaryEditor() { store.openSalaryEditor() }
    func closeSalaryEditor() { store.closeSalaryEditor() }
    func changeSalary(memberOrder: Int32, value: String) {
        store.changeSalary(memberOrder: memberOrder, value: value)
    }
    func saveSalaries() { store.saveSalaries() }
    func openCategory(_ category: SalaryAllocationCategory) { store.openCategoryEditor(category: category) }
    func addCategory() { store.openNewCategoryEditor() }
    func closeCategoryEditor() { store.closeCategoryEditor() }
    func changeCategoryName(_ value: String) { store.changeCategoryName(value: value) }
    func selectCommonSource() { store.selectCommonSource() }
    func selectMemberSource(_ order: Int32) { store.selectMemberSource(memberOrder: order) }
    func changeMethod(_ method: SalaryAllocationMethod) { store.changeCategoryMethod(method: method) }
    func changeCategoryValue(_ value: String) { store.changeCategoryValue(value: value) }
    func changeMemo(_ value: String) { store.changeCategoryMemo(value: value) }
    func toggleDeductedCategory(_ id: Int64) { store.toggleDeductedCategory(categoryId: id) }
    func saveCategory() { store.saveCategory() }
    func deleteCategory() { store.deleteCategory() }
}

struct SalaryAllocationView: View {
    let dependencies: IosDependencies
    let setup: LedgerSetup
    let initialMonth: YearMonthKey
    let onClose: (YearMonthKey) -> Void

    @State private var month: YearMonthKey
    @State private var didReturn = false

    init(
        dependencies: IosDependencies,
        setup: LedgerSetup,
        initialMonth: YearMonthKey,
        onClose: @escaping (YearMonthKey) -> Void
    ) {
        self.dependencies = dependencies
        self.setup = setup
        self.initialMonth = initialMonth
        self.onClose = onClose
        _month = State(initialValue: initialMonth)
    }

    var body: some View {
        NavigationStack {
            SalaryAllocationMonthView(
                dependencies: dependencies,
                setup: setup,
                month: month,
                onClose: { _ in finishClose() },
                onPreviousMonth: { month = previousMonth(month) },
                onNextMonth: { month = nextMonth(month) }
            )
            .id("salary-\(month.year)-\(month.month)")
        }
        .tint(MoaLogColor.teal)
        .onDisappear(perform: finishClose)
    }

    private func finishClose() {
        guard !didReturn else { return }
        didReturn = true
        onClose(month)
    }
}

private struct SalaryAllocationMonthView: View {
    @StateObject private var model: SalaryAllocationViewModel
    @State private var detailCategory: AllocationCategoryRoute?

    let dependencies: IosDependencies
    let setup: LedgerSetup
    let month: YearMonthKey
    let onClose: (YearMonthKey) -> Void
    let onPreviousMonth: () -> Void
    let onNextMonth: () -> Void

    init(
        dependencies: IosDependencies,
        setup: LedgerSetup,
        month: YearMonthKey,
        onClose: @escaping (YearMonthKey) -> Void,
        onPreviousMonth: @escaping () -> Void,
        onNextMonth: @escaping () -> Void
    ) {
        _model = StateObject(wrappedValue: SalaryAllocationViewModel(dependencies: dependencies, setup: setup, month: month))
        self.dependencies = dependencies
        self.setup = setup
        self.month = month
        self.onClose = onClose
        self.onPreviousMonth = onPreviousMonth
        self.onNextMonth = onNextMonth
    }

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 16) {
                monthSelector
                salarySummary
                sectionHeader

                if model.state.isLoading {
                    ProgressView("월급 배분을 불러오는 중이에요")
                        .font(MoaLogFont.regular(13, relativeTo: .caption))
                        .tint(MoaLogColor.teal)
                        .frame(maxWidth: .infinity, minHeight: 120)
                } else if model.state.sheet.categories.isEmpty {
                    emptyState
                } else {
                    ForEach(model.state.sheet.categories, id: \.id) { category in
                        categoryCard(category)
                    }
                }

                Button(action: model.addCategory) {
                    Label("배분 항목 추가", systemImage: "plus")
                        .font(MoaLogFont.semibold(14))
                        .foregroundStyle(MoaLogColor.teal)
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(MoaLogColor.cardBorder, style: StrokeStyle(lineWidth: 1, dash: [5])))
                }
                .buttonStyle(.plain)

                Text("저장한 계획은 \(String(month.month))월 월급 배분표에만 저장돼요")
                    .font(MoaLogFont.regular(11, relativeTo: .caption2))
                    .foregroundStyle(MoaLogColor.mutedInk)
                    .multilineTextAlignment(.center)

                if let error = model.state.error {
                    Text(error)
                        .font(MoaLogFont.medium(12, relativeTo: .caption))
                        .foregroundStyle(MoaLogColor.error)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityLabel("오류, \(error)")
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
            .padding(.bottom, 24)
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity)
        }
        .background(MoaLogColor.homeCanvas.ignoresSafeArea())
        .navigationTitle("월급 배분")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button { onClose(month) } label: { Image(systemName: "chevron.left") }
                    .accessibilityLabel("계획으로 돌아가기")
            }
            ToolbarItem(placement: .topBarTrailing) {
                Image(systemName: "questionmark.circle")
                    .foregroundStyle(MoaLogColor.mutedInk)
                    .accessibilityLabel("월급과 배분 항목을 직접 입력하는 화면입니다")
            }
        }
        .navigationDestination(item: $detailCategory) { route in
            SalaryAllocationDetailView(
                dependencies: dependencies,
                categoryId: route.categoryId,
                month: route.month,
                onEditCategory: model.openCategory
            )
        }
        .sheet(isPresented: salaryEditorBinding) {
            SalaryEditorSheet(model: model, setup: setup)
                .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: categoryEditorBinding) {
            if let draft = model.state.categoryDraft {
                CategoryEditorSheet(model: model, setup: setup, draft: draft)
                    .presentationDetents([.large])
            }
        }
    }

    private var salaryEditorBinding: Binding<Bool> {
        Binding(get: { model.state.salaryEditorOpen }, set: { if !$0 { model.closeSalaryEditor() } })
    }

    private var categoryEditorBinding: Binding<Bool> {
        Binding(get: { model.state.categoryDraft != nil }, set: { if !$0 { model.closeCategoryEditor() } })
    }

    private var monthSelector: some View {
        HStack(spacing: 4) {
            monthButton("이전 달", symbol: "chevron.left", action: onPreviousMonth)
            Spacer()
            Image(systemName: "calendar")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(MoaLogColor.teal)
                .accessibilityHidden(true)
            Text("\(String(month.year))년 \(String(month.month))월")
                .font(MoaLogFont.semibold(15))
                .foregroundStyle(MoaLogColor.ink)
            Spacer()
            monthButton("다음 달", symbol: "chevron.right", action: onNextMonth)
        }
        .padding(.horizontal, 4)
        .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(MoaLogColor.cardBorder))
    }

    private func monthButton(_ label: String, symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 14, weight: .semibold))
                .frame(width: 48, height: 48)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    private var salarySummary: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Image(systemName: "heart.fill")
                    .foregroundStyle(MoaLogColor.teal)
                    .frame(width: 36, height: 36)
                    .background(MoaLogColor.sage, in: Circle())
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text("부부 총 합산 급여")
                        .font(MoaLogFont.semibold(13, relativeTo: .caption))
                    Text("\(String(month.month))월 실제 수령액")
                        .font(MoaLogFont.regular(11, relativeTo: .caption2))
                        .foregroundStyle(MoaLogColor.mutedInk)
                }
                Spacer()
                Button(action: model.openSalaryEditor) {
                    Label("수정", systemImage: "slider.horizontal.3")
                        .font(MoaLogFont.semibold(12, relativeTo: .caption))
                        .frame(minHeight: 44)
                }
                .buttonStyle(.plain)
            }

            Text(totalSalaryText)
                .font(MoaLogFont.bold(28, relativeTo: .title))
                .foregroundStyle(MoaLogColor.ink)
                .minimumScaleFactor(0.7)
                .lineLimit(1)

            Divider()

            ForEach(setup.members.sorted { $0.order < $1.order }, id: \.order) { member in
                HStack {
                    Text("\(member.displayName) 급여")
                        .font(MoaLogFont.regular(12, relativeTo: .caption))
                        .foregroundStyle(MoaLogColor.mutedInk)
                    Spacer()
                    Text(model.state.sheet.incomes.first(where: { $0.memberOrder == member.order })?.amountWon.map { formatWon($0.int64Value) } ?? "미입력")
                        .font(MoaLogFont.semibold(12, relativeTo: .caption))
                        .foregroundStyle(MoaLogColor.ink)
                }
            }
        }
        .padding(16)
        .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(MoaLogColor.cardBorder))
        .accessibilityElement(children: .contain)
    }

    private var totalSalaryText: String {
        let values = model.state.sheet.incomes.map { $0.amountWon?.int64Value }
        guard values.count == 2, values.allSatisfy({ $0 != nil }) else { return "미입력" }
        guard let sum = safeSum(values.compactMap { $0 }) else { return "금액 범위 초과" }
        return formatWon(sum)
    }

    private var sectionHeader: some View {
        HStack(alignment: .bottom) {
            VStack(alignment: .leading, spacing: 3) {
                Text("대분류별 배분 계획")
                    .font(MoaLogFont.bold(17, relativeTo: .headline))
                Text("실제 월급 기준 · 금액은 자동 조정되지 않아요")
                    .font(MoaLogFont.regular(11, relativeTo: .caption2))
                    .foregroundStyle(MoaLogColor.mutedInk)
            }
            Spacer()
            Text("총 \(model.state.sheet.categories.count)개")
                .font(MoaLogFont.medium(11, relativeTo: .caption2))
                .foregroundStyle(MoaLogColor.mutedInk)
        }
    }

    private func categoryCard(_ category: SalaryAllocationCategory) -> some View {
        let target = model.targetAmount(category.id)
        let subtotalResult = childSubtotal(category.children)
        let difference = safeDifference(target, subtotalResult.value)
        let warns = difference.value != nil && difference.value != 0

        return VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Button {
                    detailCategory = AllocationCategoryRoute(categoryId: category.id, month: month)
                } label: {
                    HStack(spacing: 10) {
                    Image(systemName: allocationSymbol(category.name))
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(MoaLogColor.teal)
                        .frame(width: 38, height: 38)
                        .background(MoaLogColor.sage, in: Circle())
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(category.name)
                            .font(MoaLogFont.bold(15))
                            .foregroundStyle(MoaLogColor.ink)
                        Text(methodLabel(category))
                            .font(MoaLogFont.regular(10, relativeTo: .caption2))
                            .foregroundStyle(MoaLogColor.mutedInk)
                            .lineLimit(2)
                    }
                    }
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(category.name), \(methodLabel(category))")
                .accessibilityHint("세부 배분 항목을 확인합니다")

                Button { model.openCategory(category) } label: {
                    Image(systemName: "pencil")
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(category.name) 배분 방식 편집")
            }

            Button {
                detailCategory = AllocationCategoryRoute(categoryId: category.id, month: month)
            } label: {
                VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 8) {
                    allocationMetric("배분액", value: target.map(formatWon) ?? "계산 불가")
                    allocationMetric("세부 합계", value: subtotalText(category, subtotalResult))
                    allocationMetric(difference.value.map { $0 < 0 ? "초과" : "차액" } ?? "차액", value: differenceText(difference), warning: warns)
                }

                if warns, let value = difference.value {
                    Label("세부 합계와 \(formatMagnitude(value.magnitude))원 차이가 있어요. 자동 조정되지 않아요.", systemImage: "exclamationmark.triangle.fill")
                        .font(MoaLogFont.medium(10, relativeTo: .caption2))
                        .foregroundStyle(MoaLogColor.error)
                        .padding(10)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(MoaLogColor.errorSurface.opacity(0.45), in: RoundedRectangle(cornerRadius: 9))
                }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(category.name) 배분 상세")
            .accessibilityValue("배분액 \(target.map(formatWon) ?? "계산 불가"), 세부 합계 \(subtotalText(category, subtotalResult)), \(differenceText(difference))")
            .accessibilityHint("세부 배분 항목을 확인합니다")
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(warns ? MoaLogColor.error.opacity(0.6) : MoaLogColor.cardBorder))
    }

    private func allocationMetric(_ label: String, value: String, warning: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label)
                .font(MoaLogFont.regular(9, relativeTo: .caption2))
                .foregroundStyle(MoaLogColor.mutedInk)
            Text(value)
                .font(MoaLogFont.bold(10, relativeTo: .caption2))
                .foregroundStyle(warning ? MoaLogColor.error : MoaLogColor.teal)
                .minimumScaleFactor(0.65)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, minHeight: 42, alignment: .leading)
        .padding(.horizontal, 8)
        .background(MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 8))
    }

    private var emptyState: some View {
        VStack(spacing: 8) {
            Image(systemName: "wallet.bifold")
                .font(.system(size: 26, weight: .medium))
                .foregroundStyle(MoaLogColor.teal)
                .accessibilityHidden(true)
            Text("아직 배분 항목이 없어요")
                .font(MoaLogFont.bold(15))
            Text("이번 달 급여를 어디에 배분할지 추가해 주세요")
                .font(MoaLogFont.regular(12, relativeTo: .caption))
                .foregroundStyle(MoaLogColor.mutedInk)
        }
        .frame(maxWidth: .infinity, minHeight: 140)
        .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(MoaLogColor.cardBorder))
    }

    private func methodLabel(_ category: SalaryAllocationCategory) -> String {
        if category.method == .fixedamount {
            return "고정 \(category.amountWon.map { formatWon($0.int64Value) } ?? "미입력")"
        }
        if category.method == .salaryratio {
            let source = category.sourceMemberOrder == nil ? "총월급" : "선택 급여"
            return "\(source)의 \(basisPointText(category.rateBasisPoints?.int32Value))"
        }
        if category.method == .childtotal { return "하위 항목 합계" }
        return "상위 급여에서 차감한 잔액"
    }

    private func subtotalText(_ category: SalaryAllocationCategory, _ result: SafeOptionalSum) -> String {
        if result.overflow { return "범위 초과" }
        if category.children.isEmpty { return "항목 없음" }
        if result.hasMissing { return "미입력" }
        return result.value.map(formatWon) ?? "미입력"
    }

    private func differenceText(_ difference: SafeDifference) -> String {
        if difference.overflow { return "범위 초과" }
        guard let value = difference.value else { return "계산 불가" }
        return (value > 0 ? "+" : "") + formatWon(value)
    }
}

private struct SalaryEditorSheet: View {
    @ObservedObject var model: SalaryAllocationViewModel
    let setup: LedgerSetup

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    Text("은행 연동 없이 이번 달 실제 수령액을 직접 입력해 주세요.")
                        .font(MoaLogFont.regular(13, relativeTo: .caption))
                        .foregroundStyle(MoaLogColor.mutedInk)
                        .frame(maxWidth: .infinity, alignment: .leading)

                    ForEach(Array(setup.members.sorted { $0.order < $1.order }.enumerated()), id: \.element.order) { index, member in
                        labeledField(
                            label: "\(member.displayName) 월급",
                            value: Binding(
                                get: { model.state.salaryInputs.indices.contains(index) ? model.state.salaryInputs[index] : "" },
                                set: { model.changeSalary(memberOrder: member.order, value: $0) }
                            ),
                            suffix: "원"
                        )
                    }

                    HStack {
                        Text("직접 입력된 합계")
                        Spacer()
                        Text(inputTotal)
                            .font(MoaLogFont.bold(14))
                            .foregroundStyle(MoaLogColor.teal)
                    }
                    .padding(14)
                    .background(MoaLogColor.sage, in: RoundedRectangle(cornerRadius: 12))

                    Button(action: model.saveSalaries) {
                        Text(model.state.isSaving ? "저장 중…" : "저장하기")
                            .font(MoaLogFont.semibold(15))
                            .foregroundStyle(.white)
                            .frame(maxWidth: .infinity, minHeight: 52)
                            .background(MoaLogColor.teal, in: RoundedRectangle(cornerRadius: 12))
                    }
                    .buttonStyle(.plain)
                    .disabled(model.state.isSaving)
                }
                .padding(20)
            }
            .background(MoaLogColor.homeCanvas)
            .navigationTitle("월급 수정")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("닫기", action: model.closeSalaryEditor)
                        .font(MoaLogFont.semibold(14))
                }
            }
        }
    }

    private var inputTotal: String {
        guard model.state.salaryInputs.contains(where: { !$0.isEmpty }) else { return "미입력" }
        var values: [Int64] = []
        for input in model.state.salaryInputs where !input.isEmpty {
            guard let value = Int64(input) else { return "금액 범위 초과" }
            values.append(value)
        }
        return safeSum(values).map(formatWon) ?? "금액 범위 초과"
    }
}

private struct CategoryEditorSheet: View {
    @ObservedObject var model: SalaryAllocationViewModel
    let setup: LedgerSetup
    let draft: SalaryCategoryDraft

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    labeledField(
                        label: "이름",
                        value: Binding(get: { model.state.categoryDraft?.name ?? "" }, set: model.changeCategoryName),
                        placeholder: "예: 생활비"
                    )

                    choiceSection("급여 출처") {
                        HStack(spacing: 8) {
                            choiceButton("공동 예산", selected: model.state.categoryDraft?.sourceMemberOrder == nil, action: model.selectCommonSource)
                            ForEach(setup.members.sorted { $0.order < $1.order }, id: \.order) { member in
                                choiceButton("\(member.displayName) 급여", selected: model.state.categoryDraft?.sourceMemberOrder?.int32Value == member.order) {
                                    model.selectMemberSource(member.order)
                                }
                            }
                        }
                    }

                    choiceSection("계산 방식") {
                        VStack(spacing: 8) {
                            methodButton(.fixedamount, "직접 금액 입력")
                            methodButton(.salaryratio, "월급 비율")
                            methodButton(.childtotal, "하위 항목 합계")
                            methodButton(.remainingfromsource, "상위에서 차감한 잔액")
                        }
                    }

                    if let current = model.state.categoryDraft,
                       current.method == .remainingfromsource {
                        choiceSection("잔액에서 차감할 배분 항목") {
                            VStack(spacing: 8) {
                                if deductionCandidates(for: current).isEmpty {
                                    Text("같은 급여 출처의 다른 배분 항목이 없어요")
                                        .font(MoaLogFont.regular(12, relativeTo: .caption))
                                        .foregroundStyle(MoaLogColor.mutedInk)
                                        .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                                } else {
                                    ForEach(deductionCandidates(for: current), id: \.id) { category in
                                        deductionButton(category, draft: current)
                                    }
                                }
                            }
                        }
                    }

                    if let current = model.state.categoryDraft,
                       current.method == .fixedamount || current.method == .salaryratio {
                        labeledField(
                            label: current.method == .fixedamount ? "직접 금액" : "월급 비율",
                            value: Binding(get: { current.value }, set: model.changeCategoryValue),
                            placeholder: "0",
                            suffix: current.method == .fixedamount ? "원" : "%"
                        )
                    }

                    labeledField(
                        label: "거래수단·메모 (선택)",
                        value: Binding(get: { model.state.categoryDraft?.memo ?? "" }, set: model.changeMemo),
                        placeholder: "자동이체는 실행되지 않아요"
                    )

                    if let error = model.state.error {
                        Text(error)
                            .font(MoaLogFont.medium(12, relativeTo: .caption))
                            .foregroundStyle(MoaLogColor.error)
                            .accessibilityLabel("오류, \(error)")
                    }

                    if draft.id != 0 {
                        Button(role: .destructive, action: model.deleteCategory) {
                            Label("배분 항목 삭제", systemImage: "trash")
                                .font(MoaLogFont.semibold(14))
                                .frame(maxWidth: .infinity, minHeight: 48)
                        }
                    }

                    Button(action: model.saveCategory) {
                        Text(model.state.isSaving ? "저장 중…" : "저장하기")
                            .font(MoaLogFont.semibold(15))
                            .foregroundStyle(.white)
                            .frame(maxWidth: .infinity, minHeight: 52)
                            .background(MoaLogColor.teal, in: RoundedRectangle(cornerRadius: 12))
                    }
                    .buttonStyle(.plain)
                    .disabled(model.state.isSaving)
                }
                .padding(20)
            }
            .background(MoaLogColor.homeCanvas)
            .navigationTitle("배분 항목 편집")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("닫기", action: model.closeCategoryEditor)
                }
            }
        }
    }

    private func methodButton(_ method: SalaryAllocationMethod, _ title: String) -> some View {
        choiceButton(title, selected: model.state.categoryDraft?.method == method) { model.changeMethod(method) }
    }

    private func deductionCandidates(for draft: SalaryCategoryDraft) -> [SalaryAllocationCategory] {
        model.state.sheet.categories.filter { category in
            category.id != draft.id &&
                category.method != .remainingfromsource &&
                category.sourceMemberOrder?.int32Value == draft.sourceMemberOrder?.int32Value
        }
    }

    private func deductionButton(_ category: SalaryAllocationCategory, draft: SalaryCategoryDraft) -> some View {
        let selected = draft.deductedCategoryIds.contains(KotlinLong(value: category.id))
        return Button { model.toggleDeductedCategory(category.id) } label: {
            HStack(spacing: 10) {
                Image(systemName: selected ? "checkmark.square.fill" : "square")
                    .font(.system(size: 20, weight: .medium))
                    .foregroundStyle(selected ? MoaLogColor.teal : MoaLogColor.outline)
                    .accessibilityHidden(true)
                Text(category.name)
                    .font(MoaLogFont.medium(13, relativeTo: .caption))
                    .foregroundStyle(MoaLogColor.ink)
                Spacer()
                Text(methodShortLabel(category.method))
                    .font(MoaLogFont.regular(10, relativeTo: .caption2))
                    .foregroundStyle(MoaLogColor.mutedInk)
            }
            .padding(.horizontal, 12)
            .frame(maxWidth: .infinity, minHeight: 48)
            .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(selected ? MoaLogColor.teal : MoaLogColor.cardBorder))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(category.name) 차감")
        .accessibilityValue(selected ? "선택됨" : "선택 안 됨")
    }
}

private struct AllocationCategoryRoute: Hashable, Identifiable {
    let categoryId: Int64
    let month: YearMonthKey
    var id: String { "\(categoryId)-\(month.year)-\(month.month)" }

    static func == (lhs: Self, rhs: Self) -> Bool { lhs.id == rhs.id }
    func hash(into hasher: inout Hasher) { hasher.combine(id) }
}

@MainActor
private final class SalaryAllocationDetailViewModel: ObservableObject {
    @Published private(set) var state: SalaryAllocationDetailUiState
    private let store: IosSalaryAllocationDetailStore
    private var observation: IosObservation?

    init(dependencies: IosDependencies, categoryId: Int64, month: YearMonthKey) {
        let store = dependencies.salaryAllocationDetailStore(categoryId: categoryId, month: month)
        self.store = store
        state = store.currentState
        observation = store.observe { [weak self] state in
            DispatchQueue.main.async { self?.state = state }
        }
    }

    deinit { observation?.cancel(); store.close() }
    func add() { store.openNewChildEditor() }
    func edit(_ child: SalaryAllocationChild) { store.openChildEditor(child: child) }
    func closeEditor() { store.closeEditor() }
    func changeName(_ value: String) { store.changeName(value: value) }
    func changeAmount(_ value: String) { store.changeAmount(value: value) }
    func changeMemo(_ value: String) { store.changeMemo(value: value) }
    func save() { store.save() }
    func delete() { store.delete() }
    func toggle(_ child: SalaryAllocationChild) { store.toggleChild(childId: child.id) }
    func addGrandchild(to child: SalaryAllocationChild) { store.openNewGrandchildEditor(childId: child.id) }
    func editGrandchild(_ item: SalaryAllocationGrandchild, in child: SalaryAllocationChild) {
        store.openGrandchildEditor(childId: child.id, item: item)
    }
    func closeGrandchildEditor() { store.closeGrandchildEditor() }
    func changeGrandchildName(_ value: String) { store.changeGrandchildName(value: value) }
    func changeGrandchildAmount(_ value: String) { store.changeGrandchildAmount(value: value) }
    func changeGrandchildMemo(_ value: String) { store.changeGrandchildMemo(value: value) }
    func saveGrandchild() { store.saveGrandchild() }
    func deleteGrandchild() { store.deleteGrandchild() }
}

private struct SalaryAllocationDetailView: View {
    @StateObject private var model: SalaryAllocationDetailViewModel
    let onEditCategory: (SalaryAllocationCategory) -> Void

    init(
        dependencies: IosDependencies,
        categoryId: Int64,
        month: YearMonthKey,
        onEditCategory: @escaping (SalaryAllocationCategory) -> Void
    ) {
        _model = StateObject(wrappedValue: SalaryAllocationDetailViewModel(dependencies: dependencies, categoryId: categoryId, month: month))
        self.onEditCategory = onEditCategory
    }

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                summary

                HStack {
                    Text("배분 항목")
                        .font(MoaLogFont.bold(17, relativeTo: .headline))
                    Spacer()
                    Text("총 \(model.state.category?.children.count ?? 0)개")
                        .font(MoaLogFont.regular(11, relativeTo: .caption2))
                        .foregroundStyle(MoaLogColor.mutedInk)
                }

                if model.state.isLoading {
                    ProgressView().tint(MoaLogColor.teal).frame(minHeight: 120)
                } else if let children = model.state.category?.children, children.isEmpty {
                    Text("아직 세부 배분 항목이 없어요")
                        .font(MoaLogFont.medium(13, relativeTo: .caption))
                        .foregroundStyle(MoaLogColor.mutedInk)
                        .frame(maxWidth: .infinity, minHeight: 120)
                        .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 14))
                } else if let children = model.state.category?.children {
                    ForEach(children, id: \.id) { child in
                        childCard(child)
                    }
                }

                Button(action: model.add) {
                    Label("하위 항목 추가", systemImage: "plus.circle")
                        .font(MoaLogFont.semibold(14))
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(MoaLogColor.cardBorder, style: StrokeStyle(lineWidth: 1, dash: [5])))
                }
                .buttonStyle(.plain)

                if let error = model.state.error {
                    Text(error)
                        .font(MoaLogFont.medium(12, relativeTo: .caption))
                        .foregroundStyle(MoaLogColor.error)
                        .accessibilityLabel("오류, \(error)")
                }
            }
            .padding(20)
            .padding(.bottom, 20)
        }
        .background(MoaLogColor.homeCanvas.ignoresSafeArea())
        .navigationTitle(model.state.category.map { "\($0.name) 배분 상세" } ?? "배분 상세")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: editorBinding) {
            if let draft = model.state.draft {
                ChildEditorSheet(model: model, draft: draft)
                    .presentationDetents([.medium, .large])
            }
        }
        .sheet(isPresented: grandchildEditorBinding) {
            if let draft = model.state.grandchildDraft {
                GrandchildEditorSheet(model: model, draft: draft)
                    .presentationDetents([.medium, .large])
            }
        }
    }

    private var editorBinding: Binding<Bool> {
        Binding(get: { model.state.draft != nil }, set: { if !$0 { model.closeEditor() } })
    }

    private var grandchildEditorBinding: Binding<Bool> {
        Binding(get: { model.state.grandchildDraft != nil }, set: { if !$0 { model.closeGrandchildEditor() } })
    }

    private func childCard(_ child: SalaryAllocationChild) -> some View {
        let expanded = model.state.expandedChildIds.contains(KotlinLong(value: child.id))
        let hasGrandchildren = !child.grandchildren.isEmpty
        return VStack(spacing: 0) {
            HStack(spacing: 10) {
                Button { model.toggle(child) } label: {
                    HStack(spacing: 10) {
                        Image(systemName: allocationSymbol(child.name))
                            .font(.system(size: 15, weight: .medium))
                            .foregroundStyle(MoaLogColor.teal)
                            .frame(width: 38, height: 38)
                            .background(MoaLogColor.sage, in: Circle())
                            .accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(child.name)
                                .font(MoaLogFont.semibold(14))
                                .foregroundStyle(MoaLogColor.ink)
                            if let memo = child.memo, !memo.isEmpty {
                                Text(memo)
                                    .font(MoaLogFont.regular(10, relativeTo: .caption2))
                                    .foregroundStyle(MoaLogColor.mutedInk)
                                    .lineLimit(1)
                            }
                        }
                        Spacer()
                        Text(childTotalText(child))
                            .font(MoaLogFont.bold(13, relativeTo: .caption))
                            .foregroundStyle(childTotalResult(child).value == nil ? MoaLogColor.error : MoaLogColor.ink)
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                        Image(systemName: expanded ? "chevron.up" : "chevron.down")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(MoaLogColor.outline)
                            .accessibilityHidden(true)
                    }
                    .frame(maxWidth: .infinity, minHeight: 58)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(child.name), \(childTotalText(child))")
                .accessibilityValue(expanded ? "펼쳐짐" : "접힘")
                .accessibilityHint(hasGrandchildren ? "세부 항목을 펼치거나 접습니다" : "세부 항목 추가 영역을 펼칩니다")

                Button { model.edit(child) } label: {
                    Image(systemName: "pencil")
                        .frame(width: 44, height: 52)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(child.name) 편집")
            }
            .padding(.horizontal, 10)

            if expanded {
                Divider().padding(.horizontal, 12)
                VStack(spacing: 8) {
                    ForEach(child.grandchildren, id: \.id) { item in
                        Button { model.editGrandchild(item, in: child) } label: {
                            HStack(spacing: 10) {
                                Image(systemName: "circle.grid.2x2.fill")
                                    .font(.system(size: 11, weight: .semibold))
                                    .foregroundStyle(MoaLogColor.teal)
                                    .frame(width: 32, height: 32)
                                    .background(MoaLogColor.sage, in: Circle())
                                    .accessibilityHidden(true)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(item.name)
                                        .font(MoaLogFont.semibold(12, relativeTo: .caption))
                                        .foregroundStyle(MoaLogColor.ink)
                                    if let memo = item.memo, !memo.isEmpty {
                                        Text(memo)
                                            .font(MoaLogFont.regular(9, relativeTo: .caption2))
                                            .foregroundStyle(MoaLogColor.mutedInk)
                                            .lineLimit(1)
                                    }
                                }
                                Spacer()
                                Text(item.amountWon.map { formatWon($0.int64Value) } ?? "미입력")
                                    .font(MoaLogFont.bold(11, relativeTo: .caption2))
                                    .foregroundStyle(item.amountWon == nil ? MoaLogColor.error : MoaLogColor.ink)
                            }
                            .padding(.horizontal, 10)
                            .frame(maxWidth: .infinity, minHeight: 48)
                            .background(MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 9))
                        }
                        .buttonStyle(.plain)
                        .accessibilityHint("세부 항목을 편집합니다")
                    }

                    Button { model.addGrandchild(to: child) } label: {
                        Label("\(child.name) 세부 추가", systemImage: "plus")
                            .font(MoaLogFont.semibold(11, relativeTo: .caption2))
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.plain)
                }
                .padding(10)
            }
        }
        .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(MoaLogColor.cardBorder))
    }

    private func childTotalResult(_ child: SalaryAllocationChild) -> SafeOptionalSum {
        if child.grandchildren.isEmpty {
            return SafeOptionalSum(
                value: child.amountWon?.int64Value,
                hasMissing: child.amountWon == nil,
                overflow: false
            )
        }
        let values = child.grandchildren.map { $0.amountWon?.int64Value }
        guard !values.contains(where: { $0 == nil }) else {
            return SafeOptionalSum(value: nil, hasMissing: true, overflow: false)
        }
        guard let sum = safeSum(values.compactMap { $0 }) else {
            return SafeOptionalSum(value: nil, hasMissing: false, overflow: true)
        }
        return SafeOptionalSum(value: sum, hasMissing: false, overflow: false)
    }

    private func childTotalText(_ child: SalaryAllocationChild) -> String {
        let result = childTotalResult(child)
        if result.overflow { return "금액 범위 초과" }
        guard let value = result.value else { return "미입력" }
        return formatWon(value)
    }

    private var summary: some View {
        let target = model.state.targetAmountWon?.int64Value
        let subtotal = childSubtotal(model.state.category?.children ?? [])
        let difference = safeDifference(target, subtotal.value)
        return VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(model.state.category?.name ?? "배분 상세")
                    .font(MoaLogFont.semibold(13, relativeTo: .caption))
                    .foregroundStyle(MoaLogColor.mutedInk)
                Spacer()
                if let category = model.state.category {
                    Text(methodShortLabel(category.method))
                        .font(MoaLogFont.medium(10, relativeTo: .caption2))
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(MoaLogColor.sage, in: Capsule())
                    Button {
                        onEditCategory(category)
                    } label: {
                        Text("편집")
                            .font(MoaLogFont.semibold(12, relativeTo: .caption))
                            .frame(minWidth: 44, minHeight: 44)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(category.name) 계산 방식 편집")
                }
            }
            HStack {
                Text("목표 배분액")
                    .font(MoaLogFont.medium(12, relativeTo: .caption))
                Spacer()
                Text(target.map(formatWon) ?? "계산 불가")
                    .font(MoaLogFont.bold(24, relativeTo: .title2))
                    .foregroundStyle(MoaLogColor.ink)
            }
            GeometryReader { proxy in
                let ratio = progressRatio(target: target, subtotal: subtotal.value)
                ZStack(alignment: .leading) {
                    Capsule().fill(MoaLogColor.homeBorder)
                    Capsule().fill(MoaLogColor.teal).frame(width: proxy.size.width * ratio)
                }
            }
            .frame(height: 5)
            HStack {
                Text("목표 \(target.map(formatWon) ?? "계산 불가")")
                Spacer()
                Text(difference.overflow ? "범위 초과" : difference.value.map { "차액 \(formatWon($0))" } ?? "계산 불가")
                    .foregroundStyle(difference.value == 0 ? MoaLogColor.mutedInk : MoaLogColor.error)
            }
            .font(MoaLogFont.regular(10, relativeTo: .caption2))
            .foregroundStyle(MoaLogColor.mutedInk)
        }
        .padding(16)
        .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(difference.value == 0 ? MoaLogColor.cardBorder : MoaLogColor.error.opacity(0.5)))
        .accessibilityElement(children: .contain)
    }
}

private struct ChildEditorSheet: View {
    @ObservedObject var model: SalaryAllocationDetailViewModel
    let draft: SalaryChildDraft

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    labeledField(label: "항목명", value: Binding(get: { model.state.draft?.name ?? "" }, set: model.changeName), placeholder: "예: 수아 ISA")
                    if usesDerivedSubtotal {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("금액")
                                .font(MoaLogFont.medium(12, relativeTo: .caption))
                                .foregroundStyle(MoaLogColor.mutedInk)
                            HStack {
                                Text("세부 항목 합계로 자동 계산돼요")
                                    .font(MoaLogFont.regular(14))
                                    .foregroundStyle(MoaLogColor.mutedInk)
                                Spacer()
                                if let subtotal = derivedSubtotal {
                                    Text(formatWon(subtotal))
                                        .font(MoaLogFont.semibold(14))
                                        .foregroundStyle(MoaLogColor.ink)
                                }
                            }
                            .frame(minHeight: 44)
                            .padding(.horizontal, 14)
                            .background(MoaLogColor.homeBorder.opacity(0.55), in: RoundedRectangle(cornerRadius: 12))
                        }
                        .accessibilityElement(children: .combine)
                        .accessibilityLabel("금액, 세부 항목 합계로 자동 계산")
                        .accessibilityValue(derivedSubtotal.map(formatWon) ?? "계산 불가")
                    } else {
                        labeledField(label: "금액", value: Binding(get: { model.state.draft?.amount ?? "" }, set: model.changeAmount), placeholder: "미입력 가능", suffix: "원")
                    }
                    labeledField(label: "거래수단·메모 (선택)", value: Binding(get: { model.state.draft?.memo ?? "" }, set: model.changeMemo), placeholder: "예: 국민은행")

                    if let error = model.state.error {
                        Text(error)
                            .font(MoaLogFont.medium(12, relativeTo: .caption))
                            .foregroundStyle(MoaLogColor.error)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .accessibilityLabel("오류, \(error)")
                    }

                    if draft.id != 0 {
                        Button(role: .destructive, action: model.delete) {
                            Label("항목 삭제", systemImage: "trash")
                                .font(MoaLogFont.semibold(14))
                                .frame(maxWidth: .infinity, minHeight: 48)
                        }
                    }

                    Button(action: model.save) {
                        Text(model.state.isSaving ? "저장 중…" : "저장하기")
                            .font(MoaLogFont.semibold(15))
                            .foregroundStyle(.white)
                            .frame(maxWidth: .infinity, minHeight: 52)
                            .background(MoaLogColor.teal, in: RoundedRectangle(cornerRadius: 12))
                    }
                    .buttonStyle(.plain)
                    .disabled(model.state.isSaving)
                }
                .padding(20)
            }
            .background(MoaLogColor.homeCanvas)
            .navigationTitle("세부 항목 편집")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("닫기", action: model.closeEditor) } }
        }
    }

    private var editedChild: SalaryAllocationChild? {
        guard draft.id != 0 else { return nil }
        return model.state.category?.children.first { $0.id == draft.id }
    }

    private var usesDerivedSubtotal: Bool {
        draft.usesGrandchildSubtotal
    }

    private var derivedSubtotal: Int64? {
        guard let child = editedChild else { return nil }
        let values = child.grandchildren.map { $0.amountWon?.int64Value }
        guard !values.contains(where: { $0 == nil }) else { return nil }
        return safeSum(values.compactMap { $0 })
    }
}

private struct GrandchildEditorSheet: View {
    @ObservedObject var model: SalaryAllocationDetailViewModel
    let draft: SalaryGrandchildDraft

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    labeledField(
                        label: "세부 항목명",
                        value: Binding(get: { model.state.grandchildDraft?.name ?? "" }, set: model.changeGrandchildName),
                        placeholder: "예: 수아 ISA"
                    )
                    labeledField(
                        label: "금액",
                        value: Binding(get: { model.state.grandchildDraft?.amount ?? "" }, set: model.changeGrandchildAmount),
                        placeholder: "미입력 가능",
                        suffix: "원"
                    )
                    labeledField(
                        label: "거래수단·메모 (선택)",
                        value: Binding(get: { model.state.grandchildDraft?.memo ?? "" }, set: model.changeGrandchildMemo),
                        placeholder: "예: 국민은행"
                    )

                    if let error = model.state.error {
                        Text(error)
                            .font(MoaLogFont.medium(12, relativeTo: .caption))
                            .foregroundStyle(MoaLogColor.error)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .accessibilityLabel("오류, \(error)")
                    }

                    if draft.id != 0 {
                        Button(role: .destructive, action: model.deleteGrandchild) {
                            Label("세부 항목 삭제", systemImage: "trash")
                                .font(MoaLogFont.semibold(14))
                                .frame(maxWidth: .infinity, minHeight: 48)
                        }
                    }

                    Button(action: model.saveGrandchild) {
                        Text(model.state.isSaving ? "저장 중…" : "저장하기")
                            .font(MoaLogFont.semibold(15))
                            .foregroundStyle(.white)
                            .frame(maxWidth: .infinity, minHeight: 52)
                            .background(MoaLogColor.teal, in: RoundedRectangle(cornerRadius: 12))
                    }
                    .buttonStyle(.plain)
                    .disabled(model.state.isSaving)
                }
                .padding(20)
            }
            .background(MoaLogColor.homeCanvas)
            .navigationTitle("세부 항목 편집")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("닫기", action: model.closeGrandchildEditor)
                }
            }
        }
    }
}

private func labeledField(
    label: String,
    value: Binding<String>,
    placeholder: String = "",
    suffix: String? = nil
) -> some View {
    VStack(alignment: .leading, spacing: 6) {
        Text(label)
            .font(MoaLogFont.semibold(12, relativeTo: .caption))
            .foregroundStyle(MoaLogColor.ink)
        HStack {
            TextField(placeholder, text: value)
                .font(MoaLogFont.regular(15))
                .keyboardType(suffix == nil ? .default : .decimalPad)
                .accessibilityLabel(label)
            if let suffix {
                Text(suffix)
                    .font(MoaLogFont.medium(13, relativeTo: .caption))
                    .foregroundStyle(MoaLogColor.mutedInk)
            }
        }
        .padding(.horizontal, 12)
        .frame(minHeight: 52)
        .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(MoaLogColor.cardBorder))
    }
}

private func choiceSection<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
    VStack(alignment: .leading, spacing: 7) {
        Text(title).font(MoaLogFont.semibold(12, relativeTo: .caption))
        content()
    }
}

private func choiceButton(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
    Button(action: action) {
        HStack(spacing: 5) {
            Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                .font(.system(size: 14))
                .accessibilityHidden(true)
            Text(title).lineLimit(1).minimumScaleFactor(0.75)
        }
        .font(MoaLogFont.medium(12, relativeTo: .caption))
        .foregroundStyle(selected ? MoaLogColor.teal : MoaLogColor.mutedInk)
        .frame(maxWidth: .infinity, minHeight: 48)
        .background(selected ? MoaLogColor.sage : MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(selected ? MoaLogColor.teal : MoaLogColor.cardBorder))
    }
    .buttonStyle(.plain)
    .accessibilityAddTraits(selected ? .isSelected : [])
}

private struct SafeOptionalSum {
    let value: Int64?
    let hasMissing: Bool
    let overflow: Bool
}

private struct SafeDifference {
    let value: Int64?
    let overflow: Bool
}

private func childSubtotal(_ children: [SalaryAllocationChild]) -> SafeOptionalSum {
    guard !children.isEmpty else { return SafeOptionalSum(value: nil, hasMissing: false, overflow: false) }
    var values: [Int64] = []
    for child in children {
        if child.grandchildren.isEmpty {
            guard let amount = child.amountWon?.int64Value else {
                return SafeOptionalSum(value: nil, hasMissing: true, overflow: false)
            }
            values.append(amount)
        } else {
            let grandchildValues = child.grandchildren.map { $0.amountWon?.int64Value }
            guard !grandchildValues.contains(where: { $0 == nil }) else {
                return SafeOptionalSum(value: nil, hasMissing: true, overflow: false)
            }
            guard let subtotal = safeSum(grandchildValues.compactMap { $0 }) else {
                return SafeOptionalSum(value: nil, hasMissing: false, overflow: true)
            }
            values.append(subtotal)
        }
    }
    guard let sum = safeSum(values) else { return SafeOptionalSum(value: nil, hasMissing: false, overflow: true) }
    return SafeOptionalSum(value: sum, hasMissing: false, overflow: false)
}

private func safeSum(_ values: [Int64]) -> Int64? {
    var total: Int64 = 0
    for value in values {
        let result = total.addingReportingOverflow(value)
        guard !result.overflow else { return nil }
        total = result.partialValue
    }
    return total
}

private func safeDifference(_ lhs: Int64?, _ rhs: Int64?) -> SafeDifference {
    guard let lhs, let rhs else { return SafeDifference(value: nil, overflow: false) }
    let result = lhs.subtractingReportingOverflow(rhs)
    return result.overflow ? SafeDifference(value: nil, overflow: true) : SafeDifference(value: result.partialValue, overflow: false)
}

private func basisPointText(_ value: Int32?) -> String {
    guard let value else { return "미입력" }
    let whole = value / 100
    let remainder = value % 100
    return remainder == 0 ? "\(whole)%" : "\(whole).\(String(format: "%02d", remainder).replacingOccurrences(of: "0$", with: "", options: .regularExpression))%"
}

private func methodShortLabel(_ method: SalaryAllocationMethod) -> String {
    if method == .fixedamount { return "고정 금액" }
    if method == .salaryratio { return "급여 비율" }
    if method == .childtotal { return "하위 합계" }
    return "잔액"
}

private func allocationSymbol(_ name: String) -> String {
    if name.contains("투자") || name.contains("ISA") || name.contains("주식") { return "chart.line.uptrend.xyaxis" }
    if name.contains("통장") || name.contains("비상") { return "building.columns.fill" }
    if name.contains("고정") || name.contains("생활") { return "house.fill" }
    return "wallet.bifold.fill"
}

private func progressRatio(target: Int64?, subtotal: Int64?) -> CGFloat {
    guard let target, let subtotal, target > 0, subtotal >= 0 else { return 0 }
    return CGFloat(min(Double(subtotal) / Double(target), 1))
}

private func formatMagnitude(_ value: UInt64) -> String {
    let formatter = NumberFormatter()
    formatter.numberStyle = .decimal
    formatter.groupingSeparator = ","
    return formatter.string(from: NSNumber(value: value)) ?? String(value)
}

private func previousMonth(_ month: YearMonthKey) -> YearMonthKey {
    if month.year == 1900 && month.month == 1 { return month }
    return month.month == 1
        ? YearMonthKey(year: month.year - 1, month: 12)
        : YearMonthKey(year: month.year, month: month.month - 1)
}

private func nextMonth(_ month: YearMonthKey) -> YearMonthKey {
    if month.year == 9999 && month.month == 12 { return month }
    return month.month == 12
        ? YearMonthKey(year: month.year + 1, month: 1)
        : YearMonthKey(year: month.year, month: month.month + 1)
}
