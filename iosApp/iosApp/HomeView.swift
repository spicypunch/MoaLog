import SharedKit
import SwiftUI
import Foundation

@MainActor
final class HomeViewModel: ObservableObject {
    @Published private(set) var state: HomeUiState

    private let store: IosHomeStore
    private var observation: IosObservation?

    init(dependencies: IosDependencies, setup: LedgerSetup) {
        let store = dependencies.homeStore(setup: setup)
        self.store = store
        state = store.currentState
        observation = store.observe(observer: { [weak self] state in
            DispatchQueue.main.async {
                self?.state = state
            }
        })
    }

    deinit {
        observation?.cancel()
        store.close()
    }

    func retry() {
        store.retry()
    }

    func selectYear(_ year: Int) {
        store.selectYear(year: Int32(year))
    }
}

enum MainTab: String, CaseIterable {
    case home
    case plan
    case records
    case assets
    case more

    var title: String {
        switch self {
        case .home: "홈"
        case .plan: "계획"
        case .records: "기록"
        case .assets: "자산"
        case .more: "더보기"
        }
    }

    var symbol: String {
        switch self {
        case .home: "house.fill"
        case .plan: "calendar"
        case .records: "plus.circle.fill"
        case .assets: "wallet.bifold"
        case .more: "ellipsis"
        }
    }
}

struct MainTabView: View {
    let dependencies: IosDependencies
    let setup: LedgerSetup

    @State private var selectedTab = MainTab.home
    @State private var planRoute: PlanLinkedRoute?
    @State private var compositionRoute: CompositionRoute?
    @State private var goalEditPresented = false
    @State private var goalEditYear = Calendar.current.component(.year, from: Date())
    @State private var recordsSelectionRequest: RecordsSelectionRequest?
    @State private var expenseEditorRoute: ExpenseEditorRoute?
    @State private var planEditorRoute: PlanEditorRoute?
    @State private var multiMonthApplyRoute: MultiMonthApplyRoute?
    @State private var pendingMultiMonthApplyRoute: MultiMonthApplyRoute?
    @State private var planSelectionRequest: PlanSelectionRequest?
    @SceneStorage("moalog.p07.route.v1") private var persistedMultiMonthRoute = ""
    @SceneStorage("moalog.p07.draft.v1") private var persistedMultiMonthDraft = ""
    @SceneStorage("moalog.expense.editor.route.v1") private var persistedExpenseEditorRoute = ""

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                HomeView(
                    dependencies: dependencies,
                    setup: setup,
                    onEditGoal: { year in
                        goalEditYear = year
                        goalEditPresented = true
                    },
                    onOpenAnnual: { year, section in planRoute = .annual(year: year, section: section) },
                    onOpenComposition: { month in compositionRoute = CompositionRoute(month: month) },
                    onAddExpense: { month in
                        presentExpenseEditor(ExpenseEditorRoute(recordId: nil, initialMonth: month, origin: .home))
                    },
                    onOpenOverspent: { month in
                        recordsSelectionRequest = RecordsSelectionRequest(month: month, section: .overspent)
                        selectedTab = .records
                    }
                )
                .opacity(selectedTab == .home ? 1 : 0)
                .allowsHitTesting(selectedTab == .home)
                .accessibilityHidden(selectedTab != .home)

                PlanView(
                    dependencies: dependencies,
                    setup: setup,
                    selectionRequest: planSelectionRequest,
                    onOpenAnnual: { year in planRoute = .annual(year: year, section: .income) },
                    onOpenSalaryAllocation: { month in planRoute = .salaryAllocation(month: month) },
                    onOpenFixedCosts: { month in planRoute = .fixedCosts(month: month) },
                    onOpenItem: { item in
                        planEditorRoute = PlanEditorRoute(
                            itemId: item.id,
                            type: item.type,
                            month: item.attributionMonth
                        )
                    },
                    onAddItem: { tab, month in
                        guard let type = tab.editorType else { return }
                        planEditorRoute = PlanEditorRoute(itemId: nil, type: type, month: month)
                    },
                    onApplyMultipleMonths: { tab, month in
                        guard let type = tab.editorType else { return }
                        presentMultiMonthApply(
                            MultiMonthApplyRoute(type: type, month: month, itemId: nil),
                            clearDraft: true
                        )
                    },
                    onOpenRecords: { month in
                        recordsSelectionRequest = RecordsSelectionRequest(month: month, section: .variableExpense)
                        selectedTab = .records
                    }
                )
                .opacity(selectedTab == .plan ? 1 : 0)
                .allowsHitTesting(selectedTab == .plan)
                .accessibilityHidden(selectedTab != .plan)

