import SharedKit
import SwiftUI

@MainActor
final class PlanViewModel: ObservableObject {
    @Published private(set) var state: PlanUiState

    private let store: IosPlanStore
    private var observation: IosObservation?

    init(dependencies: IosDependencies) {
        let store = dependencies.planStore()
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

    func previousMonth() { store.previousMonth() }
    func nextMonth() { store.nextMonth() }
    func selectTab(_ tab: NativePlanTab) { store.selectTab(tab: tab.shared) }
    func select(month: YearMonthKey, tab: NativePlanTab) {
        store.selectMonthAndTab(year: month.year, month: month.month, tab: tab.shared)
    }
    func select(month: YearMonthKey) {
        store.selectMonth(year: month.year, month: month.month)
    }
    func retry() { store.retry() }
}

struct PlanSelectionRequest: Identifiable {
    let id = UUID()
    let month: YearMonthKey
    let tab: NativePlanTab?
}

enum NativePlanTab: CaseIterable, Hashable {
    case income
    case fixedExpense
    case variableExpense
    case savings

    var title: String {
        switch self {
        case .income: "수입"
        case .fixedExpense: "고정지출"
        case .variableExpense: "변동지출"
        case .savings: "저축·투자"
        }
    }

    var shared: PlanTab {
        switch self {
        case .income: .income
        case .fixedExpense: .fixedexpense
        case .variableExpense: .variableexpense
        case .savings: .savings
        }
    }

    var editorType: PlanItemType? {
        switch self {
        case .income: .income
        case .fixedExpense: .fixedexpense
        case .savings: .savings
        case .variableExpense: nil
        }
    }

    static func from(_ tab: PlanTab) -> NativePlanTab {
        if tab == .fixedexpense { return .fixedExpense }
        if tab == .variableexpense { return .variableExpense }
        if tab == .savings { return .savings }
        return .income
    }

    static func from(_ type: PlanItemType) -> NativePlanTab {
        if type == .fixedexpense { return .fixedExpense }
        if type == .savings { return .savings }
        return .income
    }
}

struct PlanView: View {
    @StateObject private var model: PlanViewModel
    @State private var appliedSelectionRequestId: UUID?

    let setup: LedgerSetup
    let selectionRequest: PlanSelectionRequest?
    let onOpenAnnual: (Int) -> Void
    let onOpenSalaryAllocation: (YearMonthKey) -> Void
    let onOpenFixedCosts: (YearMonthKey) -> Void
    let onOpenItem: (MonthlyPlanItem) -> Void
    let onAddItem: (NativePlanTab, YearMonthKey) -> Void
    let onApplyMultipleMonths: (NativePlanTab, YearMonthKey) -> Void
    let onOpenRecords: (YearMonthKey) -> Void

    init(
        dependencies: IosDependencies,
        setup: LedgerSetup,
        selectionRequest: PlanSelectionRequest? = nil,
        onOpenAnnual: @escaping (Int) -> Void,
        onOpenSalaryAllocation: @escaping (YearMonthKey) -> Void,
        onOpenFixedCosts: @escaping (YearMonthKey) -> Void,
        onOpenItem: @escaping (MonthlyPlanItem) -> Void,
        onAddItem: @escaping (NativePlanTab, YearMonthKey) -> Void,
        onApplyMultipleMonths: @escaping (NativePlanTab, YearMonthKey) -> Void,
        onOpenRecords: @escaping (YearMonthKey) -> Void
    ) {
        _model = StateObject(wrappedValue: PlanViewModel(dependencies: dependencies))
        self.setup = setup
        self.selectionRequest = selectionRequest
        self.onOpenAnnual = onOpenAnnual
        self.onOpenSalaryAllocation = onOpenSalaryAllocation
        self.onOpenFixedCosts = onOpenFixedCosts
        self.onOpenItem = onOpenItem
        self.onAddItem = onAddItem
        self.onApplyMultipleMonths = onApplyMultipleMonths
        self.onOpenRecords = onOpenRecords
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar

            ScrollView {
                LazyVStack(spacing: 16) {
                    monthSelector
                    quickLinks
                    summaryCard
                    tabSection
                }
                .padding(.horizontal, 20)
                .padding(.top, 12)
                .padding(.bottom, 32)
                .frame(maxWidth: 480)
                .frame(maxWidth: .infinity)
            }
        }
        .background(MoaLogColor.homeCanvas)
        .onAppear(perform: applySelectionRequestIfNeeded)
        .onChange(of: selectionRequest?.id) { _, _ in applySelectionRequestIfNeeded() }
    }

