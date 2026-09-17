import SharedKit
import SwiftUI
import Charts

@MainActor
private final class AssetsViewModel: ObservableObject {
    @Published private(set) var state: AssetsUiState
    private let store: IosAssetsStore
    private var observation: IosObservation?

    init(dependencies: IosDependencies, initialMonth: YearMonthKey) {
        let store = dependencies.assetsStore(initialMonth: initialMonth)
        self.store = store
        state = store.currentState
        observation = store.observe { [weak self] value in DispatchQueue.main.async { self?.state = value } }
    }
    deinit { observation?.cancel(); store.close() }
    func previousMonth() { store.previousMonth() }
    func nextMonth() { store.nextMonth() }
    func select(_ month: YearMonthKey) { store.selectMonth(year: month.year, month: month.month) }
    func toggleSort() { store.toggleSort() }
    func retry() { store.retry() }
}

struct AssetIdRoute: Identifiable { let assetId: Int64; let month: YearMonthKey; var id: String { "\(assetId)-\(month.year)-\(month.month)" } }
struct AssetEditorRoute: Identifiable {
    let assetId: Int64?
    let month: YearMonthKey
    let mode: AssetEditorMode
    let purposeAccount: Bool
    var id: String { "\(assetId.map(String.init) ?? "new")-\(month.year)-\(month.month)-\(mode)-\(purposeAccount)" }
}
private struct PurposeRoute: Identifiable { let month: YearMonthKey; var id: String { "\(month.year)-\(month.month)" } }

struct AssetsTabView: View {
    @StateObject private var model: AssetsViewModel
    @State private var detailRoute: AssetIdRoute?
    @State private var editorRoute: AssetEditorRoute?
    @State private var purposeRoute: PurposeRoute?
    @SceneStorage("moalog.assets.month.v1") private var restoredMonth = ""

    private let dependencies: IosDependencies
    private let setup: LedgerSetup
    private let onOpenSavings: (YearMonthKey) -> Void