                switch selectedTab {
                case .home:
                    EmptyView()
                case .plan:
                    EmptyView()
                case .records:
                    RecordsTabView(
                        dependencies: dependencies,
                        selectionRequest: recordsSelectionRequest,
                        onSelectionRequestConsumed: { id in
                            if recordsSelectionRequest?.id == id { recordsSelectionRequest = nil }
                        },
                        onAdd: { month in
                            presentExpenseEditor(ExpenseEditorRoute(recordId: nil, initialMonth: month, origin: .records))
                        },
                        onEdit: { record, month in
                            presentExpenseEditor(ExpenseEditorRoute(recordId: record.id, initialMonth: month, origin: .records))
                        }
                    )
                case .assets:
                    AssetsTabView(
                        dependencies: dependencies,
                        setup: setup,
                        onOpenSavings: { month in
                            planSelectionRequest = PlanSelectionRequest(month: month, tab: .savings)
                            selectedTab = .plan
                        }
                    )
                case .more:
                    MoreTabView(
                        dependencies: dependencies,
                        setup: setup,
                        onGuideDestination: openGuideDestination
                    )
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            bottomBar
                .zIndex(10)
        }
        .background(MoaLogColor.homeCanvas.ignoresSafeArea())
        .fullScreenCover(item: $planRoute) { route in
            switch route {
            case let .annual(year, section):
                AnnualPlanView(
                    dependencies: dependencies,
                    setup: setup,
                    year: year,
                    initialSection: section,
                    onClose: { planRoute = nil },
                    onOpenMonth: { month, selectedSection in
                        planSelectionRequest = PlanSelectionRequest(month: month, tab: selectedSection.planTab)
                        selectedTab = .plan
                        planRoute = nil
                    },
                    onOpenEditor: { itemId, type, month in
                        planRoute = nil
                        DispatchQueue.main.async {
                            planEditorRoute = PlanEditorRoute(itemId: itemId, type: type, month: month)
                        }
                    },
                    onOpenRecords: { month, section in
                        recordsSelectionRequest = RecordsSelectionRequest(month: month, section: .annual(section))
                        selectedTab = .records
                        planRoute = nil
                    }
                )
            case let .salaryAllocation(month):
                SalaryAllocationView(
                    dependencies: dependencies,
                    setup: setup,
                    initialMonth: month,
                    onClose: { selectedMonth in
                        planSelectionRequest = PlanSelectionRequest(month: selectedMonth, tab: nil)
                        planRoute = nil
                    }
                )
            case let .fixedCosts(month):
                FixedCostCheckView(
                    dependencies: dependencies,
                    setup: setup,
                    initialMonth: month,
                    onClose: { selectedMonth in
                        planSelectionRequest = PlanSelectionRequest(month: selectedMonth, tab: .fixedExpense)
                        planRoute = nil
                    },
                    onApplied: { appliedMonth in
                        planSelectionRequest = PlanSelectionRequest(month: appliedMonth, tab: .fixedExpense)
                        selectedTab = .plan
                        planRoute = nil
                    }
                )
            }
        }
        .fullScreenCover(item: $compositionRoute) { route in
            CompositionAnalysisView(
                dependencies: dependencies,
                initialMonth: route.month,
                onClose: { compositionRoute = nil },
                onOpenRecords: { month, categoryId in
                    recordsSelectionRequest = RecordsSelectionRequest(
                        month: month,
                        categoryId: categoryId,
                        section: .variableExpense
                    )
                    selectedTab = .records
                    compositionRoute = nil
                },
                onOpenAnnual: { year, section in
                    compositionRoute = nil
                    DispatchQueue.main.async {
                        planRoute = .annual(year: year, section: section)
                    }
                }
            )
        }
        .sheet(isPresented: $goalEditPresented) {
            GoalEditView(
                dependencies: dependencies,
                setup: setup,
                year: goalEditYear,
                onClose: { goalEditPresented = false },
                onSaved: { goalEditPresented = false }
            )
        }
        .fullScreenCover(item: $planEditorRoute, onDismiss: {
            if let pendingMultiMonthApplyRoute {
                self.pendingMultiMonthApplyRoute = nil
                presentMultiMonthApply(pendingMultiMonthApplyRoute, clearDraft: true)
            }
        }) { route in
            PlanEditorView(
                dependencies: dependencies,
                setup: setup,
                route: route,
                onClose: { planEditorRoute = nil },
                onSaveAndApply: { completion in
                    pendingMultiMonthApplyRoute = MultiMonthApplyRoute(
                        type: completion.type,
                        month: completion.month,
                        itemId: completion.itemId
                    )
                    planEditorRoute = nil
                }
            )
        }
        .fullScreenCover(item: $multiMonthApplyRoute) { route in
            MultiMonthApplyView(
                dependencies: dependencies,
                route: route,
                onApplied: clearPersistedMultiMonthFlow,
                onCancel: {
                    clearPersistedMultiMonthFlow()
                    multiMonthApplyRoute = nil
                },
                onCompleted: {
                    planSelectionRequest = PlanSelectionRequest(month: route.month, tab: .from(route.type))
                    selectedTab = .plan
                    clearPersistedMultiMonthFlow()
                    multiMonthApplyRoute = nil
                }
            )
        }
        .fullScreenCover(item: $expenseEditorRoute) { route in
            ExpenseEditorView(
                dependencies: dependencies,
                route: route,
                onCancel: {
                    clearPersistedExpenseEditor()
                    expenseEditorRoute = nil
                },
                onCompleted: { month in
                    recordsSelectionRequest = RecordsSelectionRequest(month: month, section: .variableExpense)
                    selectedTab = .records
                    clearPersistedExpenseEditor()
                    expenseEditorRoute = nil
                }
            )
        }
        .onAppear {
            restoreMultiMonthRouteIfNeeded()
            restoreExpenseEditorRouteIfNeeded()
        }
    }

    private func presentMultiMonthApply(_ route: MultiMonthApplyRoute, clearDraft: Bool) {
        if clearDraft { persistedMultiMonthDraft = "" }
        persistedMultiMonthRoute = PersistedMultiMonthApplyRoute(route).encoded
        multiMonthApplyRoute = route
    }

    private func restoreMultiMonthRouteIfNeeded() {
        guard multiMonthApplyRoute == nil,
              let persisted = PersistedMultiMonthApplyRoute.decode(persistedMultiMonthRoute),
              let route = persisted.route else { return }
        selectedTab = .plan
        multiMonthApplyRoute = route
    }

    private func clearPersistedMultiMonthFlow() {
        persistedMultiMonthRoute = ""
        persistedMultiMonthDraft = ""
    }

    private func presentExpenseEditor(_ route: ExpenseEditorRoute) {
        persistedExpenseEditorRoute = PersistedExpenseEditorRoute(route).encoded
        expenseEditorRoute = route
    }

    private func restoreExpenseEditorRouteIfNeeded() {
        guard expenseEditorRoute == nil,
              let persisted = PersistedExpenseEditorRoute.decode(persistedExpenseEditorRoute),
              let route = persisted.route else { return }
        selectedTab = route.origin == .records ? .records : .home
        expenseEditorRoute = route
    }

    private func clearPersistedExpenseEditor() {
        persistedExpenseEditorRoute = ""
    }

    private func openGuideDestination(_ destination: GuideDestination) {
        let month = currentCalendarMonth
        switch destination {
        case .salaryAllocation:
            planRoute = .salaryAllocation(month: month)
        case .fixedCostCheck:
            planRoute = .fixedCosts(month: month)
        case .monthlyPlan:
            planSelectionRequest = PlanSelectionRequest(month: month, tab: nil)
            selectedTab = .plan
        case .records:
            recordsSelectionRequest = RecordsSelectionRequest(month: month, section: .variableExpense)
            selectedTab = .records
        case .assets:
            selectedTab = .assets
        }
    }

    private var currentCalendarMonth: YearMonthKey {
        let now = Date(), calendar = Calendar.current
        return YearMonthKey(
            year: Int32(calendar.component(.year, from: now)),
            month: Int32(calendar.component(.month, from: now))
        )
    }

    private var bottomBar: some View {
        HStack(spacing: 0) {
            ForEach(MainTab.allCases, id: \.self) { tab in
                Button {
                    selectedTab = tab
                } label: {
                    VStack(spacing: tab == .records ? 2 : 3) {
                        Image(systemName: tab.symbol)
                            .font(.system(size: tab == .records ? 31 : 21, weight: .semibold))
                            .foregroundStyle(tab == .records ? .white : tab == selectedTab ? MoaLogColor.teal : MoaLogColor.mutedInk)
                            .frame(width: tab == .records ? 44 : 32, height: tab == .records ? 44 : 28)
                            .background(tab == .records ? MoaLogColor.teal : .clear, in: Circle())
                            .shadow(color: tab == .records ? MoaLogColor.teal.opacity(0.22) : .clear, radius: 6, y: 3)

                        Text(tab.title)
                            .font(MoaLogFont.medium(11, relativeTo: .caption2))
                            .foregroundStyle(tab == selectedTab ? MoaLogColor.teal : MoaLogColor.mutedInk)
                    }
                    .offset(y: tab == .records ? -9 : 0)
                    .frame(maxWidth: .infinity, minHeight: 64)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(tab.title)
                .accessibilityIdentifier("main-tab-\(tab.rawValue)")
                .accessibilityAddTraits(tab == selectedTab ? .isSelected : [])
            }
        }
        .frame(height: 64)
        .background(MoaLogColor.surface)
        .overlay(alignment: .top) {
            Rectangle().fill(MoaLogColor.homeBorder).frame(height: 1)
        }
    }
}