    private var topBar: some View {
        HStack(spacing: 8) {
            Text("모아로그")
                .font(MoaLogFont.bold(18, relativeTo: .headline))
                .foregroundStyle(MoaLogColor.teal)

            HStack(spacing: 4) {
                Image(systemName: "heart.fill")
                    .font(.system(size: 10, weight: .semibold))
                    .accessibilityHidden(true)
                Text(memberNames)
                    .lineLimit(1)
            }
            .font(MoaLogFont.medium(11, relativeTo: .caption2))
            .foregroundStyle(MoaLogColor.mutedInk)
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .background(MoaLogColor.homeBorder.opacity(0.65), in: Capsule())
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(memberNames) 프로필")

            Spacer(minLength: 4)

            Button(action: { onOpenAnnual(Int(model.state.month.year)) }) {
                HStack(spacing: 2) {
                    Text("연간 보기")
                    Image(systemName: "chevron.right")
                        .font(.system(size: 10, weight: .bold))
                        .accessibilityHidden(true)
                }
                .font(MoaLogFont.semibold(13, relativeTo: .caption))
                .foregroundStyle(MoaLogColor.teal)
                .frame(minHeight: 48)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 20)
        .frame(minHeight: 48)
        .background(MoaLogColor.homeCanvas.opacity(0.97))
        .overlay(alignment: .bottom) {
            Rectangle().fill(MoaLogColor.homeBorder).frame(height: 1)
        }
    }

    private func applySelectionRequestIfNeeded() {
        guard let request = selectionRequest, appliedSelectionRequestId != request.id else { return }
        appliedSelectionRequestId = request.id
        if let tab = request.tab {
            model.select(month: request.month, tab: tab)
        } else {
            model.select(month: request.month)
        }
    }

    private var monthSelector: some View {
        HStack(spacing: 8) {
            monthButton("이전 달", symbol: "chevron.left", action: model.previousMonth)

            Spacer(minLength: 0)

            HStack(spacing: 7) {
                Image(systemName: "calendar")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(MoaLogColor.teal)
                    .accessibilityHidden(true)
                Text(monthTitle)
                    .font(MoaLogFont.semibold(16))
                    .foregroundStyle(MoaLogColor.ink)
                    .lineLimit(1)
                Text("예산수립")
                    .font(MoaLogFont.bold(9, relativeTo: .caption2))
                    .foregroundStyle(planBlue)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 3)
                    .background(planBlueSurface, in: Capsule())
            }

            Spacer(minLength: 0)

            monthButton("다음 달", symbol: "chevron.right", action: model.nextMonth)
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 4)
        .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(MoaLogColor.cardBorder, lineWidth: 1))
        .shadow(color: MoaLogColor.teal.opacity(0.04), radius: 4, y: 2)
    }

    private func monthButton(_ label: String, symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(MoaLogColor.mutedInk)
                .frame(width: 48, height: 48)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    private var quickLinks: some View {
        HStack(spacing: 8) {
            quickLink(
                title: "월급 배분",
                subtitle: "월급 배분 계획 세우기",
                symbol: "building.columns.fill",
                background: MoaLogColor.secondaryContainer.opacity(0.7),
                action: { onOpenSalaryAllocation(model.state.month) }
            )
            quickLink(
                title: "고정비 점검",
                subtitle: "이번 달 고정비 관리",
                symbol: "doc.text.fill",
                background: MoaLogColor.homeBorder.opacity(0.7),
                action: { onOpenFixedCosts(model.state.month) }
            )
        }
    }

    private func quickLink(
        title: String,
        subtitle: String,
        symbol: String,
        background: Color,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: symbol)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(MoaLogColor.teal)
                    .frame(width: 36, height: 36)
                    .background(background, in: RoundedRectangle(cornerRadius: 9))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(MoaLogFont.semibold(13, relativeTo: .caption))
                        .foregroundStyle(MoaLogColor.ink)
                    Text(subtitle)
                        .font(MoaLogFont.regular(10, relativeTo: .caption2))
                        .foregroundStyle(MoaLogColor.mutedInk)
                        .lineLimit(2)
                }
                Spacer(minLength: 0)
            }
            .padding(11)
            .frame(maxWidth: .infinity, minHeight: 68, alignment: .leading)
            .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(MoaLogColor.cardBorder, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    private var summaryCard: some View {
        PlanCard {
            VStack(spacing: 12) {
                HStack(spacing: 6) {
                    Image(systemName: "chart.bar.xaxis")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(MoaLogColor.teal)
                        .accessibilityHidden(true)
                    Text("\(model.state.month.month)월 재정 플랜 요약")
                        .font(MoaLogFont.semibold(13, relativeTo: .caption))
                        .foregroundStyle(MoaLogColor.ink)
                    Spacer()
                    Text(summaryBadge)
                        .font(MoaLogFont.bold(9, relativeTo: .caption2))
                        .foregroundStyle(summaryBadgeColor)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 3)
                        .background(summaryBadgeColor.opacity(0.10), in: Capsule())
                }

                if model.state.isLoading {
                    ProgressView("월별 계획을 불러오는 중이에요")
                        .font(MoaLogFont.regular(13, relativeTo: .caption))
                        .tint(MoaLogColor.teal)
                        .frame(maxWidth: .infinity, minHeight: 116)
                } else if let error = model.state.error {
                    errorContent(error)
                } else {
                    summaryContent
                }
            }
        }
    }

    private var summaryContent: some View {
        let summary = model.state.summary
        return VStack(spacing: 12) {
            HStack(spacing: 12) {
                summaryHighlight("예정 수입", amount: summary.incomeWon?.int64Value, accent: true)
                summaryHighlight("총 지출 예정", amount: summary.totalExpenseWon?.int64Value, accent: false)
            }

            HStack(spacing: 8) {
                summaryBreakdownDot(color: MoaLogColor.teal, text: "고정 \(amountText(summary.fixedExpenseWon?.int64Value))")
                Divider().frame(height: 14)
                summaryBreakdownDot(
                    color: MoaLogColor.outline,
                    text: "변동 \(summary.hasVariableExpenses ? formatWon(summary.variableExpenseWon) : "미입력")"
                )
                Spacer(minLength: 0)
                Text("지출률 \(rateText(summary.spendingRate?.doubleValue))")
                    .font(MoaLogFont.medium(10, relativeTo: .caption2))
                    .foregroundStyle(MoaLogColor.mutedInk)
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
            .background(MoaLogColor.homeCanvas, in: RoundedRectangle(cornerRadius: 8))

            HStack(spacing: 12) {
                savingsMetric(
                    title: "저축률 (목적 포함)",
                    amount: summary.savingsWon?.int64Value,
                    rate: summary.savingsRate?.doubleValue,
                    color: MoaLogColor.tertiary,
                    badgeBackground: MoaLogColor.sage
                )
                savingsMetric(
                    title: "순저축률",
                    amount: summary.netSavingsWon?.int64Value,
                    rate: summary.netSavingsRate?.doubleValue,
                    color: MoaLogColor.teal,
                    badgeBackground: planBlueSurface
                )
            }

            if summary.hasMissingAmounts {
                Label("미입력 항목은 합계에서 제외했어요", systemImage: "info.circle")
                    .font(MoaLogFont.medium(11, relativeTo: .caption2))
                    .foregroundStyle(MoaLogColor.error)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    private func summaryHighlight(_ title: String, amount: Int64?, accent: Bool) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title)
                .font(MoaLogFont.medium(11, relativeTo: .caption2))
                .foregroundStyle(MoaLogColor.mutedInk)
            Text(amount.map(formatWon) ?? "미입력")
                .font(MoaLogFont.bold(17))
                .foregroundStyle(accent ? MoaLogColor.teal : MoaLogColor.ink)
                .frame(maxWidth: .infinity, alignment: .trailing)
                .minimumScaleFactor(0.7)
                .lineLimit(1)
        }
        .padding(12)
        .frame(maxWidth: .infinity, minHeight: 68)
        .background(MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 12))
    }

    private func summaryBreakdownDot(color: Color, text: String) -> some View {
        HStack(spacing: 5) {
            Circle().fill(color).frame(width: 6, height: 6)
            Text(text)
                .font(MoaLogFont.medium(10, relativeTo: .caption2))
                .foregroundStyle(MoaLogColor.mutedInk)
                .lineLimit(1)
        }
    }

    private func savingsMetric(
        title: String,
        amount: Int64?,
        rate: Double?,
        color: Color,
        badgeBackground: Color
    ) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(spacing: 4) {
                Text(title)
                    .font(MoaLogFont.medium(10, relativeTo: .caption2))
                    .foregroundStyle(MoaLogColor.mutedInk)
                    .lineLimit(1)
                Spacer(minLength: 0)
                Text(rateText(rate))
                    .font(MoaLogFont.bold(9, relativeTo: .caption2))
                    .foregroundStyle(color)
                    .padding(.horizontal, 5)
                    .padding(.vertical, 2)
                    .background(badgeBackground, in: RoundedRectangle(cornerRadius: 4))
            }
            Text(amount.map(formatWon) ?? "미입력")
                .font(MoaLogFont.bold(14, relativeTo: .body))
                .foregroundStyle(color)
                .frame(maxWidth: .infinity, alignment: .trailing)
                .minimumScaleFactor(0.7)
                .lineLimit(1)
        }
        .padding(11)
        .frame(maxWidth: .infinity, minHeight: 68)
        .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(MoaLogColor.cardBorder, lineWidth: 1))
    }

    private func errorContent(_ message: String) -> some View {
        HStack(spacing: 12) {
            Text(message)
                .font(MoaLogFont.regular(13, relativeTo: .caption))
                .foregroundStyle(MoaLogColor.error)
            Spacer()
            Button("다시 시도", action: model.retry)
                .font(MoaLogFont.semibold(12, relativeTo: .caption))
                .foregroundStyle(MoaLogColor.teal)
        }
        .frame(minHeight: 72)
    }

    private var tabSection: some View {
        VStack(spacing: 12) {
            HStack(spacing: 4) {
                ForEach(NativePlanTab.allCases, id: \.self) { tab in
                    let selected = selectedTab == tab
                    Button {
                        model.selectTab(tab)
                    } label: {
                        HStack(spacing: 4) {
                            Text(tab.title)
                            if selected {
                                Circle().fill(MoaLogColor.teal).frame(width: 5, height: 5)
                            }
                        }
                        .font(selected ? MoaLogFont.bold(11, relativeTo: .caption2) : MoaLogFont.medium(11, relativeTo: .caption2))
                        .foregroundStyle(selected ? MoaLogColor.teal : MoaLogColor.mutedInk)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(selected ? MoaLogColor.surface : .clear, in: RoundedRectangle(cornerRadius: 8))
                        .shadow(color: selected ? MoaLogColor.ink.opacity(0.06) : .clear, radius: 3, y: 1)
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
            .padding(4)
            .background(MoaLogColor.homeBorder.opacity(0.75), in: RoundedRectangle(cornerRadius: 12))

            if model.state.isLoading {
                PlanCard {
                    ProgressView().tint(MoaLogColor.teal).frame(maxWidth: .infinity, minHeight: 100)
                }
            } else if let error = model.state.error {
                PlanCard {
                    errorContent(error)
                }
            } else if selectedTab == .variableExpense {
                variableExpenseContent
            } else if visibleItems.isEmpty {
                emptyItemsContent
            } else {
                itemList
            }

            tabActions
        }
    }

    private var itemList: some View {
        PlanCard(insets: 0) {
            VStack(spacing: 0) {
                ForEach(Array(visibleItems.enumerated()), id: \.element.id) { index, item in
                    Button {
                        onOpenItem(item)
                    } label: {
                        planItemRow(item)
                    }
                    .buttonStyle(.plain)
                    if index < visibleItems.count - 1 {
                        Rectangle()
                            .fill(MoaLogColor.divider)
                            .frame(height: 1)
                            .padding(.leading, 68)
                    }
                }
            }
        }
    }

    private func planItemRow(_ item: MonthlyPlanItem) -> some View {
        HStack(spacing: 12) {
            Image(systemName: itemSymbol)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(item.amountWon == nil ? MoaLogColor.error : MoaLogColor.teal)
                .frame(width: 40, height: 40)
                .background(
                    item.amountWon == nil ? MoaLogColor.errorSurface.opacity(0.55) : MoaLogColor.secondaryContainer.opacity(0.65),
                    in: Circle()
                )
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 5) {
                    Text(item.name)
                        .font(MoaLogFont.semibold(14))
                        .foregroundStyle(MoaLogColor.ink)
                        .lineLimit(1)
                    Text(ownerLabel(item))
                        .font(MoaLogFont.bold(9, relativeTo: .caption2))
                        .foregroundStyle(MoaLogColor.mutedInk)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 2)
                        .background(MoaLogColor.homeBorder.opacity(0.7), in: RoundedRectangle(cornerRadius: 4))
                }
                Text(item.memo.takeIfNotBlank ?? item.category)
                    .font(MoaLogFont.regular(11, relativeTo: .caption2))
                    .foregroundStyle(MoaLogColor.mutedInk)
                    .lineLimit(1)
            }

            Spacer(minLength: 4)

            VStack(alignment: .trailing, spacing: 4) {
                if let amount = item.amountWon?.int64Value {
                    Text(formatWon(amount))
                        .font(MoaLogFont.bold(14))
                        .foregroundStyle(MoaLogColor.ink)
                        .minimumScaleFactor(0.7)
                        .lineLimit(1)
                } else {
                    Label("금액 입력", systemImage: "plus")
                        .font(MoaLogFont.medium(11, relativeTo: .caption2))
                        .foregroundStyle(MoaLogColor.mutedInk)
                        .padding(.horizontal, 8)
                        .frame(minHeight: 30)
                        .overlay(RoundedRectangle(cornerRadius: 7).stroke(MoaLogColor.cardBorder, style: StrokeStyle(lineWidth: 1, dash: [3])))
                }

                Text(item.amountWon == nil ? "미입력" : statusTitle(item.status))
                    .font(MoaLogFont.bold(9, relativeTo: .caption2))
                    .foregroundStyle(statusColor(item))
                    .padding(.horizontal, 7)
                    .padding(.vertical, 2)
                    .background(statusColor(item).opacity(0.10), in: Capsule())
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 13)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityHint("계획 항목을 수정합니다")
    }

    private var variableExpenseContent: some View {
        PlanCard {
            VStack(spacing: 10) {
                Image(systemName: "cart.fill")
                    .font(.system(size: 22, weight: .medium))
                    .foregroundStyle(MoaLogColor.teal)
                    .frame(width: 44, height: 44)
                    .background(MoaLogColor.secondaryContainer.opacity(0.65), in: Circle())
                    .accessibilityHidden(true)
                Text("\(model.state.month.month)월 변동지출")
                    .font(MoaLogFont.semibold(15))
                    .foregroundStyle(MoaLogColor.ink)
                Text(
                    model.state.hasVariableExpenses
                        ? formatWon(model.state.variableExpenseTotalWon)
                        : "미입력"
                )
                    .font(MoaLogFont.bold(24, relativeTo: .title2))
                    .foregroundStyle(MoaLogColor.teal)
                Text("변동지출은 지출 기록에서 입력하고 관리해요")
                    .font(MoaLogFont.regular(12, relativeTo: .caption))
                    .foregroundStyle(MoaLogColor.mutedInk)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity, minHeight: 150)
        }
    }

    private var emptyItemsContent: some View {
        PlanCard {
            VStack(spacing: 9) {
                Image(systemName: "tray")
                    .font(.system(size: 22, weight: .medium))
                    .foregroundStyle(MoaLogColor.teal)
                    .accessibilityHidden(true)
                Text("등록된 \(selectedTab.title) 계획이 없어요")
                    .font(MoaLogFont.semibold(14))
                    .foregroundStyle(MoaLogColor.ink)
                Text("계획 항목을 추가하면 월 요약에 바로 반영돼요")
                    .font(MoaLogFont.regular(12, relativeTo: .caption))
                    .foregroundStyle(MoaLogColor.mutedInk)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity, minHeight: 120)
        }
    }

    private var tabActions: some View {
        HStack(spacing: 8) {
            if selectedTab == .variableExpense {
                actionButton("지출 기록 보기", symbol: "list.bullet.rectangle", accent: true) {
                    onOpenRecords(model.state.month)
                }
            } else {
                actionButton("계획 항목 추가", symbol: "plus", accent: true) {
                    onAddItem(selectedTab, model.state.month)
                }
                actionButton("여러 달에 적용", symbol: "doc.on.doc", accent: false) {
                    onApplyMultipleMonths(selectedTab, model.state.month)
                }
            }
        }
    }

    private func actionButton(_ title: String, symbol: String, accent: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: symbol)
                .font(MoaLogFont.semibold(12, relativeTo: .caption))
                .foregroundStyle(accent ? MoaLogColor.teal : MoaLogColor.mutedInk)
                .frame(maxWidth: .infinity, minHeight: 48)
                .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(MoaLogColor.cardBorder, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    private var selectedTab: NativePlanTab { .from(model.state.selectedTab) }
    private var visibleItems: [MonthlyPlanItem] { model.state.visibleItems }

    private var memberNames: String {
        setup.members.sorted { $0.order < $1.order }.map(\.displayName).joined(separator: " · ")
    }

    private var monthTitle: String {
        "\(String(model.state.month.year))년 \(model.state.month.month)월"
    }

    private var summaryBadge: String {
        if model.state.isLoading { return "불러오는 중" }
        let summary = model.state.summary
        if summary.incomeWon == nil,
           summary.fixedExpenseWon == nil,
           summary.savingsWon == nil,
           !summary.hasVariableExpenses {
            return "입력 전"
        }
        return summary.hasMissingAmounts ? "일부 미입력" : "입력 완료"
    }

    private var summaryBadgeColor: Color {
        if model.state.isLoading { return MoaLogColor.mutedInk }
        return model.state.summary.hasMissingAmounts ? MoaLogColor.error : MoaLogColor.tertiary
    }

    private var itemSymbol: String {
        switch selectedTab {
        case .income: "banknote.fill"
        case .fixedExpense: "house.fill"
        case .savings: "leaf.fill"
        case .variableExpense: "cart.fill"
        }
    }

    private func ownerLabel(_ item: MonthlyPlanItem) -> String {
        guard let order = item.ownerMemberOrder?.int32Value else { return "공동" }
        return setup.members.first(where: { $0.order == order })?.displayName ?? "개인"
    }

    private func statusTitle(_ status: PlanItemStatus) -> String {
        status == .confirmed ? "확정" : "예상"
    }

    private func statusColor(_ item: MonthlyPlanItem) -> Color {
        if item.amountWon == nil { return MoaLogColor.outline }
        return item.status == .confirmed ? MoaLogColor.mutedInk : planBlue
    }

    private func amountText(_ amount: Int64?) -> String {
        amount.map(formatWon) ?? "미입력"
    }

    private func rateText(_ rate: Double?) -> String {
        guard let rate else {
            let income = model.state.summary.incomeWon?.int64Value
            return income == 0 ? "계산 불가" : "—"
        }
        return formatPercent(rate)
    }

    private var planBlue: Color { Color(red: 0.165, green: 0.412, blue: 0.675) }
    private var planBlueSurface: Color { Color(red: 0.922, green: 0.953, blue: 0.980) }
}

private struct PlanCard<Content: View>: View {
    let insets: CGFloat
    @ViewBuilder let content: Content

    init(insets: CGFloat = 16, @ViewBuilder content: () -> Content) {
        self.insets = insets
        self.content = content()
    }

    var body: some View {
        content
            .padding(insets)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(MoaLogColor.cardBorder, lineWidth: 1))
            .shadow(color: MoaLogColor.teal.opacity(0.04), radius: 4, y: 2)
    }
}

private extension Optional where Wrapped == String {
    var takeIfNotBlank: String? {
        guard let value = self, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return value
    }
}
