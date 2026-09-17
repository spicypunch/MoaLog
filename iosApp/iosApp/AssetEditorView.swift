import SharedKit
import SwiftUI

private struct StoredAssetDraft: Codable {
    let routeId: String, name: String, amount: String, duration: String, base: String, increase: String
    let type: String, memo: String, kind: String
    let owner: Int?, year: Int, month: Int
    var encoded: String { (try? JSONEncoder().encode(self)).flatMap { String(data: $0, encoding: .utf8) } ?? "" }
    static func decode(_ value: String) -> Self? { value.data(using: .utf8).flatMap { try? JSONDecoder().decode(Self.self, from: $0) } }
}

@MainActor
private final class AssetEditorViewModel: ObservableObject {
    @Published private(set) var state: AssetEditorUiState
    private let store: IosAssetEditorStore
    private var observation: IosObservation?
    init(dependencies: IosDependencies, route: AssetEditorRoute) {
        let store = dependencies.assetEditorStore(assetId: route.assetId.map { KotlinLong(value: $0) }, month: route.month, mode: route.mode, purposeAccount: route.purposeAccount)
        self.store = store; state = store.currentState
        observation = store.observe { [weak self] state in DispatchQueue.main.async { self?.state = state } }
    }
    deinit { observation?.cancel(); store.close() }
    func changeName(_ value: String) { store.changeName(value: value) }
    func changeType(_ value: AssetType) { store.changeType(value: value) }
    func commonOwner() { store.selectCommonOwner() }
    func owner(_ value: Int32) { store.selectOwner(memberOrder: value) }
    func changeMemo(_ value: String) { store.changeMemo(value: value) }
    func changeKind(_ value: AssetKind) { store.changeKind(value: value) }
    func month(_ value: YearMonthKey) { store.changeMonth(year: value.year, month: value.month) }
    func amount(_ value: String) { store.changeAmount(value: value) }
    func duration(_ value: String) { store.changeDuration(value: value) }
    func base(_ value: String) { store.changeBaseAmount(value: value) }
    func increase(_ value: String) { store.changeMonthlyIncrease(value: value) }
    func togglePreview() { store.togglePreview() }
    func save() { store.save() }
    func retry() { store.retry() }
    func restore(_ draft: StoredAssetDraft) {
        guard let type = assetType(draft.type), let kind = assetKind(draft.kind) else { return }
        store.restoreDraft(
            selectedMode: state.selectedMode,
            name: draft.name,
            type: type,
            ownerMemberOrder: draft.owner.map { KotlinInt(value: Int32($0)) },
            memo: draft.memo,
            kind: kind,
            year: Int32(draft.year),
            month: Int32(draft.month),
            amount: draft.amount,
            duration: draft.duration,
            baseAmount: draft.base,
            monthlyIncrease: draft.increase
        )
    }
}

struct AssetEditorView: View {
    @StateObject private var model: AssetEditorViewModel
    @State private var showDiscard = false
    @State private var restored = false
    @SceneStorage("moalog.asset.editor.draft.v1") private var storedDraft = ""
    private let setup: LedgerSetup
    private let route: AssetEditorRoute
    private let onClose: () -> Void
    private let onSaved: (YearMonthKey) -> Void

    init(dependencies: IosDependencies, setup: LedgerSetup, route: AssetEditorRoute, onClose: @escaping () -> Void, onSaved: @escaping (YearMonthKey) -> Void) {
        self.setup = setup; self.route = route; self.onClose = onClose; self.onSaved = onSaved
        _model = StateObject(wrappedValue: AssetEditorViewModel(dependencies: dependencies, route: route))
    }

