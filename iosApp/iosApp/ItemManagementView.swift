import SharedKit
import SwiftUI

private struct ItemEditorRestoration: Codable {
    let selectedType: String
    let stableId: String?
    let name: String
    let classification: String
    let ownerMemberOrder: Int?
    let includePurposeAccount: Bool
    let includeNetSavings: Bool

    var encoded: String {
        (try? JSONEncoder().encode(self)).flatMap { String(data: $0, encoding: .utf8) } ?? ""
    }
    static func decode(_ raw: String) -> Self? {
        guard let data = raw.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(Self.self, from: data)
    }
}

@MainActor
private final class ItemManagementViewModel: ObservableObject {
    @Published private(set) var state: ItemManagementUiState
    private let store: IosItemManagementStore
    private var observation: IosObservation?

    init(dependencies: IosDependencies) {
        store = dependencies.itemManagementStore()
        state = store.currentState
        observation = store.observe { [weak self] value in
            DispatchQueue.main.async { self?.state = value }
        }
    }

    deinit { observation?.cancel(); store.close() }
    func selectType(_ value: ManagedItemType) { store.selectType(type: value) }
    func showArchived(_ value: Bool) { store.showArchived(value: value) }
    func add() { store.add() }
    func edit(_ id: String) { store.edit(stableId: id) }
    func changeName(_ value: String) { store.changeName(value: value) }
    func changeClassification(_ value: String) { store.changeClassification(value: value) }
    func selectCommonOwner() { store.selectCommonOwner() }
    func selectOwner(_ order: Int32) { store.selectOwner(memberOrder: order) }
    func changePurpose(_ value: Bool) { store.changePurpose(value: value) }
    func changeNetSavings(_ value: Bool) { store.changeNetSavings(value: value) }
    func save() { store.save() }
    func dismissEditor() { store.dismissEditor() }
    func requestArchive(_ id: String) { store.requestArchive(stableId: id) }
    func dismissArchive() { store.dismissArchive() }
    func confirmArchive() { store.confirmArchive() }
    func restore(_ id: String) { store.restore(stableId: id) }
    func moveUp(_ id: String) { store.moveUp(stableId: id) }
    func moveDown(_ id: String) { store.moveDown(stableId: id) }
    func retry() { store.retry() }
    func clearAnnouncement() { store.clearResultAnnouncement() }
    func restoreDraft(_ value: ItemEditorRestoration) {
        guard let type = ManagedItemType.fromStored(value.selectedType) else { return }
        selectType(type)
        store.restoreDraft(
            stableId: value.stableId,
            name: value.name,
            classification: value.classification,
            ownerMemberOrder: value.ownerMemberOrder.map { KotlinInt(value: Int32($0)) },
            includePurposeAccount: value.includePurposeAccount,
            includeNetSavings: value.includeNetSavings
        )
    }
}

struct ItemManagementView: View {
    @StateObject private var model: ItemManagementViewModel
    @SceneStorage("moalog.items.type.v1") private var storedType = ManagedItemType.variableexpense.name
    @SceneStorage("moalog.items.archived.v1") private var storedArchived = false
    @SceneStorage("moalog.items.editor.v1") private var storedEditor = ""
    @State private var didRestore = false
    @State private var reorderMode = false
    @State private var showEditorDiscard = false

    let setup: LedgerSetup
    let onClose: () -> Void