    init(dependencies: IosDependencies, setup: LedgerSetup, onOpenSavings: @escaping (YearMonthKey) -> Void) {
        self.dependencies = dependencies
        self.setup = setup
        self.onOpenSavings = onOpenSavings
        let now = Date(), calendar = Calendar.current
        let month = YearMonthKey(year: Int32(calendar.component(.year, from: now)), month: Int32(calendar.component(.month, from: now)))
        _model = StateObject(wrappedValue: AssetsViewModel(dependencies: dependencies, initialMonth: month))
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            ScrollView {
                LazyVStack(spacing: 14) {
                    monthPicker
                    if model.state.isLoading { ProgressView("자산을 불러오는 중이에요").frame(minHeight: 260) }
                    else if let error = model.state.loadError { errorView(error) }
                    else if model.state.rows.isEmpty { emptyView }
                    else {
                        totalCard
                        trendCard
                        shortcutCards
                        assetList
                        addButton
                    }
                }
                .padding(.horizontal, 16).padding(.vertical, 12)
                .frame(maxWidth: 480).frame(maxWidth: .infinity)
            }
        }
        .background(MoaLogColor.homeCanvas)
        .onAppear { if let month = parseAssetMonth(restoredMonth) { model.select(month) } }
        .onChange(of: model.state.month) { _, month in restoredMonth = String(format: "%04d-%02d", month.year, month.month) }
        .fullScreenCover(item: $detailRoute) { route in
            AssetDetailView(dependencies: dependencies, setup: setup, route: route, onClose: { month in
                model.select(month)
                detailRoute = nil
            })
        }
        .fullScreenCover(item: $purposeRoute) { route in
            PurposeAccountsView(dependencies: dependencies, setup: setup, initialMonth: route.month, onClose: { month in
                model.select(month)
                purposeRoute = nil
            })
        }
        .fullScreenCover(item: $editorRoute) { route in
            AssetEditorView(dependencies: dependencies, setup: setup, route: route, onClose: { editorRoute = nil }, onSaved: { month in
                model.select(month)
                editorRoute = nil
            })
        }
    }

    private var header: some View {
        HStack {
            Text("모아로그").font(MoaLogFont.bold(17, relativeTo: .headline)).foregroundStyle(MoaLogColor.teal)
            Text(setup.members.sorted { $0.order < $1.order }.map(\.displayName).joined(separator: " · "))
                .font(MoaLogFont.medium(10, relativeTo: .caption2)).padding(.horizontal, 8).frame(minHeight: 30)
                .background(MoaLogColor.surface, in: Capsule()).overlay(Capsule().stroke(MoaLogColor.cardBorder))
            Spacer()
            Text("자산").font(MoaLogFont.semibold(13, relativeTo: .caption)).foregroundStyle(MoaLogColor.mutedInk)
        }.padding(.horizontal, 18).frame(minHeight: 48)
    }

    private var monthPicker: some View {
        HStack {
            monthButton("이전 달", "chevron.left", model.previousMonth)
            Spacer()
            Text("\(model.state.month.year)년 \(model.state.month.month)월").font(MoaLogFont.semibold(15))
                .accessibilityAddTraits(.isHeader)
            Spacer()
            monthButton("다음 달", "chevron.right", model.nextMonth)
        }.frame(minHeight: 52).padding(.horizontal, 8)
        .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 14)).overlay(RoundedRectangle(cornerRadius: 14).stroke(MoaLogColor.cardBorder))
    }

    private func monthButton(_ label: String, _ symbol: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: symbol).frame(width: 44, height: 44) }.buttonStyle(.plain).accessibilityLabel(label)
            .disabled((symbol == "chevron.left" && isMinimumMonth(model.state.month)) || (symbol == "chevron.right" && isMaximumMonth(model.state.month)))
    }

    private var totalCard: some View {
        VStack(alignment: .leading, spacing: 9) {
            Label("부부 합산 자산", systemImage: "lock.fill").font(MoaLogFont.medium(11, relativeTo: .caption))
            Text(model.state.hasTotalOverflow ? "금액 범위 초과" : model.state.totalWon.map { formatWon($0.int64Value) } ?? "미입력")
                .font(MoaLogFont.bold(30, relativeTo: .largeTitle)).minimumScaleFactor(0.6).lineLimit(1)
            Text(totalStatusText).font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
            if let delta = model.state.previousMonthDeltaWon?.int64Value {
                Label("전월 대비 \(delta >= 0 ? "+" : "")\(formatWon(delta))", systemImage: delta >= 0 ? "arrow.up.right" : "arrow.down.right")
                    .font(MoaLogFont.semibold(11, relativeTo: .caption2)).foregroundStyle(delta >= 0 ? MoaLogColor.teal : MoaLogColor.error)
            }
        }.padding(16).frame(maxWidth: .infinity, alignment: .leading).assetCard()
        .accessibilityElement(children: .combine)
    }

    private var trendCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack { Text("월별 자산 추이").font(MoaLogFont.semibold(15)); Spacer(); statusLegend }
            Text("최근 6개월간 입력 및 예상 잔액").font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
            Chart(model.state.trend, id: \.month) { point in
                if !point.hasOverflow, let amount = point.amountWon?.int64Value {
                    LineMark(x: .value("월", point.month.month), y: .value("금액", amount))
                        .foregroundStyle(statusColor(point.status)).lineStyle(StrokeStyle(lineWidth: 2, dash: isEstimated(point.status) ? [5, 4] : []))
                    PointMark(x: .value("월", point.month.month), y: .value("금액", amount)).foregroundStyle(statusColor(point.status))
                }
            }.frame(height: 130).chartYAxis(.hidden)
            HStack { ForEach(model.state.trend, id: \.month) { Text("\($0.month.month)월").frame(maxWidth: .infinity) } }
                .font(MoaLogFont.regular(9, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
        }.padding(16).assetCard().accessibilityElement(children: .combine)
        .accessibilityLabel(trendAccessibility)
    }

    private var statusLegend: some View {
        HStack(spacing: 8) { Label("확정", systemImage: "circle.fill"); Label("예상", systemImage: "circle") }
            .font(MoaLogFont.regular(9, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
    }

    private var shortcutCards: some View {
        HStack(spacing: 10) {
            shortcut("목적통장 현황", symbol: "banknote", value: purposeShortcutValue) { purposeRoute = PurposeRoute(month: model.state.month) }
            shortcut("이번 달 저축", symbol: "book.closed", value: "계획에서 확인") { onOpenSavings(model.state.month) }
        }
    }
    private func shortcut(_ title: String, symbol: String, value: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 8) { HStack { Image(systemName: symbol); Spacer(); Image(systemName: "chevron.right") }; Text(title).font(MoaLogFont.semibold(12)); Text(value).font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk) }
                .padding(14).frame(maxWidth: .infinity, minHeight: 104, alignment: .leading).assetCard()
        }.buttonStyle(.plain).accessibilityHint("상세 화면을 엽니다")
    }

    private var assetList: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack { Text("자산별 상세 목록").font(MoaLogFont.semibold(15)); Text("\(model.state.rows.count)개").font(MoaLogFont.medium(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk); Spacer(); Button(action: model.toggleSort) { Label(assetSortLabel, systemImage: "arrow.up.arrow.down").frame(minHeight: 44) }.buttonStyle(.plain).font(MoaLogFont.medium(10, relativeTo: .caption2)) }
            ForEach(displayRows, id: \.asset.id) { row in
                Button { detailRoute = AssetIdRoute(assetId: row.asset.id, month: model.state.month) } label: { assetRow(row) }
                    .buttonStyle(.plain).accessibilityHint("자산 상세를 엽니다")
            }
        }.padding(16).assetCard()
    }

    private func assetRow(_ row: AssetRow) -> some View {
        HStack(spacing: 12) {
            Image(systemName: assetSymbol(row.asset.type)).foregroundStyle(MoaLogColor.teal).frame(width: 40, height: 40).background(MoaLogColor.secondaryContainer, in: Circle())
            VStack(alignment: .leading, spacing: 3) { Text(row.asset.name).font(MoaLogFont.semibold(13)); Text("\(ownerText(row.asset.ownerMemberOrder)) · \(row.asset.type.displayName)").font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk) }
            Spacer()
            VStack(alignment: .trailing, spacing: 3) { Text(row.hasOverflow ? "금액 범위 초과" : row.amountWon.map { formatWon($0.int64Value) } ?? "미입력").font(MoaLogFont.bold(12)); Text(row.hasOverflow ? "확인 필요" : assetStatusText(row.status)).font(MoaLogFont.medium(9, relativeTo: .caption2)).foregroundStyle(row.hasOverflow ? MoaLogColor.error : statusColor(row.status)) }
            Image(systemName: "chevron.right").foregroundStyle(MoaLogColor.mutedInk)
        }.frame(minHeight: 58).contentShape(Rectangle()).accessibilityElement(children: .combine)
    }

    private var addButton: some View { Button { editorRoute = AssetEditorRoute(assetId: nil, month: model.state.month, mode: .newasset, purposeAccount: false) } label: { Label("자산 항목 추가", systemImage: "plus.circle.fill").frame(maxWidth: .infinity, minHeight: 52) }.buttonStyle(.borderedProminent).tint(MoaLogColor.teal) }
    private var emptyView: some View { VStack(spacing: 16) { Image(systemName: "chart.line.uptrend.xyaxis").font(.system(size: 34)).foregroundStyle(MoaLogColor.teal).frame(width: 92, height: 92).background(MoaLogColor.secondaryContainer.opacity(0.7), in: Circle()); Text("아직 등록된 자산이 없어요").font(MoaLogFont.bold(20, relativeTo: .title2)); Text("현금, 예적금, 투자 등 부부의 자산을 등록하고 한눈에 관리해 보세요.").font(MoaLogFont.regular(13, relativeTo: .caption)).foregroundStyle(MoaLogColor.mutedInk).multilineTextAlignment(.center); addButton }.padding(26).frame(maxWidth: .infinity, minHeight: 360).assetCard() }
    private func errorView(_ error: String) -> some View { VStack(spacing: 12) { Text(error).foregroundStyle(MoaLogColor.error); Button("다시 시도", action: model.retry).frame(minHeight: 44) }.font(MoaLogFont.medium(13)).frame(maxWidth: .infinity, minHeight: 260) }

    private var displayRows: [AssetRow] { model.state.rows }
    private var assetSortLabel: String { String(describing: model.state.sort).lowercased().contains("name") ? "이름순" : "금액순" }
    private var totalStatusText: String {
        if model.state.hasTotalOverflow { return "일부 금액이 범위를 초과해 합계를 계산할 수 없어요" }
        if model.state.missingValueCount > 0 { return "\(model.state.missingValueCount)개 항목이 미입력되어 입력된 평가액만 합산했어요" }
        return "\(assetStatusText(model.state.totalStatus)) 금액의 합계예요"
    }
    private var purposeShortcutValue: String { if model.state.hasPurposeAccountOverflow { return "금액 범위 초과" }; let total = model.state.purposeAccountTotalWon.map { formatWon($0.int64Value) } ?? "미입력"; return model.state.purposeAccountMissingCount > 0 ? "\(total) · \(model.state.purposeAccountMissingCount)개 미입력" : total }
    private var trendAccessibility: String { model.state.trend.map { "\($0.month.year)년 \($0.month.month)월, \($0.hasOverflow ? "금액 범위 초과" : $0.amountWon.map { formatWon($0.int64Value) } ?? "미입력"), \(assetStatusText($0.status))" }.joined(separator: ", ") }
    private func ownerText(_ order: KotlinInt?) -> String { guard let order = order?.intValue else { return "공동" }; guard let name = setup.members.first(where: { $0.order == order })?.displayName else { return "개인" }; return "개인(\(name))" }
}

