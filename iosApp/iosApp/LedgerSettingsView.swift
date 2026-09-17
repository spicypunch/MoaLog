import SharedKit
import SwiftUI

private struct LedgerSettingsRestoration: Codable {
    let ledgerName: String
    let firstMemberName: String
    let secondMemberName: String

    var encoded: String {
        (try? JSONEncoder().encode(self)).flatMap { String(data: $0, encoding: .utf8) } ?? ""
    }

    static func decode(_ value: String) -> Self? {
        guard let data = value.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(Self.self, from: data)
    }
}

@MainActor
private final class LedgerSettingsViewModel: ObservableObject {
    @Published private(set) var state: LedgerSettingsUiState
    private let store: IosLedgerSettingsStore
    private var observation: IosObservation?

    init(dependencies: IosDependencies) {
        store = dependencies.ledgerSettingsStore()
        state = store.currentState
        observation = store.observe { [weak self] state in
            DispatchQueue.main.async { self?.state = state }
        }
    }

    deinit { observation?.cancel(); store.close() }
    func changeLedgerName(_ value: String) { store.changeLedgerName(value: value) }
    func changeFirstName(_ value: String) { store.changeFirstMemberName(value: value) }
    func changeSecondName(_ value: String) { store.changeSecondMemberName(value: value) }
    func restore(_ value: LedgerSettingsRestoration) {
        store.restoreDraft(
            ledgerName: value.ledgerName,
            firstMemberName: value.firstMemberName,
            secondMemberName: value.secondMemberName
        )
    }
    func save() { store.save() }
    func retry() {
        let draft = state.isDirty ? LedgerSettingsRestoration(
            ledgerName: state.ledgerName,
            firstMemberName: state.firstMemberName,
            secondMemberName: state.secondMemberName
        ) : nil
        store.retry()
        if let draft { restore(draft) }
    }
}

struct LedgerSettingsView: View {
    @StateObject private var model: LedgerSettingsViewModel
    @SceneStorage("moalog.ledger.settings.draft.v1") private var storedDraft = ""
    @State private var didRestore = false
    @State private var showDiscard = false
    @FocusState private var focusedField: Field?

    let setup: LedgerSetup
    let onClose: () -> Void

    private enum Field: Hashable { case ledger, first, second }

    init(dependencies: IosDependencies, setup: LedgerSetup, onClose: @escaping () -> Void) {
        self.setup = setup
        self.onClose = onClose
        _model = StateObject(wrappedValue: LedgerSettingsViewModel(dependencies: dependencies))
    }