enum PlanLinkedRoute: Identifiable {
    case annual(year: Int, section: NativeAnnualSection)
    case salaryAllocation(month: YearMonthKey)
    case fixedCosts(month: YearMonthKey)

    var id: String {
        switch self {
        case let .annual(year, section): "annual-\(year)-\(section.title)"
        case let .salaryAllocation(month): "salary-\(month.year)-\(month.month)"
        case let .fixedCosts(month): "fixed-\(month.year)-\(month.month)"
        }
    }

    var title: String {
        switch self {
        case .annual: "연간 한눈에 보기"
        case .salaryAllocation: "월급 배분"
        case .fixedCosts: "고정비 점검"
        }
    }

    var symbol: String {
        switch self {
        case .annual: "calendar.badge.clock"
        case .salaryAllocation: "building.columns"
        case .fixedCosts: "doc.text"
        }
    }
}

struct CompositionRoute: Identifiable {
    let month: YearMonthKey
    var id: String { "composition-\(month.year)-\(month.month)" }
}

enum NativeRecordsSection: Equatable {
    case variableExpense
    case overspent
    case annual(NativeAnnualSection)

    var title: String {
        switch self {
        case .variableExpense: "변동지출"
        case .overspent: "과소비"
        case let .annual(section): section.title
        }
    }

    var overspentOnly: Bool { self == .overspent }
}

struct RecordsSelectionRequest {
    let id = UUID()
    let month: YearMonthKey
    let categoryId: String?
    let section: NativeRecordsSection

    init(month: YearMonthKey, categoryId: String? = nil, section: NativeRecordsSection) {
        self.month = month
        self.categoryId = categoryId
        self.section = section
    }
}

struct MultiMonthApplyRoute: Identifiable {
    let type: PlanItemType
    let month: YearMonthKey
    let itemId: Int64?

