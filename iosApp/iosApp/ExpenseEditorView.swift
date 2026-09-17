import SharedKit
import SwiftUI

enum ExpenseEditorOrigin: String, Codable {
    case home
    case records
}

struct PersistedExpenseEditorRoute: Codable {
    let recordId: Int64?
    let year: Int
    let month: Int
    let origin: ExpenseEditorOrigin

    init(_ route: ExpenseEditorRoute) {
        recordId = route.recordId
        year = Int(route.initialMonth.year)
        month = Int(route.initialMonth.month)
        origin = route.origin
    }

    var route: ExpenseEditorRoute? {
        guard (1900...9999).contains(year), (1...12).contains(month) else { return nil }
        return ExpenseEditorRoute(recordId: recordId, initialMonth: YearMonthKey(year: Int32(year), month: Int32(month)), origin: origin)
    }

    var encoded: String { (try? JSONEncoder().encode(self)).flatMap { String(data: $0, encoding: .utf8) } ?? "" }
    static func decode(_ raw: String) -> Self? {
        guard let data = raw.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(Self.self, from: data)
    }
}

struct ExpenseEditorRoute: Identifiable {
    let recordId: Int64?
    let initialMonth: YearMonthKey
    let origin: ExpenseEditorOrigin
    var id: String { "\(recordId.map(String.init) ?? "new")-\(initialMonth.year)-\(initialMonth.month)-\(origin == .home ? "home" : "records")" }
}

private struct ExpenseEditorDraftRestoration: Codable {
    let routeId: String
    let amount: String
    let categoryId: String?
    let attributionMonth: String
    let actualDate: String
    let detail: String
    let overspent: Bool

    var encoded: String { (try? JSONEncoder().encode(self)).flatMap { String(data: $0, encoding: .utf8) } ?? "" }
    static func decode(_ raw: String) -> Self? {
        guard let data = raw.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(Self.self, from: data)
    }
}

@MainActor
private final class ExpenseEditorViewModel: ObservableObject {
    @Published private(set) var state: ExpenseEditorUiState
    @Published var quickAddError: String?
    private let store: IosExpenseEditorStore
    private var observation: IosObservation?

    init(dependencies: IosDependencies, route: ExpenseEditorRoute) {
        let store = dependencies.expenseEditorStore(recordId: route.recordId.map { KotlinLong(value: $0) }, initialMonth: route.initialMonth)
        self.store = store
        state = store.currentState
        observation = store.observe { [weak self] state in
            DispatchQueue.main.async { self?.state = state }
        }
    }

    deinit { observation?.cancel(); store.close() }
    func changeAmount(_ value: String) { quickAddError = nil; store.changeAmount(value: value) }
    func quickAdd(_ won: Int64) {
        let normalized = state.amount.replacingOccurrences(of: ",", with: "").trimmingCharacters(in: .whitespaces)
        guard normalized.allSatisfy(\.isNumber), let current = Int64(normalized.isEmpty ? "0" : normalized) else {
            quickAddError = "금액이 너무 커서 더할 수 없어요"
            return
        }
        let result = current.addingReportingOverflow(won)
        guard !result.overflow else { quickAddError = "금액이 너무 커서 더할 수 없어요"; return }
        store.changeAmount(value: String(result.partialValue))
    }
    func selectCategory(_ id: String) { store.selectCategory(categoryId: id) }
    func changeMonth(_ value: String) { store.changeAttributionMonth(value: value) }
    func changeActualDate(_ value: String) { store.changeActualDate(value: value) }
    func useSuggestedDate() { store.useSuggestedActualDate() }
    func changeDetail(_ value: String) { store.changeDetail(value: value) }
    func changeOverspent(_ value: Bool) { store.changeOverspent(value: value) }
    func save() { store.save() }
    func retry() { store.retryPersistence() }
    func dismissError() { store.dismissPersistenceError() }
    func reset() { store.resetForm() }
    func requestDelete() { store.requestDelete() }
    func dismissDelete() { store.dismissDelete() }
    func confirmDelete() { store.confirmDelete() }
    func requestBack() { store.requestBack() }
    func dismissDiscard() { store.dismissDiscard() }
    func confirmDiscard() { store.confirmDiscard() }
    func restore(_ draft: ExpenseEditorDraftRestoration) {
        store.restoreDraft(
            amount: draft.amount, categoryId: draft.categoryId,
            attributionMonth: draft.attributionMonth, actualDate: draft.actualDate,
            detail: draft.detail, overspent: draft.overspent
        )
    }
}