    init(dependencies: IosDependencies, setup: LedgerSetup, onClose: @escaping () -> Void) {
        self.setup = setup
        self.onClose = onClose
        _model = StateObject(wrappedValue: ItemManagementViewModel(dependencies: dependencies))
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                typePicker
                archivePicker
                content
            }
            .background(MoaLogColor.homeCanvas.ignoresSafeArea())
            .navigationTitle("항목 관리")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(action: onClose) { Image(systemName: "chevron.left").frame(width: 44, height: 44) }
                        .accessibilityLabel("더보기로")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(reorderMode ? "완료" : "순서 편집") { withAnimation { reorderMode.toggle() } }
                        .font(MoaLogFont.semibold(12, relativeTo: .caption)).frame(minHeight: 44)
                        .disabled(model.state.showArchived || model.state.items.count < 2)
                }
            }
            .safeAreaInset(edge: .bottom) {
                if !model.state.showArchived {
                    Button(action: model.add) {
                        Label("새 \(model.state.selectedType.addTitle) 추가", systemImage: "plus")
                            .font(MoaLogFont.bold(14)).foregroundStyle(.white).frame(maxWidth: .infinity, minHeight: 52)
                            .background(MoaLogColor.teal, in: RoundedRectangle(cornerRadius: 13))
                    }
                    .buttonStyle(.plain).disabled(model.state.isLoading || model.state.isMutating)
                    .padding(.horizontal, 16).padding(.vertical, 10).background(MoaLogColor.surface)
                }
            }
        }
        .sheet(isPresented: editorPresented) {
            ItemEditorSheet(model: model, setup: setup, onRequestClose: { showEditorDiscard = true })
                .interactiveDismissDisabled(model.state.editor != nil)
        }
        .alert("수정을 그만둘까요?", isPresented: $showEditorDiscard) {
            Button("계속 수정", role: .cancel) {}
            Button("그만두기", role: .destructive) { storedEditor = ""; model.dismissEditor() }
        } message: { Text("저장하지 않은 항목 정보가 사라져요.") }
        .alert("이 항목을 보관할까요?", isPresented: archivePresented) {
            Button("취소", role: .cancel, action: model.dismissArchive)
            Button("보관", role: .destructive, action: model.confirmArchive)
        } message: { Text("기존 기록과 합계는 유지돼요.") }
        .onAppear(perform: restoreIfNeeded)
        .onChange(of: model.state.selectedType) { _, value in storedType = value.name }
        .onChange(of: model.state.showArchived) { _, value in storedArchived = value; if value { reorderMode = false } }
        .onChange(of: editorFingerprint) { _, _ in persistEditor() }
        .onChange(of: model.state.resultAnnouncement) { _, value in
            guard let value else { return }
            storedEditor = ""
            UIAccessibility.post(notification: .announcement, argument: value)
            model.clearAnnouncement()
        }
    }

    private var typePicker: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(ManagedItemType.displayed, id: \.name) { type in
                    Button(type.title) { model.selectType(type) }
                        .font(MoaLogFont.semibold(11, relativeTo: .caption))
                        .foregroundStyle(model.state.selectedType == type ? .white : MoaLogColor.mutedInk)
                        .padding(.horizontal, 12).frame(minHeight: 44)
                        .background(model.state.selectedType == type ? MoaLogColor.teal : MoaLogColor.surface, in: Capsule())
                        .accessibilityAddTraits(model.state.selectedType == type ? .isSelected : [])
                }
            }.padding(.horizontal, 16).padding(.top, 10)
        }
    }

    private var archivePicker: some View {
        HStack(spacing: 4) {
            archiveButton("사용 중", value: false)
            archiveButton("보관됨", value: true)
        }
        .padding(4).background(MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 11))
        .padding(.horizontal, 16).padding(.vertical, 10)
    }

    private func archiveButton(_ title: String, value: Bool) -> some View {
        Button(title) { model.showArchived(value) }
            .font(MoaLogFont.semibold(12, relativeTo: .caption)).foregroundStyle(model.state.showArchived == value ? MoaLogColor.teal : MoaLogColor.mutedInk)
            .frame(maxWidth: .infinity, minHeight: 44)
            .background(model.state.showArchived == value ? MoaLogColor.surface : .clear, in: RoundedRectangle(cornerRadius: 9))
            .accessibilityAddTraits(model.state.showArchived == value ? .isSelected : [])
    }

    @ViewBuilder private var content: some View {
        if model.state.isLoading {
            ProgressView("항목을 불러오는 중이에요").frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let error = model.state.error, model.state.items.isEmpty {
            ContentUnavailableView {
                Label("항목을 불러오지 못했어요", systemImage: "exclamationmark.circle")
            } description: { Text(error) } actions: { Button("다시 시도", action: model.retry).frame(minHeight: 44) }
        } else if model.state.items.isEmpty {
            ContentUnavailableView {
                Label(model.state.showArchived ? "보관된 항목이 없어요" : "사용 중인 항목이 없어요", systemImage: "tray")
            } description: { Text(model.state.showArchived ? "보관한 항목이 여기에 표시돼요." : "아래 버튼으로 첫 항목을 추가해 보세요.") }
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    Label("항목을 보관해도 기존 기록과 통계 합계는 유지됩니다.", systemImage: "info.circle")
                        .font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
                        .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                        .background(MoaLogColor.secondaryContainer, in: RoundedRectangle(cornerRadius: 10))
                    ForEach(Array(model.state.items.enumerated()), id: \.element.stableId) { index, item in
                        itemRow(item, index: index)
                    }
                    if let error = model.state.error {
                        HStack {
                            Label(error, systemImage: "exclamationmark.circle.fill")
                                .font(MoaLogFont.medium(11, relativeTo: .caption)).foregroundStyle(MoaLogColor.error)
                            Spacer()
                            Button("다시 시도", action: model.retry)
                                .font(MoaLogFont.semibold(11, relativeTo: .caption)).frame(minHeight: 44)
                        }
                        .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                    }
                }.padding(.horizontal, 16).padding(.bottom, 24).frame(maxWidth: 480).frame(maxWidth: .infinity)
            }
        }
    }

    private func itemRow(_ item: ManagedItem, index: Int) -> some View {
        HStack(spacing: 11) {
            Image(systemName: item.type.symbol).foregroundStyle(MoaLogColor.teal).frame(width: 38, height: 38)
                .background(MoaLogColor.secondaryContainer, in: Circle()).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(item.name).font(MoaLogFont.semibold(13))
                Text(item.archived ? "보관됨" : item.detail(setup: setup))
                    .font(MoaLogFont.regular(9, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk).lineLimit(2)
            }
            Spacer()
            if item.archived {
                Button("복원") { model.restore(item.stableId) }.font(MoaLogFont.semibold(11, relativeTo: .caption)).frame(minHeight: 44)
            } else if reorderMode {
                VStack(spacing: 0) {
                    Button { model.moveUp(item.stableId) } label: { Image(systemName: "chevron.up").frame(width: 44, height: 28) }
                        .disabled(index == 0 || model.state.isMutating).accessibilityLabel("\(item.name) 위로 이동")
                    Button { model.moveDown(item.stableId) } label: { Image(systemName: "chevron.down").frame(width: 44, height: 28) }
                        .disabled(index == model.state.items.count - 1 || model.state.isMutating).accessibilityLabel("\(item.name) 아래로 이동")
                }
            } else {
                Menu {
                    Button("수정") { model.edit(item.stableId) }
                    Button("보관", role: .destructive) { model.requestArchive(item.stableId) }
                } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) }
                .accessibilityLabel("\(item.name) 메뉴")
            }
        }
        .padding(.horizontal, 12).frame(minHeight: 70)
        .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 13))
        .overlay(RoundedRectangle(cornerRadius: 13).stroke(MoaLogColor.cardBorder))
        .accessibilityElement(children: .contain)
        .accessibilityAction(named: "위로 이동") { model.moveUp(item.stableId) }
        .accessibilityAction(named: "아래로 이동") { model.moveDown(item.stableId) }
    }

    private var editorPresented: Binding<Bool> { Binding(get: { model.state.editor != nil }, set: { if !$0 && model.state.editor != nil { showEditorDiscard = true } }) }
    private var archivePresented: Binding<Bool> { Binding(get: { model.state.pendingArchiveId != nil }, set: { if !$0 { model.dismissArchive() } }) }
    private var editorFingerprint: String {
        guard let value = model.state.editor else { return "" }
        return "\(value.stableId ?? "")|\(value.name)|\(value.classification)|\(value.ownerMemberOrder?.int32Value ?? -1)|\(value.includePurposeAccount)|\(value.includeNetSavings)"
    }

    private func restoreIfNeeded() {
        guard !didRestore else { return }; didRestore = true
        if let type = ManagedItemType.fromStored(storedType) { model.selectType(type) }
        model.showArchived(storedArchived)
        if let editor = ItemEditorRestoration.decode(storedEditor) { model.restoreDraft(editor) }
    }

    private func persistEditor() {
        guard didRestore else { return }
        guard let value = model.state.editor else { storedEditor = ""; return }
        storedEditor = ItemEditorRestoration(
            selectedType: model.state.selectedType.name,
            stableId: value.stableId,
            name: value.name,
            classification: value.classification,
            ownerMemberOrder: value.ownerMemberOrder.map { Int($0.int32Value) },
            includePurposeAccount: value.includePurposeAccount,
            includeNetSavings: value.includeNetSavings
        ).encoded
    }
}