    var body: some View {
        NavigationStack {
            Group { if model.state.isLoading { ProgressView("불러오는 중이에요") } else { form } }
                .background(MoaLogColor.homeCanvas.ignoresSafeArea())
                .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) { Button(action: requestClose) { Image(systemName: "chevron.left").frame(width: 44, height: 44) }.accessibilityLabel("닫기") }
                    ToolbarItem(placement: .topBarTrailing) { Button("취소", action: requestClose).frame(minHeight: 44) }
                }
                .safeAreaInset(edge: .bottom) { if !model.state.isLoading { saveButton.padding(.horizontal, 16).padding(.vertical, 10).background(MoaLogColor.surface) } }
        }
        .interactiveDismissDisabled(model.state.isDirty || model.state.isSaving)
        .alert("입력 내용을 버릴까요?", isPresented: $showDiscard) { Button("계속 입력", role: .cancel) {}; Button("버리기", role: .destructive) { storedDraft = ""; onClose() } } message: { Text("저장하지 않은 변경 내용이 사라져요.") }
        .alert("처리하지 못했어요", isPresented: errorBinding) { Button("다시 시도", action: model.retry); Button("확인", role: .cancel) {} } message: { Text(model.state.error ?? "잠시 후 다시 시도해 주세요") }
        .onAppear(perform: restoreWhenReady)
        .onChange(of: model.state.isLoading) { _, _ in restoreWhenReady() }
        .onChange(of: signature) { _, _ in persist() }
        .onChange(of: model.state.saved) { _, saved in if saved { storedDraft = ""; onSaved(model.state.savedMonth ?? model.state.month) } }
    }

    private var form: some View {
        ScrollView {
            VStack(spacing: 14) {
                if let error = model.state.error { Label(error, systemImage: "exclamationmark.circle.fill").font(MoaLogFont.semibold(12)).foregroundStyle(MoaLogColor.error).padding(14).frame(maxWidth: .infinity, alignment: .leading).assetEditorCard().accessibilityLabel("오류, \(error)") }
                if model.state.selectedMode == .newasset { metadataCard }
                if model.state.selectedMode == .monthlyvalue || model.state.selectedMode == .newasset { valueCard }
                if model.state.selectedMode == .growthrule { growthCard }
            }.padding(16).padding(.bottom, 20).frame(maxWidth: 480).frame(maxWidth: .infinity)
        }
    }

    private var metadataCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(route.purposeAccount ? "통장 정보" : "자산 정보").font(MoaLogFont.semibold(15))
            TextField(route.purposeAccount ? "통장 이름" : "자산 이름", text: Binding(get: { model.state.name }, set: model.changeName))
                .font(MoaLogFont.medium(15)).padding(.horizontal, 14).frame(minHeight: 52).background(MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 12)).accessibilityLabel(route.purposeAccount ? "통장 이름" : "자산 이름")
            Text("자산 유형").font(MoaLogFont.semibold(12))
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 92), spacing: 8)], spacing: 8) {
                ForEach(assetTypeOptions, id: \.key) { option in
                    let selected = model.state.type == option.value
                    Button { model.changeType(option.value) } label: {
                        Label(option.title, systemImage: assetSymbol(option.value))
                            .font(MoaLogFont.medium(10, relativeTo: .caption2)).frame(maxWidth: .infinity, minHeight: 44)
                            .foregroundStyle(selected ? .white : MoaLogColor.ink)
                            .background(selected ? MoaLogColor.teal : MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 10))
                    }.buttonStyle(.plain).accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
            Text("소유자").font(MoaLogFont.semibold(12))
            HStack(spacing: 8) {
                ownerButton("공동", order: nil)
                ForEach(setup.members.sorted { $0.order < $1.order }, id: \.order) { member in ownerButton(member.displayName, order: member.order) }
            }
            if !route.purposeAccount {
                Text("관리 위치").font(MoaLogFont.semibold(12))
                HStack(spacing: 8) {
                    kindButton("일반 자산", value: .ordinary)
                    kindButton("목적통장", value: .purposeaccount)
                }
            }
            Text("메모 (선택)").font(MoaLogFont.semibold(12))
            TextField("예: 공동 비상금, 장기 투자", text: Binding(get: { model.state.memo }, set: model.changeMemo), axis: .vertical)
                .font(MoaLogFont.regular(13)).lineLimit(2...4).padding(12).frame(minHeight: 52)
                .background(MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 12))
        }.padding(16).assetEditorCard()
    }

    private func ownerButton(_ title: String, order: Int32?) -> some View {
        let selected: Bool
        if let order { selected = model.state.ownerMemberOrder?.intValue == Int(order) }
        else { selected = model.state.ownerMemberOrder == nil }
        return Button { if let order { model.owner(order) } else { model.commonOwner() } } label: { Text(title).font(MoaLogFont.semibold(11)).foregroundStyle(selected ? .white : MoaLogColor.ink).frame(maxWidth: .infinity, minHeight: 44).background(selected ? MoaLogColor.teal : MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 10)) }.buttonStyle(.plain).accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func kindButton(_ title: String, value: AssetKind) -> some View {
        let selected = model.state.kind == value
        return Button { model.changeKind(value) } label: {
            Text(title).font(MoaLogFont.semibold(11)).foregroundStyle(selected ? .white : MoaLogColor.ink)
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(selected ? MoaLogColor.teal : MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 10))
        }.buttonStyle(.plain).accessibilityAddTraits(selected ? .isSelected : [])
    }

    private var valueCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(model.state.selectedMode == .newasset ? "초기 잔액" : "월별 잔액").font(MoaLogFont.semibold(15))
            monthMenu
            amountField("금액", value: Binding(get: { model.state.amount }, set: model.amount))
            Text("0원도 확정 잔액으로 저장됩니다.").font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
        }.padding(16).assetEditorCard()
    }

    private var growthCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("예상 금액 자동 증가 규칙").font(MoaLogFont.semibold(15)); monthMenu
            TextField("반복 기간(개월)", text: Binding(get: { model.state.duration }, set: model.duration)).keyboardType(.numberPad).padding(.horizontal, 14).frame(minHeight: 52).background(MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 12))
            amountField("현재 기준 금액", value: Binding(get: { model.state.baseAmount }, set: model.base))
            amountField("매월 예상 증감액", value: Binding(get: { model.state.monthlyIncrease }, set: model.increase), signed: true)
            Button(action: model.togglePreview) { Label(model.state.isPreviewVisible ? "미리보기 닫기" : "적용 미리보기", systemImage: "tablecells").frame(maxWidth: .infinity, minHeight: 48) }.buttonStyle(.bordered)
            if model.state.isPreviewVisible { previewRows }
            Text("실제 확정 잔액을 입력하면 해당 월의 예상값보다 우선합니다.").font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
        }.padding(16).assetEditorCard()
    }

    private var previewRows: some View {
        VStack(spacing: 8) {
            ForEach(Array(previewValues.enumerated()), id: \.offset) { index, amount in HStack { Text("\(index + 1)개월 후"); Spacer(); Text(amount.map(formatWon) ?? "금액 범위 초과") }.font(MoaLogFont.medium(11)) }
        }.padding(12).background(MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 10)).accessibilityElement(children: .combine)
    }

    private var monthMenu: some View {
        Menu {
            ForEach(availableMonths, id: \.self) { month in Button("\(month.year)년 \(month.month)월") { model.month(month) } }
        } label: { HStack { Image(systemName: "calendar"); Text("\(model.state.month.year)년 \(model.state.month.month)월"); Spacer(); Image(systemName: "chevron.down") }.font(MoaLogFont.medium(13)).padding(.horizontal, 14).frame(minHeight: 52).background(MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 12)) }
        .accessibilityLabel("기준 월")
    }

    private func amountField(_ title: String, value: Binding<String>, signed: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 6) { Text(title).font(MoaLogFont.semibold(11)); HStack { TextField("0", text: value).keyboardType(signed ? .numbersAndPunctuation : .numberPad).font(MoaLogFont.bold(24, relativeTo: .title2)).accessibilityLabel(title).accessibilityIdentifier(signed ? "asset-editor-monthly-change" : "asset-editor-amount"); Text("원").font(MoaLogFont.semibold(14)) }.padding(.horizontal, 14).frame(minHeight: 58).background(MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 12)) }
    }

    private var saveButton: some View { Button(action: model.save) { Group { if model.state.isSaving { ProgressView().tint(.white) } else { Label("저장하기", systemImage: "checkmark") } }.font(MoaLogFont.semibold(15)).frame(maxWidth: .infinity, minHeight: 52) }.buttonStyle(.borderedProminent).tint(MoaLogColor.teal).disabled(!model.state.canSave) }
    private var title: String { if model.state.selectedMode == .growthrule { return "예상 규칙 설정" }; if model.state.selectedMode == .monthlyvalue { return "월별 잔액 입력" }; return route.purposeAccount ? "목적통장 입력" : route.assetId == nil ? "자산 항목 추가" : "자산 정보 수정" }
    private var errorBinding: Binding<Bool> { Binding(get: { model.state.error?.contains("저장하지 못") == true || model.state.error?.contains("불러오지 못") == true }, set: { _ in }) }
    private var signature: String { "\(model.state.name)|\(assetTypeKey(model.state.type))|\(model.state.ownerMemberOrder?.intValue.description ?? "")|\(model.state.memo)|\(assetKindKey(model.state.kind))|\(model.state.month.year)-\(model.state.month.month)|\(model.state.amount)|\(model.state.duration)|\(model.state.baseAmount)|\(model.state.monthlyIncrease)" }
    private var availableMonths: [YearMonthKey] { (-24...24).compactMap { offsetAssetMonth(route.month, $0) } }
    private var previewValues: [Int64?] { let base = Int64(model.state.baseAmount), increase = Int64(model.state.monthlyIncrease); return (1...min(Int(model.state.duration) ?? 3, 6)).map { step in guard let base, let increase else { return nil }; let mul = increase.multipliedReportingOverflow(by: Int64(step)); guard !mul.overflow else { return nil }; let sum = base.addingReportingOverflow(mul.partialValue); return sum.overflow ? nil : sum.partialValue } }

    private func requestClose() { if model.state.isDirty { showDiscard = true } else { storedDraft = ""; onClose() } }
    private func restoreWhenReady() { guard !restored, !model.state.isLoading else { return }; restored = true; guard let draft = StoredAssetDraft.decode(storedDraft), draft.routeId == route.id else { return }; model.restore(draft) }
    private func persist() {
        guard restored else { return }
        guard model.state.isDirty, !model.state.saved else { storedDraft = ""; return }
        storedDraft = StoredAssetDraft(routeId: route.id, name: model.state.name, amount: model.state.amount, duration: model.state.duration, base: model.state.baseAmount, increase: model.state.monthlyIncrease, type: assetTypeKey(model.state.type), memo: model.state.memo, kind: assetKindKey(model.state.kind), owner: model.state.ownerMemberOrder.map { Int($0.intValue) }, year: Int(model.state.month.year), month: Int(model.state.month.month)).encoded
    }
}