struct ExpenseEditorView: View {
    @StateObject private var model: ExpenseEditorViewModel
    @State private var didFinish = false
    @State private var restoredDraft = false
    @SceneStorage("moalog.expense.editor.draft.v1") private var persistedDraft = ""

    let route: ExpenseEditorRoute
    let onCancel: () -> Void
    let onCompleted: (YearMonthKey) -> Void

    init(
        dependencies: IosDependencies,
        route: ExpenseEditorRoute,
        onCancel: @escaping () -> Void,
        onCompleted: @escaping (YearMonthKey) -> Void
    ) {
        self.route = route; self.onCancel = onCancel; self.onCompleted = onCompleted
        _model = StateObject(wrappedValue: ExpenseEditorViewModel(dependencies: dependencies, route: route))
    }

    var body: some View {
        NavigationStack {
            Group {
                if model.state.isLoading { loadingContent }
                else { form }
            }
            .background(MoaLogColor.homeCanvas.ignoresSafeArea())
            .navigationTitle(model.state.isEditing ? "지출 수정" : "지출 입력")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(action: model.requestBack) { Image(systemName: "chevron.left").frame(width: 44, height: 44) }
                        .accessibilityLabel("닫기")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("취소", action: model.requestBack).font(MoaLogFont.medium(13, relativeTo: .caption)).frame(minHeight: 44)
                }
            }
            .safeAreaInset(edge: .bottom) {
                if !model.state.isLoading {
                    saveButton
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(MoaLogColor.surface)
                }
            }
        }
        .interactiveDismissDisabled(model.state.hasUnsavedChanges || model.state.isSaving)
        .alert("이 지출을 삭제할까요?", isPresented: deleteBinding) {
            Button("취소", role: .cancel, action: model.dismissDelete)
            Button("삭제", role: .destructive, action: model.confirmDelete)
        } message: { Text("삭제하면 월별 합계에도 반영돼요.") }
        .alert("입력 내용을 버릴까요?", isPresented: discardBinding) {
            Button("계속 입력", role: .cancel, action: model.dismissDiscard)
            Button("버리기", role: .destructive, action: model.confirmDiscard)
        } message: { Text("저장하지 않은 변경 내용이 사라져요.") }
        .alert("처리하지 못했어요", isPresented: persistenceErrorBinding) {
            if model.state.failedOperation != nil { Button("다시 시도", action: model.retry) }
            Button("확인", role: .cancel, action: model.dismissError)
        } message: { Text(model.state.persistenceError ?? "잠시 후 다시 시도해 주세요.") }
        .onAppear(perform: restoreDraftIfNeeded)
        .onChange(of: model.state.isLoading) { _, _ in restoreDraftIfNeeded() }
        .onChange(of: draftSignature) { _, _ in persistDraftIfNeeded() }
        .onChange(of: model.state.completed) { _, completed in if completed { complete() } }
        .onChange(of: model.state.exitRequested) { _, exit in if exit { cancel() } }
    }

    private var form: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(spacing: 14) {
                    if model.state.errors.hasAny { validationBanner.id("validation") }
                    amountCard
                    categoryCard
                    dateCard
                    detailCard
                    overspentCard
                    if model.state.isEditing { deleteButton }
                    Button(action: model.reset) {
                        Label("입력 초기화", systemImage: "arrow.counterclockwise")
                            .font(MoaLogFont.medium(11, relativeTo: .caption2)).frame(minHeight: 44)
                    }.buttonStyle(.plain)
                }
                .padding(16)
                .frame(maxWidth: 520)
                .frame(maxWidth: .infinity)
            }
            .onChange(of: model.state.errors.hasAny) { _, invalid in
                if invalid { withAnimation { proxy.scrollTo("validation", anchor: .top) }; UIAccessibility.post(notification: .announcement, argument: "입력 내용을 확인해 주세요") }
            }
        }
    }

    private var validationBanner: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "exclamationmark.circle.fill").foregroundStyle(MoaLogColor.error).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text("입력 내용을 확인해 주세요").font(MoaLogFont.bold(13, relativeTo: .caption))
                Text("\(model.state.errors.count)개 항목을 수정하면 저장할 수 있어요.")
                    .font(MoaLogFont.regular(11, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
            }
            Spacer()
        }
        .padding(12).background(MoaLogColor.error.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(MoaLogColor.error.opacity(0.35)))
        .accessibilityElement(children: .combine)
    }

    private var amountCard: some View {
        editorCard {
            HStack {
                requiredLabel("금액")
                Spacer()
                Text("변동지출로 반영").font(MoaLogFont.medium(9, relativeTo: .caption2)).foregroundStyle(MoaLogColor.teal)
                    .padding(.horizontal, 8).padding(.vertical, 4).background(MoaLogColor.sage, in: Capsule())
            }
            HStack(alignment: .firstTextBaseline, spacing: 5) {
                TextField("0", text: Binding(get: { model.state.amount }, set: model.changeAmount))
                    .font(MoaLogFont.bold(28, relativeTo: .title)).keyboardType(.numberPad).accessibilityLabel("금액")
                Text("원").font(MoaLogFont.semibold(14)).foregroundStyle(MoaLogColor.mutedInk)
            }
            .frame(minHeight: 48)
            if let error = model.state.errors.amount ?? model.quickAddError { fieldError(error) }
            HStack(spacing: 6) {
                quickButton("+1만", value: 10_000); quickButton("+5만", value: 50_000)
                quickButton("+10만", value: 100_000); quickButton("+50만", value: 500_000)
            }
        }
    }

    private var categoryCard: some View {
        editorCard {
            HStack { requiredLabel("카테고리"); Spacer(); Text("필수 선택").font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk) }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 8)], spacing: 8) {
                ForEach(model.state.categories, id: \.id) { category in
                    let selected = model.state.categoryId == category.id
                    Button { model.selectCategory(category.id) } label: {
                        Label(category.name, systemImage: expenseCategorySymbol(category.id))
                            .font(MoaLogFont.medium(11, relativeTo: .caption2)).lineLimit(1).minimumScaleFactor(0.75)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .background(selected ? MoaLogColor.teal : MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 10))
                            .foregroundStyle(selected ? .white : MoaLogColor.ink)
                            .overlay(RoundedRectangle(cornerRadius: 10).stroke(selected ? MoaLogColor.teal : MoaLogColor.cardBorder))
                    }.buttonStyle(.plain).accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
            if let error = model.state.errors.category { fieldError(error) }
        }
    }

    private var dateCard: some View {
        editorCard {
            requiredLabel("귀속월")
            Menu {
                ForEach(availableMonths, id: \.description) { month in
                    Button(monthDisplay(month)) { model.changeMonth(month.description) }
                }
            } label: {
                HStack { Image(systemName: "calendar"); Text(monthDisplayString(model.state.attributionMonth)); Spacer(); Image(systemName: "chevron.down") }
                    .font(MoaLogFont.medium(13, relativeTo: .caption)).foregroundStyle(MoaLogColor.ink)
                    .padding(.horizontal, 12).frame(minHeight: 48).background(MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 10))
            }
            if let error = model.state.errors.attributionMonth { fieldError(error) }
            Toggle("실제 지출일 입력", isOn: actualDateEnabled)
                .font(MoaLogFont.semibold(12, relativeTo: .caption)).tint(MoaLogColor.teal).frame(minHeight: 44)
            if !model.state.actualDate.isEmpty {
                DatePicker("실제 지출일", selection: actualDateBinding, displayedComponents: .date)
                    .datePickerStyle(.compact).font(MoaLogFont.medium(12, relativeTo: .caption)).frame(minHeight: 44)
            }
            if model.state.actualDate.isEmpty, model.state.suggestedActualDate != nil {
                Button("오늘 날짜 사용", action: model.useSuggestedDate)
                    .font(MoaLogFont.semibold(11, relativeTo: .caption2)).frame(minHeight: 44)
            }
            if let error = model.state.errors.actualDate { fieldError(error) }
        }
    }

    private var detailCard: some View {
        editorCard {
            Text("세부 내용 (선택)").font(MoaLogFont.semibold(12, relativeTo: .caption))
            TextField("예: 아파트 트레이더스 장보기", text: Binding(get: { model.state.detail }, set: model.changeDetail), axis: .vertical)
                .font(MoaLogFont.regular(13, relativeTo: .caption)).lineLimit(2...4)
                .padding(12).frame(minHeight: 52).background(MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 10))
                .accessibilityLabel("세부 내용")
        }
    }

    private var overspentCard: some View {
        Toggle(isOn: Binding(get: { model.state.overspent }, set: model.changeOverspent)) {
            VStack(alignment: .leading, spacing: 4) {
                Label("과소비로 표시하기", systemImage: "exclamationmark.circle.fill")
                    .font(MoaLogFont.bold(13, relativeTo: .caption)).foregroundStyle(MoaLogColor.error)
                Text("개인 이력과 가계 소비 분석에만 활용돼요.")
                    .font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
            }
        }
        .tint(MoaLogColor.error).padding(14).frame(minHeight: 68)
        .background(MoaLogColor.error.opacity(0.06), in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(MoaLogColor.error.opacity(0.25)))
    }

    private var deleteButton: some View {
        Button(role: .destructive, action: model.requestDelete) {
            Label("이 지출 삭제", systemImage: "trash").font(MoaLogFont.semibold(14)).frame(maxWidth: .infinity, minHeight: 48)
        }
    }

    private var saveButton: some View {
        Button(action: model.save) {
            Label(model.state.isSaving ? "저장 중…" : "저장하기", systemImage: "checkmark")
                .font(MoaLogFont.semibold(15)).foregroundStyle(.white).frame(maxWidth: .infinity, minHeight: 52)
                .background(MoaLogColor.teal, in: RoundedRectangle(cornerRadius: 12))
        }.buttonStyle(.plain).disabled(!model.state.canSave)
            .opacity(model.state.canSave ? 1 : 0.55)
    }

    private var loadingContent: some View {
        VStack(spacing: 12) { ProgressView().tint(MoaLogColor.teal); Text("입력 화면을 준비하는 중이에요").font(MoaLogFont.medium(13, relativeTo: .caption)) }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func editorCard<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10, content: content).padding(14)
            .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(MoaLogColor.cardBorder))
    }
    private func requiredLabel(_ text: String) -> some View { Text("\(text) *").font(MoaLogFont.semibold(12, relativeTo: .caption)) }
    private func fieldError(_ text: String) -> some View {
        Label(text, systemImage: "exclamationmark.circle.fill").font(MoaLogFont.medium(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.error)
            .accessibilityLabel("오류, \(text)")
    }
    private func quickButton(_ title: String, value: Int64) -> some View {
        Button { model.quickAdd(value) } label: { Text(title).font(MoaLogFont.medium(10, relativeTo: .caption2)).frame(maxWidth: .infinity, minHeight: 44).background(MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 9)) }
            .buttonStyle(.plain)
    }

    private var actualDateEnabled: Binding<Bool> { Binding(get: { !model.state.actualDate.isEmpty }, set: { enabled in model.changeActualDate(enabled ? dateString(Date()) : "") }) }
    private var actualDateBinding: Binding<Date> { Binding(get: { parseDate(model.state.actualDate) ?? Date() }, set: { model.changeActualDate(dateString($0)) }) }
    private var availableMonths: [YearMonthKey] { (-12...12).compactMap { offsetMonth(route.initialMonth, by: $0) } }
    private func monthDisplay(_ month: YearMonthKey) -> String { "\(month.year)년 \(month.month)월" }
    private func monthDisplayString(_ raw: String) -> String { parseMonth(raw).map(monthDisplay) ?? raw }

    private var deleteBinding: Binding<Bool> { Binding(get: { model.state.showDeleteConfirmation }, set: { if !$0 { model.dismissDelete() } }) }
    private var discardBinding: Binding<Bool> { Binding(get: { model.state.showDiscardConfirmation }, set: { if !$0 { model.dismissDiscard() } }) }
    private var persistenceErrorBinding: Binding<Bool> { Binding(get: { model.state.persistenceError != nil }, set: { if !$0 { model.dismissError() } }) }
    private var draftSignature: String { "\(model.state.amount)|\(model.state.categoryId ?? "")|\(model.state.attributionMonth)|\(model.state.actualDate)|\(model.state.detail)|\(model.state.overspent)" }

    private func restoreDraftIfNeeded() {
        guard !restoredDraft, !model.state.isLoading else { return }
        restoredDraft = true
        guard let draft = ExpenseEditorDraftRestoration.decode(persistedDraft), draft.routeId == route.id else { return }
        model.restore(draft)
    }
    private func persistDraftIfNeeded() {
        guard restoredDraft else { return }
        guard model.state.hasUnsavedChanges, !model.state.completed else {
            persistedDraft = ""
            return
        }
        persistedDraft = ExpenseEditorDraftRestoration(
            routeId: route.id, amount: model.state.amount, categoryId: model.state.categoryId,
            attributionMonth: model.state.attributionMonth, actualDate: model.state.actualDate,
            detail: model.state.detail, overspent: model.state.overspent
        ).encoded
    }
    private func complete() {
        guard !didFinish else { return }; didFinish = true; persistedDraft = ""
        UIAccessibility.post(notification: .announcement, argument: model.state.isEditing ? "지출을 수정했어요" : "지출을 저장했어요")
        onCompleted(parseMonth(model.state.attributionMonth) ?? route.initialMonth)
    }
    private func cancel() { guard !didFinish else { return }; didFinish = true; persistedDraft = ""; onCancel() }
}

