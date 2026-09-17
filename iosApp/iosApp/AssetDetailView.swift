import SharedKit
import SwiftUI
import Charts

@MainActor
private final class AssetDetailViewModel: ObservableObject {
    @Published private(set) var state: AssetDetailUiState
    private let store: IosAssetDetailStore
    private var observation: IosObservation?
    init(dependencies: IosDependencies, route: AssetIdRoute) {
        let store = dependencies.assetDetailStore(assetId: route.assetId, month: route.month)
        self.store = store; state = store.currentState
        observation = store.observe { [weak self] state in DispatchQueue.main.async { self?.state = state } }
    }
    deinit { observation?.cancel(); store.close() }
    func previousMonth() { store.previousMonth() }
    func nextMonth() { store.nextMonth() }
    func select(_ month: YearMonthKey) { store.selectMonth(year: month.year, month: month.month) }
    func deleteAsset() { store.deleteAsset() }
    func deleteValuation() { store.deleteSelectedValuation() }
    func deleteGrowthRule() { store.deleteGrowthRule() }
    func retryMutation() { store.retryMutation() }
    func dismissMutationError() { store.dismissMutationError() }
    func retry() { store.retry() }
}

struct AssetDetailView: View {
    @StateObject private var model: AssetDetailViewModel
    @State private var editorRoute: AssetEditorRoute?
    @State private var deleteAssetPresented = false
    @State private var deleteValuationPresented = false
    @State private var deleteRulePresented = false
    @SceneStorage("moalog.asset.detail.month.v1") private var storedMonth = ""
    private let dependencies: IosDependencies
    private let setup: LedgerSetup
    private let route: AssetIdRoute
    private let onClose: (YearMonthKey) -> Void

    init(dependencies: IosDependencies, setup: LedgerSetup, route: AssetIdRoute, onClose: @escaping (YearMonthKey) -> Void) {
        self.dependencies = dependencies; self.setup = setup; self.route = route; self.onClose = onClose
        _model = StateObject(wrappedValue: AssetDetailViewModel(dependencies: dependencies, route: route))
    }