    var id: String {
        "\(itemId.map(String.init) ?? "all")-\(type.name)-\(month.year)-\(month.month)"
    }
}

private struct PersistedMultiMonthApplyRoute: Codable {
    let typeName: String
    let year: Int32
    let month: Int32
    let itemId: Int64?

    init(_ route: MultiMonthApplyRoute) {
        typeName = route.type.name
        year = route.month.year
        month = route.month.month
        itemId = route.itemId
    }

    var route: MultiMonthApplyRoute? {
        guard year >= 1900, year <= 9999, month >= 1, month <= 12 else { return nil }
        let type: PlanItemType
        switch typeName {
        case PlanItemType.fixedexpense.name: type = .fixedexpense
        case PlanItemType.savings.name: type = .savings
        case PlanItemType.income.name: type = .income
        default: return nil
        }
        return MultiMonthApplyRoute(
            type: type,
            month: YearMonthKey(year: year, month: month),
            itemId: itemId
        )
    }

    var encoded: String {
        guard let data = try? JSONEncoder().encode(self) else { return "" }
        return String(decoding: data, as: UTF8.self)
    }

    static func decode(_ value: String) -> Self? {
        guard !value.isEmpty, let data = value.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(Self.self, from: data)
    }
}

struct PlanEditorRoute: Identifiable {
    let itemId: Int64?
    let type: PlanItemType
    let month: YearMonthKey

    var id: String {
        "\(itemId.map(String.init) ?? "new")-\(type.name)-\(month.year)-\(month.month)"
    }
}

func planEditorTypeTitle(_ type: PlanItemType) -> String {
    if type == .fixedexpense { return "고정지출" }
    if type == .savings { return "저축·투자" }
    return "수입"
}

struct HomeView: View {
    @StateObject private var model: HomeViewModel

    let onEditGoal: (Int) -> Void
    let onOpenAnnual: (Int, NativeAnnualSection) -> Void
    let onOpenComposition: (YearMonthKey) -> Void
    let onAddExpense: (YearMonthKey) -> Void
    let onOpenOverspent: (YearMonthKey) -> Void

