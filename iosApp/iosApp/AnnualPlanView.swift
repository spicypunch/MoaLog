import SharedKit
import SwiftUI

@MainActor
final class AnnualPlanViewModel: ObservableObject {
    @Published private(set) var state: AnnualPlanUiState

    private let store: IosAnnualPlanStore
    private var observation: IosObservation?

    init(dependencies: IosDependencies, year: Int) {
        let store = dependencies.annualPlanStore(year: Int32(year))
        self.store = store
        state = store.currentState
        observation = store.observe { [weak self] state in
            DispatchQueue.main.async { self?.state = state }
        }
    }

    deinit {
        observation?.cancel()
        store.close()
    }

    func select(_ section: NativeAnnualSection) { store.selectSection(section: section.shared) }
    func showTable() { store.showTable() }
    func showMonthlyList() { store.showMonthlyList() }
    func retry() { store.retry() }
}

enum NativeAnnualSection: CaseIterable, Hashable {
    case income, fixedExpense, variableExpense, savings

    var title: String {
        switch self {
        case .income: "수입"
        case .fixedExpense: "고정지출"
        case .variableExpense: "변동지출"
        case .savings: "저축·투자"
        }
    }

    var shared: AnnualPlanSection {
        switch self {
        case .income: .income
        case .fixedExpense: .fixedexpense
        case .variableExpense: .variableexpense
        case .savings: .savings
        }
    }

    var planType: PlanItemType? {
        switch self {
        case .income: .income
        case .fixedExpense: .fixedexpense
        case .variableExpense: nil
        case .savings: .savings
        }
    }

    var planTab: NativePlanTab {
        switch self {
        case .income: .income
        case .fixedExpense: .fixedExpense
        case .variableExpense: .variableExpense
        case .savings: .savings
        }
    }

    static func from(_ section: AnnualPlanSection) -> Self {
        if section == .fixedexpense { return .fixedExpense }
        if section == .variableexpense { return .variableExpense }
        if section == .savings { return .savings }
        return .income
    }
}

struct AnnualPlanView: View {
    @StateObject private var model: AnnualPlanViewModel

    let members: [LedgerMember]
    let initialSection: NativeAnnualSection
    let onClose: () -> Void
    let onOpenMonth: (YearMonthKey, NativeAnnualSection) -> Void
    let onOpenEditor: (Int64?, PlanItemType, YearMonthKey) -> Void
    let onOpenRecords: (YearMonthKey, NativeAnnualSection) -> Void