private extension View {
    func assetCard() -> some View { background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 16)).overlay(RoundedRectangle(cornerRadius: 16).stroke(MoaLogColor.cardBorder)) }
}

func assetStatusText(_ status: Any) -> String { let value = String(describing: status).lowercased(); if value.contains("confirm") { return "확정" }; if value.contains("estimate") { return "예상" }; if value.contains("mixed") { return "일부 예상" }; return "미입력" }
func isEstimated(_ status: Any) -> Bool { let value = String(describing: status).lowercased(); return value.contains("estimate") || value.contains("mixed") }
func statusColor(_ status: Any) -> Color { assetStatusText(status) == "미입력" ? MoaLogColor.error : isEstimated(status) ? .orange : MoaLogColor.teal }
func assetSymbol(_ type: AssetType) -> String { switch type { case .cash: "banknote"; case .deposit: "building.columns"; case .investment: "chart.line.uptrend.xyaxis"; case .housing: "house"; default: "wallet.bifold" } }
func parseAssetMonth(_ value: String) -> YearMonthKey? { let parts = value.split(separator: "-"); guard parts.count == 2, let year = Int32(parts[0]), let month = Int32(parts[1]), (1900...9999).contains(year), (1...12).contains(month) else { return nil }; return YearMonthKey(year: year, month: month) }
func isMinimumMonth(_ month: YearMonthKey) -> Bool { month.year == 1900 && month.month == 1 }
func isMaximumMonth(_ month: YearMonthKey) -> Bool { month.year == 9999 && month.month == 12 }
func signedWon(_ value: Int64) -> String { value >= 0 ? "+\(formatWon(value))" : formatWon(value) }