    init(
        dependencies: IosDependencies,
        setup: LedgerSetup,
        onEditGoal: @escaping (Int) -> Void,
        onOpenAnnual: @escaping (Int, NativeAnnualSection) -> Void,
        onOpenComposition: @escaping (YearMonthKey) -> Void,
        onAddExpense: @escaping (YearMonthKey) -> Void,
        onOpenOverspent: @escaping (YearMonthKey) -> Void
    ) {
        _model = StateObject(wrappedValue: HomeViewModel(dependencies: dependencies, setup: setup))
        self.onEditGoal = onEditGoal
        self.onOpenAnnual = onOpenAnnual
        self.onOpenComposition = onOpenComposition
        self.onAddExpense = onAddExpense
        self.onOpenOverspent = onOpenOverspent
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar

            ScrollView {
                LazyVStack(spacing: 14) {
                    savingsGoalCard
                    annualSummaryCard
                    insightSection
                    addExpenseButton
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 20)
                .frame(maxWidth: 480)
                .frame(maxWidth: .infinity)
            }
        }
        .background(MoaLogColor.homeCanvas)
    }

    private var topBar: some View {
        HStack(spacing: 8) {
            Text("모아로그")
                .font(MoaLogFont.bold(16, relativeTo: .headline))
                .foregroundStyle(MoaLogColor.teal)

            Text("\(model.state.memberNames) 프로필")
                .font(MoaLogFont.semibold(11, relativeTo: .caption))
                .foregroundStyle(MoaLogColor.mutedInk)
                .lineLimit(1)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(MoaLogColor.surface, in: Capsule())
                .overlay(Capsule().stroke(MoaLogColor.homeBorder.opacity(0.7), lineWidth: 1))

            Spacer(minLength: 4)

            Menu {
                ForEach(selectableYears, id: \.self) { year in
                    Button {
                        model.selectYear(year)
                    } label: {
                        if year == Int(model.state.selectedYear) {
                            Label("\(year)년", systemImage: "checkmark")
                        } else {
                            Text("\(year)년")
                        }
                    }
                }
            } label: {
                HStack(spacing: 2) {
                    Text("\(String(model.state.selectedYear))년")
                    Image(systemName: "chevron.down")
                        .font(.system(size: 10, weight: .bold))
                }
                .font(MoaLogFont.semibold(12, relativeTo: .caption))
                .foregroundStyle(MoaLogColor.teal)
                .padding(.horizontal, 10)
                .frame(minHeight: 44)
                .background(MoaLogColor.surfaceLow, in: Capsule())
            }
            .accessibilityLabel("조회 연도, \(String(model.state.selectedYear))년")
            .accessibilityHint("다른 연도를 선택합니다")
        }
        .padding(.horizontal, 16)
        .frame(minHeight: 48)
        .background(MoaLogColor.homeCanvas.opacity(0.97))
        .overlay(alignment: .bottom) {
            Rectangle().fill(MoaLogColor.homeBorder).frame(height: 1)
        }
    }

    private var savingsGoalCard: some View {
        HomeCard {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .top, spacing: 8) {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 8) {
                            Text("\(String(model.state.selectedYear)) 연간 저축 목표")
                                .font(MoaLogFont.semibold(15, relativeTo: .headline))
                                .foregroundStyle(MoaLogColor.ink)
                            Text("목적통장 포함")
                                .font(MoaLogFont.bold(9, relativeTo: .caption2))
                                .foregroundStyle(MoaLogColor.mutedInk)
                                .padding(.horizontal, 7)
                                .padding(.vertical, 2)
                                .background(MoaLogColor.divider, in: Capsule())
                        }
                        Text("함께 정한 연간 저축 목표")
                            .font(MoaLogFont.regular(11, relativeTo: .caption))
                            .foregroundStyle(MoaLogColor.mutedInk)
                    }

                    Spacer(minLength: 4)

                    Button(action: { onEditGoal(Int(model.state.selectedYear)) }) {
                        Label("목표 수정", systemImage: "pencil")
                            .labelStyle(.titleAndIcon)
                            .font(MoaLogFont.medium(11, relativeTo: .caption))
                            .foregroundStyle(MoaLogColor.mutedInk)
                            .frame(minHeight: 44)
                    }
                    .buttonStyle(.plain)
                    .accessibilityHint("저축 목표 수정 화면을 엽니다")
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text("목표 저축액")
                        .font(MoaLogFont.medium(11, relativeTo: .caption2))
                        .foregroundStyle(MoaLogColor.mutedInk)

                    if let target = targetAmount {
                        HStack(alignment: .firstTextBaseline, spacing: 4) {
                            Text(formatNumber(target))
                                .font(MoaLogFont.bold(26, relativeTo: .title))
                            Text("원")
                                .font(MoaLogFont.semibold(15, relativeTo: .body))
                        }
                        .foregroundStyle(MoaLogColor.teal)
                        .minimumScaleFactor(0.75)
                    } else {
                        Text("아직 목표를 설정하지 않았어요")
                            .font(MoaLogFont.bold(17, relativeTo: .headline))
                            .foregroundStyle(MoaLogColor.teal)
                    }
                }

                VStack(spacing: 7) {
                    HStack(spacing: 7) {
                        Circle().fill(MoaLogColor.teal).frame(width: 6, height: 6)
                        Text("연간 저축 (예상 포함)")
                            .font(MoaLogFont.semibold(11, relativeTo: .caption))
                            .foregroundStyle(MoaLogColor.mutedInk)
                        Spacer()
                        if let savings = displayedPurposeSavings {
                            Text(formatWon(savings))
                                .font(MoaLogFont.semibold(11, relativeTo: .caption))
                                .foregroundStyle(MoaLogColor.ink)
                        } else {
                            Text(model.state.annualSummary.isLoading ? "불러오는 중" : "미입력")
                                .font(MoaLogFont.medium(11, relativeTo: .caption))
                                .foregroundStyle(MoaLogColor.outline)
                        }
                    }

                    ProgressView(value: goalProgressFraction)
                        .tint(MoaLogColor.teal)
                        .scaleEffect(x: 1, y: 2.2, anchor: .center)
                        .clipShape(Capsule())
                        .padding(.vertical, 2)
                        .accessibilityLabel("연간 저축 목표 달성률")
                        .accessibilityValue(goalProgressText)

                    Rectangle().fill(MoaLogColor.homeBorder.opacity(0.7)).frame(height: 1)

                    HStack(spacing: 7) {
                        Text("달성률 \(goalProgressText)")
                            .font(MoaLogFont.bold(10, relativeTo: .caption2))
                            .foregroundStyle(MoaLogColor.tertiary)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(MoaLogColor.sage, in: Capsule())
                        Text(goalRemainingText)
                            .font(MoaLogFont.regular(11, relativeTo: .caption))
                            .foregroundStyle(MoaLogColor.mutedInk)
                        Spacer()
                        Text("저축률 \(savingsRateText)")
                            .font(MoaLogFont.semibold(11, relativeTo: .caption))
                            .foregroundStyle(MoaLogColor.mutedInk)
                    }
                }
                .padding(10)
                .background(MoaLogColor.surfaceLow.opacity(0.75), in: RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(MoaLogColor.homeBorder.opacity(0.6), lineWidth: 1))
            }
        }
    }

    private var annualSummaryCard: some View {
        HomeCard {
            VStack(spacing: 8) {
                HStack {
                    Text("연간 누적 결산")
                        .font(MoaLogFont.semibold(15, relativeTo: .headline))
                        .foregroundStyle(MoaLogColor.ink)
                        .accessibilityAddTraits(.isHeader)
                    Spacer()
                    Button(action: { onOpenAnnual(Int(model.state.selectedYear), .income) }) {
                        HStack(spacing: 2) {
                            Text("연간 상세 보기")
                            Image(systemName: "chevron.right")
                                .accessibilityHidden(true)
                        }
                        .font(MoaLogFont.semibold(11, relativeTo: .caption))
                        .foregroundStyle(MoaLogColor.teal)
                        .frame(minHeight: 44)
                    }
                    .buttonStyle(.plain)
                    .accessibilityHint("연간 한눈에 보기 화면을 엽니다")
                }

                HStack(spacing: 8) {
                    metricCard(
                        "연간 수입",
                        value: annualIncomeText,
                        detail: annualIncomeDetail,
                        section: .income,
                        hint: "연간 보기의 수입 구역을 엽니다"
                    )
                    metricCard(
                        "연간 지출",
                        value: annualExpenseText,
                        detail: annualExpenseDetail,
                        section: .fixedExpense,
                        hint: "연간 보기의 고정지출 구역을 엽니다. 변동지출은 탭에서 확인할 수 있습니다"
                    )
                    metricCard(
                        "순 저축률",
                        value: annualNetSavingsRateText,
                        detail: annualNetSavingsRateDetail,
                        emptyDetail: annualNetSavingsRateDetail,
                        accent: true,
                        section: .savings,
                        hint: "연간 보기의 저축·투자 구역을 엽니다"
                    )
                }

                Rectangle().fill(MoaLogColor.homeBorder).frame(height: 1)

                HStack(spacing: 6) {
                    Image(systemName: "person.2.fill")
                        .foregroundStyle(MoaLogColor.teal)
                        .accessibilityHidden(true)
                    Text("부부 공동 목표 기여")
                    Spacer()
                    Text(model.state.annualSummary.memberContributionLabel ?? "미입력")
                        .font(MoaLogFont.semibold(11, relativeTo: .caption))
                }
                .font(MoaLogFont.regular(11, relativeTo: .caption))
                .foregroundStyle(MoaLogColor.mutedInk)
            }
        }
    }

    private var insightSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("현황 분석 및 알림")
                .font(MoaLogFont.semibold(15, relativeTo: .headline))
                .foregroundStyle(MoaLogColor.ink)
                .padding(.horizontal, 4)
                .accessibilityAddTraits(.isHeader)

            compositionCard
            overspentCard
        }
    }

    private var compositionCard: some View {
        HomeCard {
            VStack(spacing: 10) {
                Button(action: {
                    onOpenComposition(
                        YearMonthKey(
                            year: model.state.selectedYear,
                            month: Int32(model.state.monthlySummary.month)
                        )
                    )
                }) {
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: "chart.pie")
                        .font(.system(size: 20, weight: .medium))
                        .foregroundStyle(MoaLogColor.teal)
                        .frame(width: 36, height: 36)
                        .background(MoaLogColor.secondaryContainer.opacity(0.6), in: RoundedRectangle(cornerRadius: 10))
                        .accessibilityHidden(true)

                    VStack(alignment: .leading, spacing: 2) {
                        Text("지출·저축 구성")
                            .font(MoaLogFont.medium(14))
                            .foregroundStyle(MoaLogColor.ink)
                        Text("카테고리별 비중 요약")
                            .font(MoaLogFont.regular(11, relativeTo: .caption))
                            .foregroundStyle(MoaLogColor.mutedInk)
                    }

                    Spacer()
                        Image(systemName: "arrow.up.right")
                            .foregroundStyle(MoaLogColor.outline)
                            .accessibilityHidden(true)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .frame(minHeight: 44)
                .accessibilityLabel("지출·저축 구성, 카테고리별 비중 요약")
                .accessibilityValue(compositionAccessibilityValue)
                .accessibilityHint("구성 분석 화면을 엽니다")

                Rectangle().fill(MoaLogColor.homeBorder.opacity(0.7)).frame(height: 1)

                compositionContent
            }
        }
    }

    private var overspentCard: some View {
        let summary = model.state.monthlySummary
        let hasOverspent = summary.overspentCount > 0

        return HomeCard(borderColor: hasOverspent ? MoaLogColor.errorSurface : MoaLogColor.homeBorder) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 8) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(hasOverspent ? MoaLogColor.error : MoaLogColor.mutedInk)
                        .frame(width: 32, height: 32)
                        .background(hasOverspent ? MoaLogColor.errorSurface.opacity(0.65) : MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 8))
                        .accessibilityHidden(true)

                    Text("\(summary.month)월 소비 체크")
                        .font(MoaLogFont.medium(14))
                        .foregroundStyle(MoaLogColor.ink)

                    Spacer()

                    if hasOverspent {
                        Text("과소비")
                            .font(MoaLogFont.bold(10, relativeTo: .caption2))
                            .foregroundStyle(MoaLogColor.error)
                            .padding(.horizontal, 9)
                            .padding(.vertical, 3)
                            .background(MoaLogColor.errorSurface, in: Capsule())
                    }
                }

                if summary.isLoading {
                    Text("이번 달 지출 기록을 확인하고 있어요")
                        .font(MoaLogFont.regular(11, relativeTo: .caption))
                        .foregroundStyle(MoaLogColor.mutedInk)
                } else if hasOverspent {
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        Text(formatWon(summary.overspentTotalWon))
                            .font(MoaLogFont.bold(17, relativeTo: .headline))
                        Text("표시된 지출")
                            .font(MoaLogFont.regular(11, relativeTo: .caption))
                    }
                    .foregroundStyle(MoaLogColor.error)

                    Text("사용자가 과소비로 표시한 지출 \(summary.overspentCount)건이 있어요.")
                        .font(MoaLogFont.regular(11, relativeTo: .caption))
                        .foregroundStyle(MoaLogColor.mutedInk)

                    Rectangle().fill(MoaLogColor.errorSurface).frame(height: 1)

                    HStack {
                        Text("이번 달 변동지출 \(formatWon(summary.expenseTotalWon))")
                            .font(MoaLogFont.medium(11, relativeTo: .caption2))
                            .foregroundStyle(MoaLogColor.mutedInk)
                        Spacer()
                        Button("상세 확인") {
                            onOpenOverspent(
                                YearMonthKey(year: model.state.selectedYear, month: Int32(summary.month))
                            )
                        }
                            .font(MoaLogFont.semibold(12, relativeTo: .caption))
                            .foregroundStyle(MoaLogColor.error)
                            .frame(minHeight: 44)
                    }
                } else if let error = summaryError {
                    HStack {
                        Text(error)
                            .font(MoaLogFont.regular(11, relativeTo: .caption))
                            .foregroundStyle(MoaLogColor.error)
                        Spacer()
                        Button("다시 시도", action: model.retry)
                            .font(MoaLogFont.semibold(12, relativeTo: .caption))
                            .foregroundStyle(MoaLogColor.teal)
                    }
                } else {
                    Text("과소비로 표시한 지출이 없어요")
                        .font(MoaLogFont.regular(11, relativeTo: .caption))
                        .foregroundStyle(MoaLogColor.mutedInk)
                }
            }
        }
    }

    private var addExpenseButton: some View {
        Button {
            onAddExpense(YearMonthKey(year: model.state.selectedYear, month: Int32(Calendar.current.component(.month, from: Date()))))
        } label: {
            Label("지출 기록하기", systemImage: "plus")
                .font(MoaLogFont.semibold(14))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, minHeight: 48)
                .background(MoaLogColor.teal, in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .accessibilityHint("지출 입력 화면을 엽니다")
    }

    @ViewBuilder
    private var compositionContent: some View {
        let summary = model.state.annualSummary
        let composition = summary.composition

        if summary.isLoading {
            compositionMessage("연간 계획과 기록을 불러오는 중이에요")
        } else if let error = summary.loadError {
            HStack(spacing: 8) {
                Text(error)
                    .font(MoaLogFont.regular(11, relativeTo: .caption))
                    .foregroundStyle(MoaLogColor.error)
                Spacer()
                Button("다시 시도", action: model.retry)
                    .font(MoaLogFont.semibold(12, relativeTo: .caption))
                    .foregroundStyle(MoaLogColor.teal)
            }
        } else if composition.isComplete,
                  let fixedPercent = composition.fixedExpensePercent?.doubleValue,
                  let variablePercent = composition.variableExpensePercent?.doubleValue,
                  let savingsPercent = composition.savingsPercent?.doubleValue {
            VStack(spacing: 10) {
                GeometryReader { geometry in
                    HStack(spacing: 2) {
                        compositionSegment(width: geometry.size.width * fixedPercent / 100, color: MoaLogColor.teal)
                        compositionSegment(width: geometry.size.width * variablePercent / 100, color: MoaLogColor.tertiary)
                        compositionSegment(width: geometry.size.width * savingsPercent / 100, color: MoaLogColor.sage)
                    }
                }
                .frame(height: 10)
                .clipShape(Capsule())

                HStack(spacing: 12) {
                    compositionLegend("고정지출", percent: fixedPercent, color: MoaLogColor.teal)
                    compositionLegend("변동지출", percent: variablePercent, color: MoaLogColor.tertiary)
                    compositionLegend("저축", percent: savingsPercent, color: MoaLogColor.sage)
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel("고정지출 \(formatPercent(fixedPercent)), 변동지출 \(formatPercent(variablePercent)), 저축 \(formatPercent(savingsPercent))")
        } else if composition.fixedExpenseWon != 0 || composition.variableExpenseWon != 0 || composition.savingsWon != 0 {
            compositionMessage("일부 금액이 미입력되어 비율을 계산할 수 없어요")
        } else {
            compositionMessage("분석할 수입·지출·저축 데이터가 아직 없어요")
        }
    }

    private func compositionMessage(_ text: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(text)
                .font(MoaLogFont.regular(11, relativeTo: .caption))
                .foregroundStyle(MoaLogColor.mutedInk)
            Capsule().fill(MoaLogColor.homeBorder).frame(height: 8)
        }
    }

    private func compositionSegment(width: CGFloat, color: Color) -> some View {
        color.frame(width: max(0, width), height: 10)
    }

    private func compositionLegend(_ title: String, percent: Double, color: Color) -> some View {
        HStack(spacing: 4) {
            Circle().fill(color).frame(width: 7, height: 7)
            Text("\(title) \(formatPercent(percent))")
                .font(MoaLogFont.medium(10, relativeTo: .caption2))
                .foregroundStyle(MoaLogColor.mutedInk)
                .lineLimit(1)
        }
    }

    private func metricCard(
        _ title: String,
        value: String?,
        detail: String,
        emptyDetail: String = "미입력",
        accent: Bool = false,
        section: NativeAnnualSection,
        hint: String
    ) -> some View {
        Button {
            onOpenAnnual(Int(model.state.selectedYear), section)
        } label: {
            VStack(spacing: 3) {
                Text(title)
                    .font(MoaLogFont.medium(10, relativeTo: .caption2))
                    .foregroundStyle(MoaLogColor.mutedInk)
                    .lineLimit(1)
                Text(value ?? "—")
                    .font(MoaLogFont.bold(14))
                    .foregroundStyle(accent ? MoaLogColor.teal : MoaLogColor.ink)
                    .minimumScaleFactor(0.7)
                    .lineLimit(1)
                Text(value == nil ? emptyDetail : detail)
                    .font(MoaLogFont.bold(10, relativeTo: .caption2))
                    .foregroundStyle(MoaLogColor.outline)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, minHeight: 64)
            .background(MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(title), \(value ?? "미입력"), \(value == nil ? emptyDetail : detail)")
        .accessibilityHint(hint)
    }

    private var targetAmount: Int64? {
        model.state.annualSavingsTargetWon?.int64Value
    }

    private var selectableYears: [Int] {
        let selected = Int(model.state.selectedYear)
        return Array(max(1900, selected - 3)...min(9999, selected + 3))
    }

    private var displayedPurposeSavings: Int64? {
        let summary = model.state.annualSummary
        return summary.savingsIsComplete || summary.knownPurposeAccountSavingsWon != 0
            ? summary.knownPurposeAccountSavingsWon
            : nil
    }

    private var goalProgressFraction: Double {
        guard let rate = model.state.annualSummary.goalProgressRate?.doubleValue else { return 0 }
        return min(max(rate / 100, 0), 1)
    }

    private var goalProgressText: String {
        guard let rate = model.state.annualSummary.goalProgressRate?.doubleValue else { return "—" }
        return formatPercent(rate)
    }

    private var goalRemainingText: String {
        guard let remaining = model.state.annualSummary.goalRemainingWon?.int64Value else { return "목표까지 —" }
        if remaining >= 0 {
            return "목표까지 \(formatCompactWon(remaining)) 남음"
        }
        return "목표보다 \(formatCompactWon(abs(remaining))) 초과"
    }

    private var savingsRateText: String {
        let summary = model.state.annualSummary
        if summary.incomeIsComplete && summary.savingsIsComplete && summary.knownIncomeWon == 0 {
            return "계산 불가"
        }
        guard let rate = summary.savingsRate?.doubleValue else { return "—" }
        return formatPercent(rate)
    }

    private var annualIncomeText: String? {
        let summary = model.state.annualSummary
        guard summary.incomeIsComplete || summary.knownIncomeWon != 0 else { return nil }
        return formatCompactWon(summary.knownIncomeWon)
    }

    private var annualIncomeDetail: String {
        model.state.annualSummary.incomeIsComplete ? formatWon(model.state.annualSummary.knownIncomeWon) : "일부 미입력"
    }

    private var annualExpenseText: String? {
        let summary = model.state.annualSummary
        guard summary.fixedExpenseIsComplete || summary.knownTotalExpenseWon != 0 else { return nil }
        return formatCompactWon(summary.knownTotalExpenseWon)
    }

    private var annualExpenseDetail: String {
        model.state.annualSummary.fixedExpenseIsComplete ? formatWon(model.state.annualSummary.knownTotalExpenseWon) : "일부 미입력"
    }

    private var annualNetSavingsRateText: String? {
        guard let rate = model.state.annualSummary.netSavingsRate?.doubleValue else { return nil }
        return formatPercent(rate)
    }

    private var annualNetSavingsRateDetail: String {
        let summary = model.state.annualSummary
        if summary.incomeIsComplete && summary.savingsIsComplete && summary.knownIncomeWon == 0 {
            return "계산 불가"
        }
        return summary.netSavingsRate == nil ? "일부 미입력" : "목적통장 제외"
    }

    private var compositionAccessibilityValue: String {
        let summary = model.state.annualSummary
        if summary.isLoading { return "불러오는 중" }
        if let error = summary.loadError { return error }

        let composition = summary.composition
        guard let fixed = composition.fixedExpensePercent?.doubleValue,
              let variable = composition.variableExpensePercent?.doubleValue,
              let savings = composition.savingsPercent?.doubleValue else {
            return composition.isComplete ? "구성 데이터 없음" : "일부 미입력"
        }
        return "고정지출 \(formatPercent(fixed)), 변동지출 \(formatPercent(variable)), 저축 \(formatPercent(savings))"
    }

    private var summaryError: String? {
        model.state.monthlySummary.loadError
    }
}

private struct HomeCard<Content: View>: View {
    let borderColor: Color
    @ViewBuilder let content: Content

    init(borderColor: Color = MoaLogColor.homeBorder, @ViewBuilder content: () -> Content) {
        self.borderColor = borderColor
        self.content = content()
    }

    var body: some View {
        content
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(borderColor, lineWidth: 1))
            .shadow(color: MoaLogColor.ink.opacity(0.04), radius: 4, y: 2)
    }
}

func formatWon(_ value: Int64) -> String {
    "\(formatNumber(value))원"
}

private func formatNumber(_ value: Int64) -> String {
    let formatter = NumberFormatter()
    formatter.numberStyle = .decimal
    formatter.groupingSeparator = ","
    return formatter.string(from: NSNumber(value: value)) ?? String(value)
}

private func formatCompactWon(_ value: Int64) -> String {
    let magnitude = value.magnitude
    if magnitude >= 100_000_000 {
        return formatCompact(value: Double(value) / 100_000_000, unit: "억원")
    }
    if magnitude >= 10_000 {
        return formatCompact(value: Double(value) / 10_000, unit: "만원")
    }
    return formatWon(value)
}

private func formatCompact(value: Double, unit: String) -> String {
    let rounded = value.rounded()
    let number = abs(value - rounded) < 0.05 ? String(Int(rounded)) : String(format: "%.1f", value)
    return "\(number)\(unit)"
}

func formatPercent(_ value: Double) -> String {
    let rounded = value.rounded()
    return abs(value - rounded) < 0.05 ? "\(Int(rounded))%" : String(format: "%.1f%%", value)
}