private struct ItemEditorSheet: View {
    @ObservedObject var model: ItemManagementViewModel
    let setup: LedgerSetup
    let onRequestClose: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                if let draft = model.state.editor {
                    VStack(alignment: .leading, spacing: 18) {
                        editorField("이름", value: Binding(get: { draft.name }, set: model.changeName))

                        if model.state.selectedType != .variableexpense {
                            VStack(alignment: .leading, spacing: 7) {
                                Text("분류").font(MoaLogFont.semibold(12, relativeTo: .caption))
                                if model.state.selectedType == .savings {
                                    Menu {
                                        ForEach(model.state.savingsClassifications, id: \.self) { value in
                                            Button(value) { model.changeClassification(value) }
                                        }
                                    } label: {
                                        HStack { Text(draft.classification.isEmpty ? "분류 선택" : draft.classification); Spacer(); Image(systemName: "chevron.down") }
                                            .font(MoaLogFont.medium(14)).padding(.horizontal, 12).frame(minHeight: 48)
                                            .background(MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 10))
                                    }
                                } else {
                                    editorField("분류", value: Binding(get: { draft.classification }, set: model.changeClassification), hideLabel: true)
                                }
                            }
                            ownerPicker(draft)
                        }

                        if model.state.selectedType == .savings {
                            Toggle("목적통장에 포함", isOn: Binding(get: { draft.includePurposeAccount }, set: model.changePurpose))
                                .font(MoaLogFont.medium(13)).frame(minHeight: 48)
                            Toggle("순저축에 포함", isOn: Binding(get: { draft.includeNetSavings }, set: model.changeNetSavings))
                                .font(MoaLogFont.medium(13)).frame(minHeight: 48)
                        }

                        if let error = model.state.editorError ?? model.state.error {
                            Label(error, systemImage: "exclamationmark.circle.fill")
                                .font(MoaLogFont.medium(11, relativeTo: .caption)).foregroundStyle(MoaLogColor.error)
                                .accessibilityLabel("입력 오류, \(error)")
                        }
                    }
                    .padding(20)
                }
            }
            .background(MoaLogColor.homeCanvas.ignoresSafeArea())
            .navigationTitle(model.state.editor?.stableId == nil ? "새 항목 추가" : "항목 정보 수정")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button(action: onRequestClose) { Image(systemName: "xmark").frame(width: 44, height: 44) }.accessibilityLabel("닫기") }
            }
            .safeAreaInset(edge: .bottom) {
                Button(action: model.save) {
                    HStack { if model.state.isMutating { ProgressView().tint(.white) }; Text(model.state.isMutating ? "저장 중" : "저장하기") }
                        .font(MoaLogFont.bold(14)).foregroundStyle(.white).frame(maxWidth: .infinity, minHeight: 52)
                        .background(canSave ? MoaLogColor.teal : MoaLogColor.mutedInk.opacity(0.35), in: RoundedRectangle(cornerRadius: 13))
                }
                .buttonStyle(.plain).disabled(!canSave).padding(.horizontal, 16).padding(.vertical, 10).background(MoaLogColor.surface)
            }
        }
    }

    private func editorField(_ title: String, value: Binding<String>, hideLabel: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            if !hideLabel { Text(title).font(MoaLogFont.semibold(12, relativeTo: .caption)) }
            TextField(title, text: value).font(MoaLogFont.medium(14)).padding(.horizontal, 12).frame(minHeight: 48)
                .background(MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 10)).accessibilityLabel(title)
        }
    }

    private func ownerPicker(_ draft: ItemManagementDraft) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("담당").font(MoaLogFont.semibold(12, relativeTo: .caption))
            HStack(spacing: 6) {
                ownerButton("공동", order: nil, selected: draft.ownerMemberOrder == nil)
                ForEach(setup.members.sorted { $0.order < $1.order }, id: \.order) { member in
                    ownerButton(member.displayName, order: member.order, selected: draft.ownerMemberOrder?.int32Value == member.order)
                }
            }
        }
    }

    private func ownerButton(_ title: String, order: Int32?, selected: Bool) -> some View {
        Button {
            if let order { model.selectOwner(order) } else { model.selectCommonOwner() }
        } label: {
            Text(title).font(MoaLogFont.semibold(11, relativeTo: .caption)).foregroundStyle(selected ? MoaLogColor.teal : MoaLogColor.mutedInk)
                .frame(maxWidth: .infinity, minHeight: 44).background(selected ? MoaLogColor.secondaryContainer : MoaLogColor.surfaceLow, in: Capsule())
        }.buttonStyle(.plain).accessibilityAddTraits(selected ? .isSelected : [])
    }

    private var canSave: Bool {
        guard let draft = model.state.editor, !model.state.isMutating else { return false }
        if draft.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return false }
        return model.state.selectedType == .variableexpense || !draft.classification.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}

private extension ManagedItemType {
    static let displayed: [ManagedItemType] = [.income, .fixedexpense, .variableexpense, .savings]
    static func fromStored(_ raw: String) -> ManagedItemType? { displayed.first { $0.name == raw } }
    var title: String { switch self { case .income: "수입 항목"; case .fixedexpense: "고정지출"; case .variableexpense: "변동지출"; case .savings: "저축 항목"; default: "항목" } }
    var addTitle: String { switch self { case .income: "수입 항목"; case .fixedexpense: "고정지출 항목"; case .variableexpense: "카테고리"; case .savings: "저축 항목"; default: "항목" } }
    var symbol: String { switch self { case .income: "banknote"; case .fixedexpense: "house"; case .variableexpense: "cart"; case .savings: "chart.line.uptrend.xyaxis"; default: "square.grid.2x2" } }
}

private extension ManagedItem {
    func detail(setup: LedgerSetup) -> String {
        var values = [classification]
        if let order = ownerMemberOrder?.int32Value,
           let name = setup.members.first(where: { $0.order == order })?.displayName { values.append(name) }
        if includePurposeAccount { values.append("목적통장") }
        if includeNetSavings { values.append("순저축") }
        return values.joined(separator: " · ")
    }
}
