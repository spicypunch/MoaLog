import SharedKit
import SwiftUI

@MainActor
final class CompositionAnalysisViewModel: ObservableObject {
    @Published private(set) var state: CompositionAnalysisUiState
    private let store: IosCompositionAnalysisStore
    private var observation: IosObservation?

    init(dependencies: IosDependencies, month: YearMonthKey) {
        let store = dependencies.compositionAnalysisStore(initialMonth: month, initialTab: .variableexpense)
        self.store = store
        state = store.currentState
        observation = store.observe { [weak self] state in
            DispatchQueue.main.async { self?.state = state }
        }
    }

    deinit { observation?.cancel(); store.close() }
    func select(_ tab: NativeCompositionTab) { store.selectTab(tab: tab.shared) }
    func selectMonthly() { store.selectMonthly() }
    func selectAnnual() { store.selectAnnual() }
    func previousPeriod() { store.previousPeriod() }
    func nextPeriod() { store.nextPeriod() }
    func retry() { store.retry() }
}

enum NativeCompositionTab: CaseIterable, Hashable {
    case fixedExpense, variableExpense, savings

    var title: String {
        switch self {
        case .fixedExpense: "고정지출"
        case .variableExpense: "변동지출"
        case .savings: "저축·투자"
        }
    }

    var shared: CompositionTab {
        switch self {
        case .fixedExpense: .fixedexpense
        case .variableExpense: .variableexpense
        case .savings: .savings
        }
    }

    var annualSection: NativeAnnualSection {
        switch self {
        case .fixedExpense: .fixedExpense
        case .variableExpense: .variableExpense
        case .savings: .savings
        }
    }

    static func from(_ value: CompositionTab) -> Self {
        if value == .fixedexpense { return .fixedExpense }
        if value == .savings { return .savings }
        return .variableExpense
    }
}

struct CompositionAnalysisView: View {
    @StateObject private var model: CompositionAnalysisViewModel
    let onClose: () -> Void
    let onOpenRecords: (YearMonthKey, String?) -> Void
    let onOpenAnnual: (Int, NativeAnnualSection) -> Void

    init(
        dependencies: IosDependencies,
        initialMonth: YearMonthKey,
        onClose: @escaping () -> Void,
        onOpenRecords: @escaping (YearMonthKey, String?) -> Void,
        onOpenAnnual: @escaping (Int, NativeAnnualSection) -> Void
    ) {
        _model = StateObject(wrappedValue: CompositionAnalysisViewModel(dependencies: dependencies, month: initialMonth))
        self.onClose = onClose
        self.onOpenRecords = onOpenRecords
        self.onOpenAnnual = onOpenAnnual
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar
            tabs
            if selectedTab == .variableExpense { periodModePicker }
            periodSelector

            if model.state.isLoading {
                Spacer()
                ProgressView("구성 분석을 불러오는 중이에요")
                    .font(MoaLogFont.medium(14)).tint(MoaLogColor.teal).foregroundStyle(MoaLogColor.mutedInk)
                Spacer()
            } else if let error = model.state.loadError {
                Spacer(); failure(error); Spacer()
            } else {
                ScrollView {
                    LazyVStack(spacing: 12) {
                        summaryCard
                        analysisCard
                        detailList
                        destinationButton
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .frame(maxWidth: 520)
                    .frame(maxWidth: .infinity)
                }
            }
        }
        .background(MoaLogColor.homeCanvas.ignoresSafeArea())
    }

    private var selectedTab: NativeCompositionTab { .from(model.state.selectedTab) }
    private var hasNegative: Bool { model.state.rows.contains { $0.isNegative } }

    private var topBar: some View {
        HStack {
            Button(action: onClose) {
                Image(systemName: "chevron.left").font(.system(size: 17, weight: .semibold)).frame(width: 48, height: 48)
            }
            .buttonStyle(.plain).foregroundStyle(MoaLogColor.ink).accessibilityLabel("뒤로 가기")
            Text("구성 분석").font(MoaLogFont.bold(17, relativeTo: .headline)).foregroundStyle(MoaLogColor.ink)
            Spacer()
            Image(systemName: "slider.horizontal.3").font(.system(size: 16)).foregroundStyle(MoaLogColor.mutedInk).frame(width: 48, height: 48).accessibilityHidden(true)
        }
        .padding(.horizontal, 4)
        .overlay(alignment: .bottom) { Rectangle().fill(MoaLogColor.homeBorder).frame(height: 1) }
    }

