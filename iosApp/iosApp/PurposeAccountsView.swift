import SharedKit
import SwiftUI

@MainActor
private final class PurposeAccountsViewModel: ObservableObject {
    @Published private(set) var state: PurposeAccountsUiState
    private let store: IosPurposeAccountsStore
    private var observation: IosObservation?
    init(dependencies: IosDependencies, month: YearMonthKey) {
        let store = dependencies.purposeAccountsStore(initialMonth: month)
        self.store = store; state = store.currentState
        observation = store.observe { [weak self] state in DispatchQueue.main.async { self?.state = state } }
    }
    deinit { observation?.cancel(); store.close() }
    func previousMonth() { store.previousMonth() }
    func nextMonth() { store.nextMonth() }
    func select(_ month: YearMonthKey) { store.selectMonth(year: month.year, month: month.month) }
    func retry() { store.retry() }
}

struct PurposeAccountsView: View {
    @StateObject private var model: PurposeAccountsViewModel
    @State private var editorRoute: AssetEditorRoute?
    @SceneStorage("moalog.purpose.month.v1") private var restoredMonth = ""
    private let dependencies: IosDependencies
    private let setup: LedgerSetup
    private let onClose: (YearMonthKey) -> Void

    init(dependencies: IosDependencies, setup: LedgerSetup, initialMonth: YearMonthKey, onClose: @escaping (YearMonthKey) -> Void) {
        self.dependencies = dependencies; self.setup = setup; self.onClose = onClose
        _model = StateObject(wrappedValue: PurposeAccountsViewModel(dependencies: dependencies, month: initialMonth))
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(spacing: 14) {
                    monthSelector
                    if model.state.isLoading { ProgressView("목적통장을 불러오는 중이에요").frame(minHeight: 280) }
                    else if let error = model.state.loadError { errorView(error) }
                    else if model.state.rows.isEmpty { emptyView }
                    else { totalCard; accountList; if missingRows.count > 0 { fillMissingButton }; addButton; infoCard }
                }.padding(16).frame(maxWidth: 480).frame(maxWidth: .infinity)
            }
            .background(MoaLogColor.homeCanvas.ignoresSafeArea())
            .navigationTitle("목적통장 현황").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button { onClose(model.state.month) } label: { Image(systemName: "chevron.left").frame(width: 44, height: 44) }.accessibilityLabel("자산으로") }
                ToolbarItem(placement: .topBarTrailing) { Button("통장 관리") { openNew() }.frame(minHeight: 44) }
            }
        }
        .onAppear { if let month = parseAssetMonth(restoredMonth), month != model.state.month { model.select(month) } }
        .onChange(of: model.state.month) { _, month in restoredMonth = String(format: "%04d-%02d", month.year, month.month) }
        .fullScreenCover(item: $editorRoute) { route in AssetEditorView(dependencies: dependencies, setup: setup, route: route, onClose: { editorRoute = nil }, onSaved: { month in model.select(month); editorRoute = nil }) }
    }

    private var monthSelector: some View {
        HStack { Button(action: model.previousMonth) { Image(systemName: "chevron.left").frame(width: 44, height: 44) }.accessibilityLabel("이전 달").disabled(isMinimumMonth(model.state.month)); Spacer(); Label("\(model.state.month.year)년 \(model.state.month.month)월", systemImage: "calendar").font(MoaLogFont.semibold(15)); Spacer(); Button(action: model.nextMonth) { Image(systemName: "chevron.right").frame(width: 44, height: 44) }.accessibilityLabel("다음 달").disabled(isMaximumMonth(model.state.month)) }
            .padding(.horizontal, 8).frame(minHeight: 52).purposeCard()
    }

    private var totalCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("입력된 잔액 합계").font(MoaLogFont.medium(11, relativeTo: .caption)); Text(model.state.hasTotalOverflow ? "금액 범위 초과" : model.state.enteredTotalWon.map { formatWon($0.int64Value) } ?? "미입력").font(MoaLogFont.bold(30, relativeTo: .largeTitle)); Label("총 \(model.state.rows.count)개 통장 운용 중", systemImage: "building.columns").font(MoaLogFont.medium(11, relativeTo: .caption)).foregroundStyle(MoaLogColor.mutedInk)
            if let delta = model.state.previousMonthDeltaWon?.int64Value { Label("전월 대비 \(signedWon(delta))", systemImage: delta >= 0 ? "arrow.up" : "arrow.down").font(MoaLogFont.semibold(11)).foregroundStyle(delta >= 0 ? MoaLogColor.teal : MoaLogColor.error) }
            if model.state.incompleteCount > 0 { Label("\(model.state.incompleteCount)개 통장의 잔액 확인이 필요해요", systemImage: "exclamationmark.circle.fill").font(MoaLogFont.medium(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.error) }
            if model.state.hasTotalOverflow { Label("금액 범위를 초과한 통장이 있어요", systemImage: "exclamationmark.triangle.fill").font(MoaLogFont.medium(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.error) }
            Text("목적통장 잔액은 일반 저축 납입액과 별도로 관리되는 보유 잔액입니다.").font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
        }.padding(16).frame(maxWidth: .infinity, alignment: .leading).purposeCard().accessibilityElement(children: .combine)
    }

    private var accountList: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack { Text("통장별 잔액").font(MoaLogFont.semibold(15)); Text("\(model.state.rows.count)").font(MoaLogFont.medium(10, relativeTo: .caption2)); Spacer(); Text("\(model.state.month.year).\(String(format: "%02d", model.state.month.month)) 기준").font(MoaLogFont.regular(9, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk) }
            ForEach(model.state.rows, id: \.asset.id) { row in
                Button { edit(row.asset.id) } label: {
                    HStack(spacing: 12) {
                        Image(systemName: purposeSymbol(row.asset.id)).foregroundStyle(MoaLogColor.teal).frame(width: 40, height: 40).background(MoaLogColor.secondaryContainer, in: Circle())
                        VStack(alignment: .leading, spacing: 3) { Text(row.asset.name).font(MoaLogFont.semibold(13)); Text(ownerText(row.asset.ownerMemberOrder)).font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk) }
                        Spacer()
                        VStack(alignment: .trailing, spacing: 3) { Text(row.hasOverflow ? "금액 범위 초과" : row.value.amountWon.map { formatWon($0.int64Value) } ?? "미입력").font(MoaLogFont.bold(12)); Text(row.hasOverflow ? "확인 필요" : assetStatusText(row.value.status)).font(MoaLogFont.medium(9, relativeTo: .caption2)).foregroundStyle(row.hasOverflow ? MoaLogColor.error : statusColor(row.value.status)); if !row.hasOverflow, let change = row.monthlyChangeWon?.int64Value { Text("다음 달 \(change >= 0 ? "+" : "")\(formatWon(change))").font(MoaLogFont.regular(9, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk) } }
                        Image(systemName: "chevron.right").foregroundStyle(MoaLogColor.mutedInk)
                    }.frame(minHeight: 62).contentShape(Rectangle()).accessibilityElement(children: .combine)
                }.buttonStyle(.plain).accessibilityHint(row.hasOverflow ? "범위를 초과한 잔액을 수정합니다" : row.value.amountWon == nil ? "미입력 잔액을 입력합니다" : "통장 정보를 수정합니다")
            }
        }.padding(16).purposeCard()
    }

    private var fillMissingButton: some View { Button { if let row = missingRows.first { edit(row.asset.id) } } label: { Label("미입력 내역 채우기", systemImage: "square.and.pencil").frame(maxWidth: .infinity, minHeight: 52) }.buttonStyle(.borderedProminent).tint(MoaLogColor.teal).accessibilityHint("첫 번째 미입력 통장의 잔액 입력을 시작합니다") }
    private var addButton: some View { Button(action: openNew) { Label("목적통장 추가", systemImage: "plus.circle.fill").frame(maxWidth: .infinity, minHeight: 52) }.buttonStyle(.borderedProminent).tint(MoaLogColor.teal) }
    private var infoCard: some View { Label("목적통장별 현재 잔액과 예상 잔액을 월 단위로 관리할 수 있어요.", systemImage: "lightbulb").font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk).padding(14).frame(maxWidth: .infinity, alignment: .leading).purposeCard() }
    private var emptyView: some View { VStack(spacing: 14) { Image(systemName: "building.columns").font(.system(size: 34)).foregroundStyle(MoaLogColor.teal); Text("등록된 목적통장이 없어요").font(MoaLogFont.bold(19, relativeTo: .title2)); Text("여행, 데이트, 비상금처럼 목적별 통장을 추가해 보세요.").font(MoaLogFont.regular(12)).foregroundStyle(MoaLogColor.mutedInk).multilineTextAlignment(.center); addButton }.padding(26).frame(maxWidth: .infinity, minHeight: 320).purposeCard() }
    private func errorView(_ error: String) -> some View { VStack(spacing: 12) { Text(error).foregroundStyle(MoaLogColor.error); Button("다시 시도", action: model.retry).frame(minHeight: 44) }.font(MoaLogFont.medium(13)).frame(minHeight: 280) }
    private var missingRows: [PurposeAccountRow] { model.state.rows.filter { $0.value.amountWon == nil && !$0.hasOverflow } }
    private func openNew() { editorRoute = AssetEditorRoute(assetId: nil, month: model.state.month, mode: .newasset, purposeAccount: true) }
    private func edit(_ id: Int64) { editorRoute = AssetEditorRoute(assetId: id, month: model.state.month, mode: .newasset, purposeAccount: true) }
    private func purposeSymbol(_ id: Int64) -> String { ["heart.fill", "airplane", "gift.fill", "person.2.fill"][Int(id.magnitude % 4)] }
    private func ownerText(_ value: KotlinInt?) -> String { guard let order = value?.intValue else { return "공동" }; return setup.members.first { $0.order == order }?.displayName ?? "개인" }
}

private extension View { func purposeCard() -> some View { background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 16)).overlay(RoundedRectangle(cornerRadius: 16).stroke(MoaLogColor.cardBorder)) } }