    init(
        dependencies: IosDependencies,
        setup: LedgerSetup,
        year: Int,
        initialSection: NativeAnnualSection = .income,
        onClose: @escaping () -> Void,
        onOpenMonth: @escaping (YearMonthKey, NativeAnnualSection) -> Void,
        onOpenEditor: @escaping (Int64?, PlanItemType, YearMonthKey) -> Void,
        onOpenRecords: @escaping (YearMonthKey, NativeAnnualSection) -> Void
    ) {
        _model = StateObject(wrappedValue: AnnualPlanViewModel(dependencies: dependencies, year: year))
        members = setup.members.sorted { $0.order < $1.order }
        self.initialSection = initialSection
        self.onClose = onClose
        self.onOpenMonth = onOpenMonth
        self.onOpenEditor = onOpenEditor
        self.onOpenRecords = onOpenRecords
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar
            sectionPicker

            if model.state.isLoading {
                Spacer()
                ProgressView("연간 계획을 불러오는 중이에요")
                    .font(MoaLogFont.medium(14))
                    .tint(MoaLogColor.teal)
                    .foregroundStyle(MoaLogColor.mutedInk)
                Spacer()
            } else if let error = model.state.error {
                Spacer()
                failureView(error)
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(spacing: 12) {
                        summaryCard
                        viewModeControl
                        if model.state.viewMode == .table {
                            legend
                            annualTable
                        } else {
                            monthlyList
                        }
                        Text("월을 누르면 해당 월의 계획으로 이동해요.")
                            .font(MoaLogFont.regular(12, relativeTo: .caption))
                            .foregroundStyle(MoaLogColor.mutedInk)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .frame(maxWidth: 560)
                    .frame(maxWidth: .infinity)
                }
            }
        }
        .background(MoaLogColor.homeCanvas.ignoresSafeArea())
        .onAppear { model.select(initialSection) }
    }

    private var selectedSection: NativeAnnualSection { .from(model.state.selectedSection) }

    private var topBar: some View {
        HStack(spacing: 8) {
            Button(action: onClose) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 17, weight: .semibold))
                    .frame(width: 48, height: 48)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .foregroundStyle(MoaLogColor.ink)
            .accessibilityLabel("뒤로 가기")

            Text("\(String(model.state.year))년 연간 한눈에 보기")
                .font(MoaLogFont.bold(17, relativeTo: .headline))
                .foregroundStyle(MoaLogColor.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.75)

            Spacer()

            Image(systemName: "slider.horizontal.3")
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(MoaLogColor.mutedInk)
                .frame(width: 48, height: 48)
                .accessibilityHidden(true)
        }
        .padding(.horizontal, 4)
        .background(MoaLogColor.homeCanvas.opacity(0.98))
        .overlay(alignment: .bottom) { Rectangle().fill(MoaLogColor.homeBorder).frame(height: 1) }
    }

    private var sectionPicker: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(NativeAnnualSection.allCases, id: \.self) { section in
                    let selected = selectedSection == section
                    Button { model.select(section) } label: {
                        HStack(spacing: 5) {
                            if section == .income { Image(systemName: "banknote.fill") }
                            Text(section.title)
                        }
                        .font(MoaLogFont.semibold(12, relativeTo: .caption))
                        .foregroundStyle(selected ? .white : MoaLogColor.mutedInk)
                        .padding(.horizontal, 14)
                        .frame(minHeight: 44)
                        .background(selected ? MoaLogColor.teal : MoaLogColor.surface, in: Capsule())
                        .overlay(Capsule().stroke(selected ? .clear : MoaLogColor.cardBorder, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
        }
        .background(MoaLogColor.homeCanvas)
    }

    private var summaryCard: some View {
        AnnualCard {
            VStack(alignment: .leading, spacing: 9) {
                HStack(spacing: 8) {
                    Text("₩")
                        .font(MoaLogFont.bold(12, relativeTo: .caption))
                        .foregroundStyle(MoaLogColor.teal)
                        .frame(width: 24, height: 24)
                        .background(MoaLogColor.secondaryContainer, in: Circle())
                    Text("\(String(model.state.year))년 \(selectedSection.title) 합계")
                        .font(MoaLogFont.medium(12, relativeTo: .caption))
                        .foregroundStyle(MoaLogColor.mutedInk)
                    Spacer()
                    Text(completenessLabel)
                        .font(MoaLogFont.bold(10, relativeTo: .caption2))
                        .foregroundStyle(MoaLogColor.mutedInk)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(MoaLogColor.divider, in: Capsule())
                }
                Text(model.state.hasKnownAmounts ? formatWon(model.state.knownTotalWon) : "미입력")
                    .font(MoaLogFont.bold(28, relativeTo: .title))
                    .foregroundStyle(MoaLogColor.teal)
                    .minimumScaleFactor(0.7)
                    .lineLimit(1)
                Rectangle().fill(MoaLogColor.homeBorder).frame(height: 1)
                HStack {
                    Label("월평균 환산액", systemImage: "calendar")
                        .font(MoaLogFont.medium(12, relativeTo: .caption))
                        .foregroundStyle(MoaLogColor.mutedInk)
                    Spacer()
                    Text(wonText(model.state.monthlyAverageWon, fallback: "계산 불가"))
                        .font(MoaLogFont.semibold(14))
                        .foregroundStyle(MoaLogColor.ink)
                }
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(String(model.state.year))년 \(selectedSection.title) 합계, \(model.state.hasKnownAmounts ? formatWon(model.state.knownTotalWon) : "미입력"), \(completenessLabel)")
    }

    private var viewModeControl: some View {
        HStack(spacing: 10) {
            Label("가로로 스크롤해 12개월 전체를 볼 수 있어요", systemImage: "hand.draw")
                .font(MoaLogFont.regular(11, relativeTo: .caption))
                .foregroundStyle(MoaLogColor.mutedInk)
            Spacer()
            Button {
                model.state.viewMode == .table ? model.showMonthlyList() : model.showTable()
            } label: {
                Label(model.state.viewMode == .table ? "월별 목록 보기" : "표로 보기", systemImage: model.state.viewMode == .table ? "list.bullet" : "tablecells")
                    .font(MoaLogFont.semibold(11, relativeTo: .caption))
                    .foregroundStyle(MoaLogColor.mutedInk)
                    .padding(.horizontal, 10)
                    .frame(minHeight: 48)
                    .background(MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 10))
            }
            .buttonStyle(.plain)
        }
    }

    private var legend: some View {
        HStack(spacing: 10) {
            legendItem(color: Color(red: 0.31, green: 0.34, blue: 0.38), title: "확정")
            legendItem(color: Color(red: 0.16, green: 0.41, blue: 0.68), title: "예상")
            legendItem(color: MoaLogColor.homeBorder, title: "미입력/0원")
            Spacer()
        }
    }

    private func legendItem(color: Color, title: String) -> some View {
        HStack(spacing: 4) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(title).font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
        }
    }

    private var annualTable: some View {
        let rows = model.state.rows
        return HStack(alignment: .top, spacing: 0) {
            VStack(spacing: 0) {
                tableLabel("\(selectedSection.title) 항목", height: 44, header: true)
                ForEach(rows, id: \.key) { row in
                    itemLabel(row, height: 56)
                }
                tableLabel("월 합계", subtitle: "총 \(rows.count)개 항목", height: 50, emphasized: true)
                tableLabel("월 잔액", subtitle: "수입-지출-저축", height: 50, emphasized: true)
            }
            .frame(width: 102)
            .zIndex(1)

            ScrollView(.horizontal, showsIndicators: true) {
                VStack(spacing: 0) {
                    HStack(spacing: 0) {
                        ForEach(model.state.months, id: \.month.description) { month in monthHeader(month) }
                        totalHeader
                    }
                    ForEach(rows, id: \.key) { row in
                        HStack(spacing: 0) {
                            ForEach(row.cells, id: \.month.description) { cell in valueCell(cell, row: row, height: 56) }
                            rowTotal(row, height: 56)
                        }
                    }
                    HStack(spacing: 0) {
                        ForEach(model.state.selectedCells, id: \.month.description) { cell in totalCell(cell, height: 50) }
                        sectionTotal(height: 50)
                    }
                    HStack(spacing: 0) {
                        ForEach(model.state.months, id: \.month.description) { month in balanceCell(month, height: 50) }
                        annualBalance(height: 50)
                    }
                }
            }
        }
        .background(MoaLogColor.surface)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(MoaLogColor.cardBorder, lineWidth: 1))
    }

    private func tableLabel(_ title: String, subtitle: String? = nil, height: CGFloat, header: Bool = false, emphasized: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(MoaLogFont.semibold(12, relativeTo: .caption)).foregroundStyle(emphasized ? MoaLogColor.teal : MoaLogColor.mutedInk).lineLimit(1)
            if let subtitle { Text(subtitle).font(MoaLogFont.regular(9, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk).lineLimit(1) }
        }
        .padding(.horizontal, 8)
        .frame(maxWidth: .infinity, minHeight: height, maxHeight: height, alignment: .leading)
        .background(header || emphasized ? MoaLogColor.surfaceLow : MoaLogColor.surface)
        .overlay(alignment: .bottom) { Rectangle().fill(MoaLogColor.divider).frame(height: 1) }
    }

    private func itemLabel(_ row: AnnualPlanRow, height: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(row.name).font(MoaLogFont.semibold(12, relativeTo: .caption)).foregroundStyle(MoaLogColor.ink).lineLimit(1)
            Text("\(ownerName(row.ownerMemberOrder)) · \(row.category)").font(MoaLogFont.regular(9, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk).lineLimit(1)
        }
        .padding(.horizontal, 8)
        .frame(maxWidth: .infinity, minHeight: height, maxHeight: height, alignment: .leading)
        .background(MoaLogColor.surface)
        .overlay(alignment: .bottom) { Rectangle().fill(MoaLogColor.divider).frame(height: 1) }
    }

    private func monthHeader(_ month: AnnualMonthSummary) -> some View {
        let cell = selectedCell(month)
        return Button { onOpenMonth(month.month, selectedSection) } label: {
            VStack(spacing: 3) {
                Text("\(month.month.month)월\(month.month == model.state.currentMonth ? " (당월)" : "")")
                    .font(MoaLogFont.bold(11, relativeTo: .caption2))
                    .foregroundStyle(month.month == model.state.currentMonth ? MoaLogColor.teal : MoaLogColor.ink)
                    .lineLimit(1)
                Text(statusTitle(cell.status)).font(MoaLogFont.regular(9, relativeTo: .caption2)).foregroundStyle(statusColor(cell.status))
            }
            .frame(width: 88, height: 44)
            .background(month.month == model.state.currentMonth ? MoaLogColor.secondaryContainer.opacity(0.65) : MoaLogColor.surfaceLow)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(month.month.month)월, \(statusTitle(cell.status)), 계획 열기")
    }

    private var totalHeader: some View {
        VStack(spacing: 2) {
            Text("연간 총합").font(MoaLogFont.bold(12, relativeTo: .caption)).foregroundStyle(MoaLogColor.teal)
            Text("12개월 누적").font(MoaLogFont.regular(9, relativeTo: .caption2)).foregroundStyle(MoaLogColor.teal)
        }
        .frame(width: 112, height: 44)
        .background(MoaLogColor.secondaryContainer)
    }

    private func valueCell(_ cell: AnnualCell, row: AnnualPlanRow, height: CGFloat) -> some View {
        Button { openCell(cell, row: row) } label: {
            VStack(spacing: 4) {
                Text(compactText(cell.amountWon))
                    .font(MoaLogFont.semibold(11, relativeTo: .caption2))
                    .foregroundStyle(cell.status == .estimated ? annualBlue : cell.amountWon == nil ? MoaLogColor.mutedInk : MoaLogColor.ink)
                    .lineLimit(1)
                statusBadge(cell.status)
            }
            .frame(width: 88, height: height)
            .background(MoaLogColor.surface)
            .overlay(alignment: .bottom) { Rectangle().fill(MoaLogColor.divider).frame(height: 1) }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(row.name), \(cell.month.month)월, \(wonText(cell.amountWon)), \(statusTitle(cell.status))")
        .accessibilityHint(row.planType == nil ? "지출 기록을 엽니다" : "계획 항목을 편집합니다")
    }

    private func totalCell(_ cell: AnnualCell, height: CGFloat) -> some View {
        Button { onOpenMonth(cell.month, selectedSection) } label: {
            Text(compactText(cell.amountWon))
                .font(MoaLogFont.bold(11, relativeTo: .caption2))
                .foregroundStyle(cell.amountWon == nil ? MoaLogColor.mutedInk : MoaLogColor.ink)
                .frame(width: 88, height: height)
                .background(MoaLogColor.surfaceLow)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(cell.month.month)월 \(selectedSection.title) 합계, \(wonText(cell.amountWon))")
    }

    private func rowTotal(_ row: AnnualPlanRow, height: CGFloat) -> some View {
        VStack(spacing: 2) {
            Text(formatAnnualCellAmount(row.knownTotalWon)).font(MoaLogFont.bold(12, relativeTo: .caption)).foregroundStyle(MoaLogColor.teal)
            Text(row.isComplete ? "원" : "원 · 일부 미입력").font(MoaLogFont.regular(9, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
        }
        .frame(width: 112, height: height)
        .background(MoaLogColor.sage.opacity(0.75))
    }

    private func sectionTotal(height: CGFloat) -> some View {
        VStack(spacing: 2) {
            Text(formatAnnualCellAmount(model.state.knownTotalWon)).font(MoaLogFont.bold(12, relativeTo: .caption)).foregroundStyle(MoaLogColor.teal)
            Text(model.state.isYearComplete ? "원" : "원 · 알려진 금액").font(MoaLogFont.regular(9, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
        }
        .frame(width: 112, height: height)
        .background(MoaLogColor.secondaryContainer)
    }

    private func balanceCell(_ month: AnnualMonthSummary, height: CGFloat) -> some View {
        Button { onOpenMonth(month.month, selectedSection) } label: {
            Text(compactText(month.balanceWon, fallback: "계산 불가"))
                .font(MoaLogFont.bold(11, relativeTo: .caption2))
                .foregroundStyle(month.balanceWon == nil ? MoaLogColor.mutedInk : MoaLogColor.teal)
                .frame(width: 88, height: height)
                .background(MoaLogColor.surfaceLow)
        }.buttonStyle(.plain)
    }

    private func annualBalance(height: CGFloat) -> some View {
        let balances = model.state.months.compactMap { $0.balanceWon?.int64Value }
        return VStack(spacing: 2) {
            Text(balances.count == 12 ? safeAnnualSum(balances).map(formatAnnualCellAmount) ?? "계산 불가" : "계산 불가")
                .font(MoaLogFont.bold(12, relativeTo: .caption)).foregroundStyle(MoaLogColor.teal)
            Text("연간 잔액").font(MoaLogFont.regular(9, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
        }
        .frame(width: 112, height: height)
        .background(MoaLogColor.secondaryContainer)
    }

    private var monthlyList: some View {
        LazyVStack(spacing: 8) {
            ForEach(model.state.months, id: \.month.description) { month in
                monthlyListRow(month)
            }
        }
    }

    private func monthlyListRow(_ month: AnnualMonthSummary) -> some View {
        Button { onOpenMonth(month.month, selectedSection) } label: {
            VStack(spacing: 8) {
                HStack {
                    Text("\(month.month.month)월").font(MoaLogFont.bold(14)).foregroundStyle(MoaLogColor.ink)
                    if month.month == model.state.currentMonth {
                        Text("당월").font(MoaLogFont.bold(9, relativeTo: .caption2)).foregroundStyle(MoaLogColor.teal)
                            .padding(.horizontal, 7).padding(.vertical, 3).background(MoaLogColor.sage, in: Capsule())
                    }
                    Spacer()
                    Text("계획 열기").font(MoaLogFont.semibold(11, relativeTo: .caption2)).foregroundStyle(MoaLogColor.teal)
                }
                HStack(spacing: 8) {
                    monthMetric("수입", month.income)
                    monthMetric("고정지출", month.fixedExpense)
                    monthMetric("변동지출", month.variableExpense)
                    monthMetric("저축", month.savings)
                }
                HStack {
                    Text("월 잔액").font(MoaLogFont.medium(11, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
                    Spacer()
                    Text(wonText(month.balanceWon, fallback: "계산 불가")).font(MoaLogFont.bold(12, relativeTo: .caption)).foregroundStyle(MoaLogColor.teal)
                }
                .padding(7).background(MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 8))
            }
            .padding(10).background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(MoaLogColor.cardBorder, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(month.month.month)월, \(selectedSection.title) \(wonText(selectedCell(month).amountWon)), 계획 열기")
    }

    private func monthMetric(_ title: String, _ cell: AnnualCell) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(MoaLogFont.regular(8, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk).lineLimit(1)
            Text(compactText(cell.amountWon)).font(MoaLogFont.bold(9, relativeTo: .caption2)).foregroundStyle(MoaLogColor.ink).lineLimit(1)
        }
        .padding(6).frame(maxWidth: .infinity, alignment: .leading).background(MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 7))
    }

    private func failureView(_ message: String) -> some View {
        VStack(spacing: 14) {
            Image(systemName: "exclamationmark.circle").font(.system(size: 34)).foregroundStyle(MoaLogColor.error)
            Text(message).font(MoaLogFont.medium(14)).foregroundStyle(MoaLogColor.mutedInk).multilineTextAlignment(.center)
            Button("다시 시도", action: model.retry).font(MoaLogFont.semibold(15)).foregroundStyle(.white).padding(.horizontal, 24).frame(minHeight: 48).background(MoaLogColor.teal, in: RoundedRectangle(cornerRadius: 12))
        }.padding(24)
    }

    private func statusBadge(_ status: AnnualCellStatus) -> some View {
        Text(statusTitle(status))
            .font(MoaLogFont.bold(8, relativeTo: .caption2))
            .foregroundStyle(statusColor(status))
            .padding(.horizontal, 5).padding(.vertical, 2)
            .background(statusColor(status).opacity(0.10), in: RoundedRectangle(cornerRadius: 4))
    }

    private var completenessLabel: String {
        if model.state.isYearComplete { return "전체 12개월 집계" }
        return model.state.hasKnownAmounts ? "일부 미입력 · 알려진 금액" : "12개월 미입력"
    }

    private func selectedCell(_ month: AnnualMonthSummary) -> AnnualCell {
        switch selectedSection {
        case .income: month.income
        case .fixedExpense: month.fixedExpense
        case .variableExpense: month.variableExpense
        case .savings: month.savings
        }
    }

    private func ownerName(_ order: KotlinInt?) -> String {
        guard let value = order?.intValue, let member = members.first(where: { $0.order == value }) else { return "공동" }
        return member.displayName
    }

    private func openCell(_ cell: AnnualCell, row: AnnualPlanRow) {
        if let type = row.planType {
            onOpenEditor(cell.sourceItemId?.int64Value, type, cell.month)
        } else {
            onOpenRecords(cell.month, selectedSection)
        }
    }

    private var annualBlue: Color { Color(red: 0.16, green: 0.41, blue: 0.68) }

    private func statusTitle(_ status: AnnualCellStatus) -> String {
        if status == .confirmed { return "확정" }
        if status == .estimated { return "예상" }
        if status == .mixed { return "혼재" }
        if status == .partial { return "일부 미입력" }
        if status == .zero { return "0원" }
        return "미입력"
    }

    private func statusColor(_ status: AnnualCellStatus) -> Color {
        if status == .estimated { return annualBlue }
        if status == .partial { return MoaLogColor.error }
        return status == .missing || status == .zero ? MoaLogColor.mutedInk : MoaLogColor.ink
    }

    private func compactText(_ value: KotlinLong?, fallback: String = "미입력") -> String {
        guard let value else { return fallback }
        return formatAnnualCellAmount(value.int64Value)
    }

    private func wonText(_ value: KotlinLong?, fallback: String = "미입력") -> String {
        guard let value else { return fallback }
        return formatWon(value.int64Value)
    }
}

private struct AnnualCard<Content: View>: View {
    @ViewBuilder let content: Content
    init(@ViewBuilder content: () -> Content) { self.content = content() }
    var body: some View {
        content.padding(12).frame(maxWidth: .infinity, alignment: .leading)
            .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(MoaLogColor.cardBorder, lineWidth: 1))
            .shadow(color: MoaLogColor.ink.opacity(0.04), radius: 3, y: 2)
    }
}

private func formatAnnualCellAmount(_ value: Int64) -> String {
    let magnitude = value.magnitude
    if magnitude >= 100_000_000 { return String(format: "%.1f억", Double(value) / 100_000_000) }
    if magnitude >= 10_000 { return "\(value / 10_000)만" }
    return "\(value)"
}

private func safeAnnualSum(_ values: [Int64]) -> Int64? {
    values.reduce(Optional<Int64>(0)) { partial, value in
        guard let partial else { return nil }
        let result = partial.addingReportingOverflow(value)
        return result.overflow ? nil : result.partialValue
    }
}