    private var tabs: some View {
        HStack(spacing: 4) {
            ForEach(NativeCompositionTab.allCases, id: \.self) { tab in
                let selected = tab == selectedTab
                Button { model.select(tab) } label: {
                    Text(tab.title)
                        .font(selected ? MoaLogFont.bold(12, relativeTo: .caption) : MoaLogFont.medium(12, relativeTo: .caption))
                        .foregroundStyle(selected ? MoaLogColor.teal : MoaLogColor.mutedInk)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(selected ? MoaLogColor.surface : .clear, in: RoundedRectangle(cornerRadius: 8))
                        .shadow(color: selected ? MoaLogColor.ink.opacity(0.07) : .clear, radius: 3, y: 1)
                }
                .buttonStyle(.plain).accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .padding(4).background(MoaLogColor.homeBorder.opacity(0.65), in: RoundedRectangle(cornerRadius: 11))
        .padding(.horizontal, 16).padding(.top, 8)
    }

    private var periodSelector: some View {
        HStack {
            periodButton("이전 기간", symbol: "chevron.left", action: model.previousPeriod)
            Spacer()
            Label(model.state.periodLabel, systemImage: "calendar")
                .font(MoaLogFont.semibold(14)).foregroundStyle(MoaLogColor.ink)
            Spacer()
            periodButton("다음 기간", symbol: "chevron.right", action: model.nextPeriod)
        }
        .padding(.horizontal, 16).padding(.top, 4)
    }

    private var periodModePicker: some View {
        HStack(spacing: 4) {
            periodModeButton("월간", selected: !model.state.usesAnnualPeriod, action: model.selectMonthly)
            periodModeButton("연간", selected: model.state.usesAnnualPeriod, action: model.selectAnnual)
        }
        .padding(3)
        .background(MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 10))
        .padding(.horizontal, 16)
        .padding(.top, 4)
    }

    private func periodModeButton(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(selected ? MoaLogFont.bold(11, relativeTo: .caption) : MoaLogFont.medium(11, relativeTo: .caption))
                .foregroundStyle(selected ? MoaLogColor.teal : MoaLogColor.mutedInk)
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(selected ? MoaLogColor.surface : .clear, in: RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(title) 분석")
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func periodButton(_ label: String, symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: symbol).font(.system(size: 14, weight: .semibold)).frame(width: 48, height: 48) }
            .buttonStyle(.plain).foregroundStyle(MoaLogColor.mutedInk).accessibilityLabel(label)
    }

    private var summaryCard: some View {
        AnalysisCard {
            VStack(alignment: .leading, spacing: 5) {
                HStack {
                    Text("\(selectedTab.title) 합계").font(MoaLogFont.medium(12, relativeTo: .caption)).foregroundStyle(MoaLogColor.mutedInk)
                    Spacer()
                    if !model.state.isComplete {
                        Text("일부 미입력").font(MoaLogFont.bold(9, relativeTo: .caption2)).foregroundStyle(MoaLogColor.error).padding(.horizontal, 7).padding(.vertical, 3).background(MoaLogColor.errorSurface, in: Capsule())
                    }
                }
                Text(signedWon(model.state.totalWon))
                    .font(MoaLogFont.bold(25, relativeTo: .title))
                    .foregroundStyle(model.state.totalWon < 0 ? MoaLogColor.error : MoaLogColor.teal)
                    .minimumScaleFactor(0.75).lineLimit(1)
                Text(hasNegative ? "음수 항목은 차감 금액으로 표시해요" : "\(model.state.rows.count)개 항목을 분석했어요")
                    .font(MoaLogFont.regular(12, relativeTo: .caption)).foregroundStyle(MoaLogColor.mutedInk)
            }
        }
    }

    private var analysisCard: some View {
        AnalysisCard {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text(hasNegative ? "항목별 증감 분석" : "카테고리별 비중").font(MoaLogFont.semibold(14)).foregroundStyle(MoaLogColor.ink)
                    Spacer()
                    Text("총 \(model.state.rows.count)개 항목").font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
                }
                if model.state.rows.isEmpty {
                    VStack(spacing: 10) {
                        Image(systemName: "chart.pie").font(.system(size: 28)).foregroundStyle(MoaLogColor.outline)
                        Text("분석할 데이터가 아직 없어요").font(MoaLogFont.medium(13)).foregroundStyle(MoaLogColor.mutedInk)
                    }.frame(maxWidth: .infinity, minHeight: 96)
                } else if hasNegative {
                    signedBars
                } else {
                    donut
                }
            }
        }
    }

    private var donut: some View {
        let rows = model.state.rows
        return ZStack {
            Circle().stroke(MoaLogColor.divider, lineWidth: 20)
            ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                let fraction = abs(row.percent?.doubleValue ?? 0) / 100
                let from = startForRow(index, rows: rows)
                Circle()
                    .trim(from: from, to: min(1, from + fraction))
                    .stroke(chartColor(index), style: StrokeStyle(lineWidth: 20, lineCap: .butt))
                    .rotationEffect(.degrees(-90))
            }
            VStack(spacing: 2) {
                Text(selectedTab.title).font(MoaLogFont.medium(11, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
                Text(model.state.rows.first.map(percentText) ?? "—")
                    .font(MoaLogFont.bold(18, relativeTo: .headline)).foregroundStyle(MoaLogColor.ink)
                Text("가장 큰 비중").font(MoaLogFont.regular(9, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
            }
        }
        .frame(width: 140, height: 140).frame(maxWidth: .infinity).padding(.vertical, 2)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(chartAccessibilityLabel)
    }

    private var signedBars: some View {
        VStack(spacing: 12) {
            ForEach(Array(model.state.rows.enumerated()), id: \.element.id) { index, row in
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text(row.title).font(MoaLogFont.medium(12, relativeTo: .caption)).foregroundStyle(MoaLogColor.mutedInk)
                        Spacer()
                        Text(signedWon(row.amountWon)).font(MoaLogFont.semibold(12, relativeTo: .caption)).foregroundStyle(row.isNegative ? MoaLogColor.error : MoaLogColor.teal)
                    }
                    GeometryReader { proxy in
                        let fraction = min(abs(row.percent?.doubleValue ?? 0) / 100, 1)
                        HStack(spacing: 0) {
                            if row.isNegative {
                                Spacer(minLength: 0)
                                RoundedRectangle(cornerRadius: 4).fill(MoaLogColor.errorSurface).frame(width: proxy.size.width * 0.5 * fraction)
                            } else {
                                Spacer().frame(width: proxy.size.width * 0.5)
                                RoundedRectangle(cornerRadius: 4).fill(chartColor(index)).frame(width: proxy.size.width * 0.5 * fraction)
                                Spacer(minLength: 0)
                            }
                        }
                        Rectangle().fill(MoaLogColor.outline.opacity(0.6)).frame(width: 1).frame(maxWidth: .infinity, alignment: .center)
                    }.frame(height: 18)
                }
            }
        }.padding(.vertical, 4)
        .accessibilityElement(children: .combine).accessibilityLabel(chartAccessibilityLabel)
    }

    private var detailList: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("카테고리 상세 목록").font(MoaLogFont.semibold(16, relativeTo: .headline)).foregroundStyle(MoaLogColor.ink)
                Spacer()
                Text("눌러서 이동").font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
            }
            ForEach(Array(model.state.rows.enumerated()), id: \.element.id) { index, row in
                Button { open(row) } label: {
                    HStack(spacing: 10) {
                        Image(systemName: row.isNegative ? "arrow.down.right" : symbol(index))
                            .font(.system(size: 16, weight: .semibold)).foregroundStyle(row.isNegative ? MoaLogColor.error : MoaLogColor.teal)
                            .frame(width: 36, height: 36).background(row.isNegative ? MoaLogColor.errorSurface.opacity(0.65) : chartColor(index).opacity(0.14), in: Circle())
                        VStack(alignment: .leading, spacing: 3) {
                            HStack(spacing: 5) {
                                Text(row.title).font(MoaLogFont.semibold(12, relativeTo: .caption)).foregroundStyle(MoaLogColor.ink).lineLimit(1)
                                if row.isNegative { Text("차감").font(MoaLogFont.bold(8, relativeTo: .caption2)).foregroundStyle(MoaLogColor.error).padding(.horizontal, 5).padding(.vertical, 2).background(MoaLogColor.errorSurface, in: Capsule()) }
                            }
                            Text(row.supportingText ?? selectedTab.title).font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk).lineLimit(1)
                        }
                        Spacer()
                        VStack(alignment: .trailing, spacing: 4) {
                            Text(signedWon(row.amountWon)).font(MoaLogFont.bold(12, relativeTo: .caption)).foregroundStyle(row.isNegative ? MoaLogColor.error : MoaLogColor.ink)
                            Text(percentText(row)).font(MoaLogFont.medium(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
                        }
                        Image(systemName: "chevron.right").font(.system(size: 11, weight: .bold)).foregroundStyle(MoaLogColor.outline)
                    }
                    .padding(.horizontal, 10).padding(.vertical, 6).frame(minHeight: 56).background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 10)).overlay(RoundedRectangle(cornerRadius: 10).stroke(MoaLogColor.cardBorder, lineWidth: 1))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(row.title), \(signedWon(row.amountWon)), 비중 \(percentAccessibilityText(row))")
                .accessibilityHint(selectedTab == .variableExpense ? "해당 지출 기록을 엽니다" : "연간 보기를 엽니다")
            }
        }
    }

    private var destinationButton: some View {
        Button {
            if selectedTab == .variableExpense && !model.state.usesAnnualPeriod { onOpenRecords(model.state.selectedMonth, nil) }
            else { onOpenAnnual(Int(model.state.selectedYear), selectedTab.annualSection) }
        } label: {
            Label(destinationTitle, systemImage: "arrow.right")
                .font(MoaLogFont.semibold(13, relativeTo: .caption)).foregroundStyle(MoaLogColor.teal).frame(maxWidth: .infinity, minHeight: 48)
                .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12)).overlay(RoundedRectangle(cornerRadius: 12).stroke(MoaLogColor.cardBorder, lineWidth: 1))
        }.buttonStyle(.plain)
    }

    private func failure(_ message: String) -> some View {
        VStack(spacing: 14) {
            Image(systemName: "exclamationmark.circle").font(.system(size: 34)).foregroundStyle(MoaLogColor.error)
            Text(message).font(MoaLogFont.medium(14)).foregroundStyle(MoaLogColor.mutedInk)
            Button("다시 시도", action: model.retry).font(MoaLogFont.semibold(15)).foregroundStyle(.white).padding(.horizontal, 24).frame(minHeight: 48).background(MoaLogColor.teal, in: RoundedRectangle(cornerRadius: 12))
        }.padding(24)
    }

    private func open(_ row: CompositionAnalysisRow) {
        if selectedTab == .variableExpense && !model.state.usesAnnualPeriod { onOpenRecords(model.state.selectedMonth, row.expenseCategoryId) }
        else { onOpenAnnual(Int(model.state.selectedYear), selectedTab.annualSection) }
    }

    private var destinationTitle: String {
        if selectedTab == .variableExpense && !model.state.usesAnnualPeriod {
            return "\(model.state.selectedMonth.month)월 변동지출 내역 전체보기"
        }
        return "연간 \(selectedTab.title) 보기"
    }

    private func startForRow(_ index: Int, rows: [CompositionAnalysisRow]) -> Double {
        rows.prefix(index).reduce(0) { $0 + abs($1.percent?.doubleValue ?? 0) / 100 }
    }

    private func chartColor(_ index: Int) -> Color {
        [MoaLogColor.error, MoaLogColor.teal, Color(red: 0.49, green: 0.72, blue: 0.69), Color(red: 0.86, green: 0.66, blue: 0.40)][index % 4]
    }

    private func symbol(_ index: Int) -> String { ["cart.fill", "fork.knife", "gift.fill", "house.fill"][index % 4] }
    private func signedWon(_ value: Int64) -> String {
        guard value < 0 else { return "(+) \(formatWon(value))" }
        if value == .min { return "(-) 9,223,372,036,854,775,808원" }
        return "(-) \(formatWon(-value))"
    }
    private func percentText(_ row: CompositionAnalysisRow) -> String {
        guard let percent = row.percent else { return "—" }
        return formatPercent(percent.doubleValue)
    }
    private func percentAccessibilityText(_ row: CompositionAnalysisRow) -> String {
        guard row.percent != nil else { return "계산 불가" }
        return percentText(row)
    }
    private var chartAccessibilityLabel: String {
        model.state.rows.map { row in
            "\(row.title) \(signedWon(row.amountWon)), \(percentAccessibilityText(row))"
        }.joined(separator: ", ")
    }
}

private struct AnalysisCard<Content: View>: View {
    @ViewBuilder let content: Content
    init(@ViewBuilder content: () -> Content) { self.content = content() }
    var body: some View {
        content.padding(12).frame(maxWidth: .infinity, alignment: .leading)
            .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(MoaLogColor.cardBorder, lineWidth: 1))
    }
}