private func parseMonth(_ value: String) -> YearMonthKey? {
    let parts = value.split(separator: "-"); guard parts.count == 2, let year = Int32(parts[0]), let month = Int32(parts[1]), (1900...9999).contains(year), (1...12).contains(month) else { return nil }
    return YearMonthKey(year: year, month: month)
}
private func offsetMonth(_ value: YearMonthKey, by delta: Int) -> YearMonthKey? {
    let index = Int(value.year) * 12 + Int(value.month) - 1 + delta
    let year = index / 12, month = index % 12 + 1
    guard (1900...9999).contains(year) else { return nil }
    return YearMonthKey(year: Int32(year), month: Int32(month))
}
private func parseDate(_ value: String) -> Date? {
    let formatter = DateFormatter(); formatter.calendar = Calendar(identifier: .gregorian); formatter.locale = Locale(identifier: "en_US_POSIX"); formatter.dateFormat = "yyyy-MM-dd"
    return formatter.date(from: value)
}
private func dateString(_ value: Date) -> String {
    let formatter = DateFormatter(); formatter.calendar = Calendar(identifier: .gregorian); formatter.locale = Locale(identifier: "en_US_POSIX"); formatter.dateFormat = "yyyy-MM-dd"
    return formatter.string(from: value)
}
private func expenseCategorySymbol(_ id: String) -> String {
    if id.contains("food") || id.contains("meal") { return "fork.knife" }
    if id.contains("transport") { return "bus.fill" }
    if id.contains("shopping") { return "cart.fill" }
    if id.contains("housing") { return "house.fill" }
    if id.contains("leisure") { return "gamecontroller.fill" }
    return "ellipsis"
}