    var body: some View {
        NavigationStack {
            Group {
                if model.state.isLoading { ProgressView("가계부 정보를 불러오는 중이에요") }
                else { content }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(MoaLogColor.homeCanvas.ignoresSafeArea())
            .navigationTitle("가계부 기본 설정")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(action: requestClose) { Image(systemName: "chevron.left").frame(width: 44, height: 44) }
                        .accessibilityLabel("더보기로")
                }
            }
            .safeAreaInset(edge: .bottom) { if !model.state.isLoading { saveButton } }
        }
        .interactiveDismissDisabled(model.state.isDirty || model.state.isSaving)
        .alert("변경 내용을 버릴까요?", isPresented: $showDiscard) {
            Button("계속 수정", role: .cancel) {}
            Button("버리기", role: .destructive) { storedDraft = ""; onClose() }
        } message: { Text("저장하지 않은 가계부 이름과 구성원 이름이 사라져요.") }
        .onAppear(perform: restoreIfNeeded)
        .onChange(of: model.state.isLoading) { _, _ in restoreIfNeeded() }
        .onChange(of: draftFingerprint) { _, _ in persistDraft() }
        .onChange(of: model.state.saved) { _, saved in
            guard saved else { return }
            storedDraft = ""
            UIAccessibility.post(notification: .announcement, argument: "가계부 기본 정보를 저장했어요")
            onClose()
        }
    }

    private var content: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("우리 가계부 정보").font(MoaLogFont.bold(22, relativeTo: .title2))
                    Text("가계부 이름과 두 사람의 표시 이름을 바꿀 수 있어요.")
                        .font(MoaLogFont.regular(12, relativeTo: .caption)).foregroundStyle(MoaLogColor.mutedInk)
                }

                VStack(spacing: 18) {
                    settingsField("가계부 이름", placeholder: setup.ledgerName, text: Binding(
                        get: { model.state.ledgerName }, set: model.changeLedgerName
                    ), field: .ledger)
                    Divider()
                    settingsField("첫 번째 구성원", placeholder: "첫 번째 이름", text: Binding(
                        get: { model.state.firstMemberName }, set: model.changeFirstName
                    ), field: .first)
                    settingsField("두 번째 구성원", placeholder: "두 번째 이름", text: Binding(
                        get: { model.state.secondMemberName }, set: model.changeSecondName
                    ), field: .second)
                }
                .padding(16).settingsCard()

                HStack(spacing: 10) {
                    Image(systemName: "calendar.badge.clock").foregroundStyle(MoaLogColor.teal)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("기준 연도").font(MoaLogFont.semibold(12, relativeTo: .caption))
                        Text("\(String(baseYear))년 · 처음 설정한 기준을 유지해요")
                            .font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
                    }
                    Spacer()
                }
                .padding(14).settingsCard().accessibilityElement(children: .combine)

                if let error = model.state.error {
                    VStack(alignment: .leading, spacing: 8) {
                        Label(error, systemImage: "exclamationmark.circle.fill")
                            .font(MoaLogFont.medium(11, relativeTo: .caption)).foregroundStyle(MoaLogColor.error)
                        Button("다시 시도", action: model.retry)
                            .font(MoaLogFont.semibold(11, relativeTo: .caption)).frame(minHeight: 44)
                    }
                    .padding(14).frame(maxWidth: .infinity, alignment: .leading)
                    .background(MoaLogColor.error.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
                    .accessibilityElement(children: .contain)
                }
            }
            .padding(16).padding(.bottom, 28).frame(maxWidth: 480).frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
    }

    private func settingsField(_ title: String, placeholder: String, text: Binding<String>, field: Field) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            Text(title).font(MoaLogFont.semibold(12, relativeTo: .caption))
            TextField(placeholder, text: text)
                .font(MoaLogFont.medium(15)).focused($focusedField, equals: field)
                .padding(.horizontal, 12).frame(minHeight: 48)
                .background(MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(focusedField == field ? MoaLogColor.teal : MoaLogColor.cardBorder))
                .accessibilityLabel(title)
        }
    }

    private var saveButton: some View {
        Button(action: model.save) {
            HStack(spacing: 8) {
                if model.state.isSaving { ProgressView().tint(.white) }
                Text(model.state.isSaving ? "저장 중" : "변경 내용 저장")
            }
            .font(MoaLogFont.bold(14)).foregroundStyle(.white).frame(maxWidth: .infinity, minHeight: 52)
            .background(model.state.canSave ? MoaLogColor.teal : MoaLogColor.mutedInk.opacity(0.35), in: RoundedRectangle(cornerRadius: 13))
        }
        .buttonStyle(.plain).disabled(!model.state.canSave)
        .padding(.horizontal, 16).padding(.vertical, 10).background(MoaLogColor.surface)
    }

    private var baseYear: Int32 { model.state.baseYear?.int32Value ?? setup.baseYear }
    private var draftFingerprint: String {
        "\(model.state.ledgerName)|\(model.state.firstMemberName)|\(model.state.secondMemberName)|\(model.state.isDirty)"
    }

    private func requestClose() {
        focusedField = nil
        if model.state.isDirty { showDiscard = true } else { storedDraft = ""; onClose() }
    }

    private func restoreIfNeeded() {
        guard !didRestore, !model.state.isLoading else { return }
        didRestore = true
        guard let draft = LedgerSettingsRestoration.decode(storedDraft) else { return }
        model.restore(draft)
    }

    private func persistDraft() {
        guard didRestore else { return }
        guard model.state.isDirty, !model.state.saved else { storedDraft = ""; return }
        storedDraft = LedgerSettingsRestoration(
            ledgerName: model.state.ledgerName,
            firstMemberName: model.state.firstMemberName,
            secondMemberName: model.state.secondMemberName
        ).encoded
    }
}

private extension View {
    func settingsCard() -> some View {
        background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(MoaLogColor.cardBorder))
    }
}