    var body: some View {
        NavigationStack {
            Group {
                if model.state.isLoading { ProgressView("자산 상세를 불러오는 중이에요") }
                else if let error = model.state.loadError { errorView(error) }
                else if let asset = model.state.asset { content(asset) }
            }
            .background(MoaLogColor.homeCanvas.ignoresSafeArea())
            .navigationTitle("자산 항목 상세").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button { onClose(model.state.args.month) } label: { Image(systemName: "chevron.left").frame(width: 44, height: 44) }.accessibilityLabel("자산 목록으로") }
                if model.state.asset != nil { ToolbarItem(placement: .topBarTrailing) { Button("수정") { openEditor(.newasset) }.frame(minHeight: 44) } }
            }
        }
        .onAppear { if let month = parseAssetMonth(storedMonth), month != model.state.args.month { model.select(month) } }
        .onChange(of: model.state.args.month) { _, month in storedMonth = String(format: "%04d-%02d", month.year, month.month) }
        .onChange(of: model.state.deleted) { _, deleted in if deleted { onClose(model.state.args.month) } }
        .fullScreenCover(item: $editorRoute) { editor in
            AssetEditorView(dependencies: dependencies, setup: setup, route: editor, onClose: { editorRoute = nil }, onSaved: { month in model.select(month); editorRoute = nil })
        }
        .alert("이 자산을 삭제할까요?", isPresented: $deleteAssetPresented) {
            Button("취소", role: .cancel) {}
            Button("삭제", role: .destructive, action: model.deleteAsset)
        } message: { Text("월별 잔액과 예상 규칙도 함께 삭제됩니다.") }
        .alert("이 달의 확정 잔액을 삭제할까요?", isPresented: $deleteValuationPresented) {
            Button("취소", role: .cancel) {}
            Button("삭제", role: .destructive, action: model.deleteValuation)
        }
        .alert("예상 규칙을 사용 중지할까요?", isPresented: $deleteRulePresented) {
            Button("취소", role: .cancel) {}
            Button("사용 중지", role: .destructive, action: model.deleteGrowthRule)
        } message: { Text("이미 입력한 확정 잔액은 유지됩니다.") }
        .alert("변경하지 못했어요", isPresented: mutationErrorBinding) {
            Button("다시 시도", action: model.retryMutation)
            Button("확인", role: .cancel, action: model.dismissMutationError)
        } message: { Text(model.state.mutationError ?? "잠시 후 다시 시도해 주세요") }
    }

    private func content(_ asset: AssetItem) -> some View {
        ScrollView {
            LazyVStack(spacing: 14) {
                monthHeader(asset)
                valueCard(asset)
                chartCard
                growthCard
                historyCard
                Button { openEditor(.monthlyvalue) } label: { Label("월별 잔액 직접 입력 / 수정", systemImage: "square.and.pencil").frame(maxWidth: .infinity, minHeight: 52) }.buttonStyle(.borderedProminent).tint(MoaLogColor.teal)
                Button(role: .destructive) { deleteAssetPresented = true } label: { Label("자산 항목 삭제", systemImage: "trash").frame(maxWidth: .infinity, minHeight: 48) }
                    .disabled(model.state.isMutating)
            }.padding(16).frame(maxWidth: 480).frame(maxWidth: .infinity)
        }
    }

    private func monthHeader(_ asset: AssetItem) -> some View {
        HStack(spacing: 12) {
            Image(systemName: assetSymbol(asset.type)).font(.system(size: 24)).foregroundStyle(MoaLogColor.teal).frame(width: 54, height: 54).background(MoaLogColor.secondaryContainer, in: Circle())
            VStack(alignment: .leading, spacing: 4) { Text(asset.name).font(MoaLogFont.bold(20, relativeTo: .title2)); Text("\(model.state.args.month.year)년 \(model.state.args.month.month)월 기준").font(MoaLogFont.regular(11, relativeTo: .caption)).foregroundStyle(MoaLogColor.mutedInk) }
            Spacer()
            VStack(spacing: 0) { Button(action: model.previousMonth) { Image(systemName: "chevron.left").frame(width: 44, height: 44) }.accessibilityLabel("이전 달").disabled(isMinimumMonth(model.state.args.month)); Button(action: model.nextMonth) { Image(systemName: "chevron.right").frame(width: 44, height: 44) }.accessibilityLabel("다음 달").disabled(isMaximumMonth(model.state.args.month)) }
        }.padding(16).assetDetailCard()
    }

    private func valueCard(_ asset: AssetItem) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack { Text(assetStatusText(model.state.value.status)).font(MoaLogFont.semibold(10, relativeTo: .caption2)).foregroundStyle(statusColor(model.state.value.status)); Spacer(); Text(ownerText(asset.ownerMemberOrder)).font(MoaLogFont.medium(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk) }
            Text(model.state.value.hasOverflow ? "금액 범위 초과" : model.state.value.amountWon.map { formatWon($0.int64Value) } ?? "미입력").font(MoaLogFont.bold(30, relativeTo: .largeTitle)).minimumScaleFactor(0.6).lineLimit(1)
            if let delta = model.state.previousMonthDeltaWon?.int64Value {
                Label("전월 대비 \(signedWon(delta))", systemImage: delta >= 0 ? "arrow.up.right" : "arrow.down.right")
                    .font(MoaLogFont.semibold(11, relativeTo: .caption)).foregroundStyle(delta >= 0 ? MoaLogColor.teal : MoaLogColor.error)
            }
            if let memo = asset.memo, !memo.isEmpty { Label(memo, systemImage: "text.quote").font(MoaLogFont.regular(11, relativeTo: .caption)).foregroundStyle(MoaLogColor.mutedInk) }
            if let rule = model.state.rule { Label("예상 월 증감액 \(signedWon(rule.monthlyIncreaseWon))", systemImage: rule.monthlyIncreaseWon >= 0 ? "arrow.up.right" : "arrow.down.right").font(MoaLogFont.semibold(11, relativeTo: .caption)).foregroundStyle(rule.monthlyIncreaseWon >= 0 ? MoaLogColor.teal : MoaLogColor.error) }
            if assetStatusText(model.state.value.status) == "확정" {
                Button(role: .destructive) { deleteValuationPresented = true } label: { Label("이 달 확정 잔액 삭제", systemImage: "trash").frame(minHeight: 44) }
                    .disabled(model.state.isMutating)
            }
        }.padding(16).frame(maxWidth: .infinity, alignment: .leading).assetDetailCard().accessibilityElement(children: .combine)
    }

    private var chartCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("자산 추이 및 예상").font(MoaLogFont.semibold(15)); Text("최근 실적과 향후 예상 잔액").font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
            Chart(model.state.chart, id: \.month) { point in
                if !point.value.hasOverflow, let amount = point.value.amountWon?.int64Value { LineMark(x: .value("월", point.month.month), y: .value("금액", amount)).foregroundStyle(statusColor(point.value.status)).lineStyle(StrokeStyle(lineWidth: 2, dash: isEstimated(point.value.status) ? [5, 4] : [])); PointMark(x: .value("월", point.month.month), y: .value("금액", amount)).foregroundStyle(statusColor(point.value.status)) }
            }.frame(height: 140).chartYAxis(.hidden)
            HStack { ForEach(model.state.chart, id: \.month) { Text("\($0.month.month)월\(isEstimated($0.value.status) ? " 예상" : "")").frame(maxWidth: .infinity) } }.font(MoaLogFont.regular(9, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
        }.padding(16).assetDetailCard().accessibilityElement(children: .combine)
    }

    private var growthCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack { Image(systemName: "wand.and.stars").foregroundStyle(MoaLogColor.teal); VStack(alignment: .leading) { Text("예상 금액 자동 증가 설정").font(MoaLogFont.semibold(14)); Text("목돈 형성 계획에 맞춰 잔액을 자동 계산").font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk) }; Spacer() }
            if let rule = model.state.rule {
                Text("기준 금액에서 매월 \(signedWon(rule.monthlyIncreaseWon)) 반영").font(MoaLogFont.medium(12)); Text("\(rule.startMonth.year)년 \(rule.startMonth.month)월부터 \(rule.durationMonths)개월").font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
            } else { Text("적용 중인 예상 규칙이 없어요").font(MoaLogFont.regular(12)).foregroundStyle(MoaLogColor.mutedInk) }
            Button { openEditor(.growthrule) } label: { Label(model.state.rule == nil ? "예상 규칙 만들기" : "예상 규칙 수정", systemImage: "slider.horizontal.3").frame(maxWidth: .infinity, minHeight: 48) }.buttonStyle(.bordered)
            if model.state.rule != nil {
                Button(role: .destructive) { deleteRulePresented = true } label: { Label("예상 규칙 사용 중지", systemImage: "xmark.circle").frame(maxWidth: .infinity, minHeight: 44) }
                    .disabled(model.state.isMutating)
            }
        }.padding(16).assetDetailCard()
    }

    private var historyCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack { Text("월별 잔액 기록").font(MoaLogFont.semibold(15)); Spacer(); Text("총 \(model.state.records.count)개월").font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk) }
            ForEach(model.state.records, id: \.month) { point in
                Button { editorRoute = AssetEditorRoute(assetId: route.assetId, month: point.month, mode: .monthlyvalue, purposeAccount: false) } label: {
                    HStack { Image(systemName: assetStatusText(point.value.status) == "확정" ? "checkmark.circle.fill" : "clock").foregroundStyle(point.value.hasOverflow ? MoaLogColor.error : statusColor(point.value.status)); VStack(alignment: .leading) { Text("\(point.month.year)년 \(point.month.month)월").font(MoaLogFont.semibold(12)); Text(point.value.hasOverflow ? "확인 필요" : assetStatusText(point.value.status)).font(MoaLogFont.regular(9, relativeTo: .caption2)).foregroundStyle(point.value.hasOverflow ? MoaLogColor.error : MoaLogColor.mutedInk) }; Spacer(); Text(point.value.hasOverflow ? "금액 범위 초과" : point.value.amountWon.map { formatWon($0.int64Value) } ?? "미입력").font(MoaLogFont.semibold(12)); Image(systemName: "chevron.right").foregroundStyle(MoaLogColor.mutedInk) }.frame(minHeight: 52).contentShape(Rectangle())
                }.buttonStyle(.plain).accessibilityHint("이 달의 잔액을 수정합니다")
            }
        }.padding(16).assetDetailCard()
    }

    private func errorView(_ error: String) -> some View { VStack(spacing: 14) { Text(error).foregroundStyle(MoaLogColor.error); Button("다시 시도", action: model.retry).frame(minHeight: 44) }.font(MoaLogFont.medium(13)) }
    private func openEditor(_ mode: AssetEditorMode) { editorRoute = AssetEditorRoute(assetId: route.assetId, month: model.state.args.month, mode: mode, purposeAccount: false) }
    private func ownerText(_ value: KotlinInt?) -> String { guard let order = value?.intValue else { return "공동" }; return setup.members.first { $0.order == order }?.displayName ?? "개인" }
    private var mutationErrorBinding: Binding<Bool> { Binding(get: { model.state.mutationError != nil }, set: { if !$0 { model.dismissMutationError() } }) }
}

private extension View { func assetDetailCard() -> some View { background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 16)).overlay(RoundedRectangle(cornerRadius: 16).stroke(MoaLogColor.cardBorder)) } }