private extension View { func assetEditorCard() -> some View { background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 16)).overlay(RoundedRectangle(cornerRadius: 16).stroke(MoaLogColor.cardBorder)) } }
private func offsetAssetMonth(_ month: YearMonthKey, _ delta: Int) -> YearMonthKey? { let index = Int(month.year) * 12 + Int(month.month) - 1 + delta; let year = index / 12, value = index % 12 + 1; guard (1900...9999).contains(year) else { return nil }; return YearMonthKey(year: Int32(year), month: Int32(value)) }

private let assetTypeOptions: [(key: String, title: String, value: AssetType)] = [
    ("cash", "현금", .cash), ("deposit", "예적금", .deposit), ("investment", "투자", .investment),
    ("housing", "주거", .housing), ("other", "기타", .other),
]
private func assetTypeKey(_ value: AssetType) -> String {
    switch value { case .cash: "cash"; case .deposit: "deposit"; case .investment: "investment"; case .housing: "housing"; default: "other" }
}
private func assetType(_ value: String) -> AssetType? {
    switch value { case "cash": .cash; case "deposit": .deposit; case "investment": .investment; case "housing": .housing; case "other": .other; default: nil }
}
private func assetKindKey(_ value: AssetKind) -> String { value == .purposeaccount ? "purpose" : "ordinary" }
private func assetKind(_ value: String) -> AssetKind? { value == "purpose" ? .purposeaccount : value == "ordinary" ? .ordinary : nil }
