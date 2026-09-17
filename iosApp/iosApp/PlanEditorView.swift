import SharedKit
import SwiftUI
import UIKit

fileprivate struct PlanEditorRestoration: Codable {
    let routeId: String
    let type: String
    let month: String
    let name: String
    let amount: String
    let category: String
    let status: String
    let ownerMemberOrder: Int?
    let memo: String
    let includePurposeAccount: Bool
    let includeNetSavings: Bool
    let selectedCatalogId: Int64?

    var encoded: String { (try? JSONEncoder().encode(self)).flatMap { String(data: $0, encoding: .utf8) } ?? "" }
    static func decode(_ raw: String) -> Self? {
        guard let data = raw.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(Self.self, from: data)
    }
}

@MainActor
final class PlanEditorViewModel: ObservableObject {
    @Published private(set) var state: PlanEditorUiState

    private let store: IosPlanEditorStore
    private var observation: IosObservation?

    init(dependencies: IosDependencies, route: PlanEditorRoute) {
        let store: IosPlanEditorStore
        if let itemId = route.itemId {
            store = dependencies.existingPlanEditorStore(
                itemId: itemId,
                type: route.type,
                initialMonth: route.month
            )
        } else {
            store = dependencies.createPlanEditorStore(type: route.type, initialMonth: route.month)
        }
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

    func selectType(_ type: PlanItemType) { store.selectType(type: type) }
    func clearCatalog() { store.clearCatalog() }
    func selectCatalog(_ id: Int64) { store.selectCatalog(catalogId: id) }
    fileprivate func restore(_ value: PlanEditorRestoration) {
        let type: PlanItemType
        switch value.type {
        case PlanItemType.fixedexpense.name: type = .fixedexpense
        case PlanItemType.savings.name: type = .savings
        default: type = .income
        }
        let status: PlanItemStatus = value.status == PlanItemStatus.confirmed.name ? .confirmed : .estimated
        store.restoreDraft(
            type: type, month: value.month, name: value.name, amount: value.amount,
            category: value.category, status: status,
            ownerMemberOrder: value.ownerMemberOrder.map { KotlinInt(value: Int32($0)) },
            memo: value.memo, includePurposeAccount: value.includePurposeAccount,
            includeNetSavings: value.includeNetSavings,
            selectedCatalogId: value.selectedCatalogId.map { KotlinLong(value: $0) }
        )
    }
    func previousMonth() { store.previousMonth() }
    func nextMonth() { store.nextMonth() }
    func changeName(_ value: String) { store.changeName(value: value) }
    func changeAmount(_ value: String) { store.changeAmount(value: value) }
    func addAmount(_ value: Int64) { store.addAmount(won: value) }
    func resetAmount() { store.resetAmount() }
    func changeCategory(_ value: String) { store.changeCategory(value: value) }
    func changeStatus(_ value: PlanItemStatus) { store.changeStatus(value: value) }
    func selectCommonOwner() { store.selectCommonOwner() }
    func selectOwner(_ order: Int32) { store.selectOwner(memberOrder: order) }
    func changeMemo(_ value: String) { store.changeMemo(value: String(value.prefix(100))) }
    func changePurpose(_ value: Bool) { store.changePurposeAccount(value: value) }
    func changeNet(_ value: Bool) { store.changeNetSavings(value: value) }
    func save() { store.save() }
    func saveAndApply() { store.saveAndApply() }
    func retryLoad() { store.retryLoad() }
    func requestBack() { store.requestBack() }
    func dismissDiscard() { store.dismissDiscard() }
    func confirmDiscard() { store.confirmDiscard() }
    func requestDelete() { store.requestDelete() }
    func dismissDelete() { store.dismissDelete() }
    func confirmDelete() { store.confirmDelete() }
}

struct PlanEditorView: View {
    @StateObject private var model: PlanEditorViewModel
    @State private var didFinish = false
    @State private var didRestore = false
    @SceneStorage("moalog.plan.editor.draft.v2") private var storedDraft = ""
    @FocusState private var focusedField: EditorField?
    @AccessibilityFocusState private var validationFocus: ValidationField?

    let setup: LedgerSetup
    let route: PlanEditorRoute
    let onClose: () -> Void
    let onSaveAndApply: (PlanEditorCompletion) -> Void

    init(
        dependencies: IosDependencies,
        setup: LedgerSetup,
        route: PlanEditorRoute,
        onClose: @escaping () -> Void,
        onSaveAndApply: @escaping (PlanEditorCompletion) -> Void
    ) {
        _model = StateObject(wrappedValue: PlanEditorViewModel(dependencies: dependencies, route: route))
        self.setup = setup
        self.route = route
        self.onClose = onClose
        self.onSaveAndApply = onSaveAndApply
    }

    var body: some View {
        VStack(spacing: 0) {
            toolbar

            if model.state.isLoading {
                Spacer()
                ProgressView("입력 화면을 준비하고 있어요")
                    .font(MoaLogFont.medium(14))
                    .tint(MoaLogColor.teal)
                Spacer()
            } else if model.state.initialLoadFailed {
                initialLoadFailure
            } else {
                ScrollViewReader { proxy in
                    ScrollView {
                        VStack(spacing: 16) {
                            if let message = model.state.persistenceError {
                                errorBanner(message).id("error")
                            }
                            if route.itemId == nil { typeSelector }
                            if route.itemId == nil && !catalogItems.isEmpty { catalogSelector }
                            monthCard
                            coreFields
                            ownerAndStatusCard
                            if isSavings { savingsOptions }
                            memoCard
                        }
                        .padding(.horizontal, 20)
                        .padding(.top, 12)
                        .padding(.bottom, 24)
                        .frame(maxWidth: 520)
                        .frame(maxWidth: .infinity)
                    }
                    .onChange(of: model.state.persistenceError) { _, error in
                        if error != nil { withAnimation { proxy.scrollTo("error", anchor: .top) } }
                    }
                    .onChange(of: model.state.validationAttempt) { _, attempt in
                        guard attempt > 0 else { return }
                        focusFirstValidationError(using: proxy)
                    }
                }
                bottomActions
            }
        }
        .background(MoaLogColor.canvas.ignoresSafeArea())
        .interactiveDismissDisabled(model.state.hasUnsavedChanges || model.state.isSaving)
        .onAppear(perform: restoreDraftIfNeeded)
        .onChange(of: draftFingerprint) { _, _ in persistDraft() }
        .onChange(of: model.state.exitRequested) { _, requested in
            if requested { finishClose() }
        }
        .onChange(of: model.state.completed) { _, completed in
            guard completed, !didFinish else { return }
            didFinish = true
            storedDraft = ""
            if let result = model.state.completion, result.kind == .savedandapply {
                onSaveAndApply(result)
            } else {
                onClose()
            }
        }
        .alert("입력을 그만둘까요?", isPresented: discardAlertBinding) {
            Button("계속 작성", role: .cancel) { model.dismissDiscard() }
            Button("나가기", role: .destructive) { model.confirmDiscard() }
        } message: {
            Text("저장하지 않은 내용이 사라져요.")
        }
        .alert("계획 항목을 삭제할까요?", isPresented: deleteAlertBinding) {
            Button("취소", role: .cancel) { model.dismissDelete() }
            Button("삭제", role: .destructive) { model.confirmDelete() }
        } message: {
            Text("삭제한 항목은 되돌릴 수 없어요.")
        }
    }

    private var toolbar: some View {
        HStack(spacing: 4) {
            Button(action: model.requestBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 17, weight: .semibold))
                    .frame(width: 48, height: 48)
            }
            .foregroundStyle(MoaLogColor.ink)
            .accessibilityLabel("월별 계획으로 돌아가기")

            VStack(spacing: 1) {
                Text(model.state.isEditing ? "계획 항목 수정" : "계획 항목 추가")
                    .font(MoaLogFont.bold(18, relativeTo: .headline))
                    .foregroundStyle(MoaLogColor.ink)
                Text(planEditorTypeTitle(model.state.type))
                    .font(MoaLogFont.regular(11, relativeTo: .caption2))
                    .foregroundStyle(MoaLogColor.mutedInk)
            }
            .frame(maxWidth: .infinity)

            if model.state.isEditing && !model.state.isLoading && !model.state.initialLoadFailed {
                Button(action: model.requestDelete) {
                    Image(systemName: "trash")
                        .font(.system(size: 16, weight: .semibold))
                        .frame(width: 48, height: 48)
                }
                .foregroundStyle(MoaLogColor.error)
                .disabled(model.state.isSaving)
                .accessibilityLabel("계획 항목 삭제")
            } else {
                Color.clear.frame(width: 48, height: 48)
            }
        }
        .padding(.horizontal, 4)
        .background(MoaLogColor.surface)
        .overlay(alignment: .bottom) { Rectangle().fill(MoaLogColor.cardBorder).frame(height: 1) }
    }

    private var initialLoadFailure: some View {
        VStack(spacing: 14) {
            Spacer()
            Image(systemName: "exclamationmark.circle.fill")
                .font(.system(size: 34, weight: .semibold))
                .foregroundStyle(MoaLogColor.error)
                .accessibilityHidden(true)
            Text("계획 항목을 불러오지 못했어요")
                .font(MoaLogFont.bold(18, relativeTo: .headline))
                .foregroundStyle(MoaLogColor.ink)
            Text(model.state.persistenceError ?? "잠시 후 다시 시도해 주세요.")
                .font(MoaLogFont.regular(13, relativeTo: .subheadline))
                .foregroundStyle(MoaLogColor.mutedInk)
                .multilineTextAlignment(.center)
            Button("다시 불러오기", action: model.retryLoad)
                .font(MoaLogFont.bold(15))
                .foregroundStyle(.white)
                .frame(maxWidth: 260, minHeight: 52)
                .background(MoaLogColor.teal, in: RoundedRectangle(cornerRadius: 12))
            Button("월별 계획으로 돌아가기", action: model.requestBack)
                .font(MoaLogFont.semibold(14))
                .foregroundStyle(MoaLogColor.teal)
                .frame(minHeight: 48)
            Spacer()
        }
        .padding(24)
    }

    private var typeSelector: some View {
        editorCard {
            fieldLabel("계획 종류")
            HStack(spacing: 6) {
                typeButton("수입", .income)
                typeButton("고정지출", .fixedexpense)
                typeButton("저축·투자", .savings)
            }
        }
    }

    private func typeButton(_ title: String, _ type: PlanItemType) -> some View {
        Button { model.selectType(type) } label: {
            Text(title)
                .font(MoaLogFont.semibold(13, relativeTo: .subheadline))
                .foregroundStyle(model.state.type == type ? .white : MoaLogColor.mutedInk)
                .frame(maxWidth: .infinity, minHeight: 48)
                .background(model.state.type == type ? MoaLogColor.teal : MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(model.state.type == type ? .isSelected : [])
    }

    private var monthCard: some View {
        editorCard {
            fieldLabel("귀속 월", required: true)
            HStack(spacing: 4) {
                monthButton("chevron.left", label: "이전 달", enabled: canMovePrevious, action: model.previousMonth)
                Image(systemName: "calendar")
                    .foregroundStyle(MoaLogColor.teal)
                    .accessibilityHidden(true)
                Text(monthTitle)
                    .font(MoaLogFont.semibold(16))
                    .foregroundStyle(MoaLogColor.ink)
                    .frame(maxWidth: .infinity)
                monthButton("chevron.right", label: "다음 달", enabled: canMoveNext, action: model.nextMonth)
            }
            .id(ValidationField.month)
            .accessibilityFocused($validationFocus, equals: .month)
            .frame(minHeight: 52)
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(fieldBorder(model.state.errors.month), lineWidth: 1))
            fieldError(model.state.errors.month)
            Text("입력한 금액이 합산될 달이에요.")
                .font(MoaLogFont.regular(11, relativeTo: .caption2))
                .foregroundStyle(MoaLogColor.mutedInk)
        }
    }

    private var catalogSelector: some View {
        editorCard {
            fieldLabel("관리 항목에서 선택")
            Menu {
                Button("직접 입력") { model.clearCatalog() }
                ForEach(catalogItems, id: \.id) { item in
                    Button(item.name) { model.selectCatalog(item.id) }
                }
            } label: {
                HStack(spacing: 10) {
                    Image(systemName: "list.bullet.rectangle").foregroundStyle(MoaLogColor.teal)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(selectedCatalogName ?? "직접 입력")
                            .font(MoaLogFont.semibold(14)).foregroundStyle(MoaLogColor.ink)
                        Text("항목 관리에 저장한 이름과 속성을 불러와요")
                            .font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
                    }
                    Spacer(); Image(systemName: "chevron.down").foregroundStyle(MoaLogColor.mutedInk)
                }
                .padding(.horizontal, 12).frame(minHeight: 52)
                .background(MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 11))
            }
            .accessibilityLabel("관리 항목 선택")
            .accessibilityValue(selectedCatalogName ?? "직접 입력")
        }
    }

    private var coreFields: some View {
        editorCard {
            fieldLabel("항목명", required: true)
            TextField("예: 월급, 관리비, 주택청약", text: textBinding(\.name, model.changeName))
                .font(MoaLogFont.regular(16))
                .textInputAutocapitalization(.never)
                .padding(.horizontal, 14)
                .frame(minHeight: 52)
                .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(fieldBorder(model.state.errors.name), lineWidth: 1))
                .focused($focusedField, equals: .name)
                .id(ValidationField.name)
                .accessibilityFocused($validationFocus, equals: .name)
                .accessibilityLabel("항목명 필수")
            fieldError(model.state.errors.name)

            fieldLabel("월 금액", required: true)
            HStack(spacing: 8) {
                TextField("0", text: amountBinding)
                    .font(MoaLogFont.bold(22, relativeTo: .title3))
                    .keyboardType(isSavings ? .numbersAndPunctuation : .numberPad)
                    .multilineTextAlignment(.trailing)
                    .focused($focusedField, equals: .amount)
                Text("원")
                    .font(MoaLogFont.semibold(15))
                    .foregroundStyle(MoaLogColor.mutedInk)
            }
            .id(ValidationField.amount)
            .accessibilityFocused($validationFocus, equals: .amount)
            .padding(.horizontal, 14)
            .frame(minHeight: 58)
            .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(fieldBorder(model.state.errors.amount), lineWidth: 1))
            .accessibilityElement(children: .combine)
            .accessibilityLabel("월 금액 필수")
            fieldError(model.state.errors.amount)

            HStack(spacing: 7) {
                amountButton("+10만", 100_000)
                amountButton("+50만", 500_000)
                amountButton("+100만", 1_000_000)
                Button("초기화", action: model.resetAmount)
                    .font(MoaLogFont.semibold(12, relativeTo: .caption))
                    .foregroundStyle(MoaLogColor.mutedInk)
                    .frame(minWidth: 64, minHeight: 48)
                    .background(MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 10))
            }
            if isSavings {
                Label("인출·매도·원금 감소는 음수로 입력할 수 있어요.", systemImage: "info.circle")
                    .font(MoaLogFont.regular(11, relativeTo: .caption2))
                    .foregroundStyle(MoaLogColor.mutedInk)
            }

            fieldLabel(isSavings ? "저축 카테고리" : "카테고리", required: true)
            Menu {
                ForEach(categories, id: \.self) { category in
                    Button(category) { model.changeCategory(category) }
                }
            } label: {
                HStack {
                    Text(model.state.category.isEmpty ? "선택해 주세요" : model.state.category)
                        .font(MoaLogFont.regular(15))
                        .foregroundStyle(model.state.category.isEmpty ? MoaLogColor.mutedInk : MoaLogColor.ink)
                    Spacer()
                    Image(systemName: "chevron.down")
                }
                .padding(.horizontal, 14)
                .frame(minHeight: 52)
                .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(fieldBorder(model.state.errors.category), lineWidth: 1))
            }
            .id(ValidationField.category)
            .accessibilityFocused($validationFocus, equals: .category)
            .accessibilityLabel("카테고리 필수")
            .accessibilityValue(model.state.category.isEmpty ? "선택 안 됨" : model.state.category)
            fieldError(model.state.errors.category)
        }
    }

    private var ownerAndStatusCard: some View {
        editorCard {
            if !isSavings {
                fieldLabel(model.state.type == .income ? "소득 귀속" : "공동/구성원")
                HStack(spacing: 7) {
                    ownerButton("공동", order: nil)
                    ForEach(setup.members.sorted { $0.order < $1.order }, id: \.order) { member in
                        ownerButton(member.displayName, order: member.order)
                    }
                }
                Divider().overlay(MoaLogColor.divider)
            }

            fieldLabel("금액 상태")
            HStack(spacing: 7) {
                statusButton("예상", .estimated, detail: "계획 중인 금액")
                statusButton("확정", .confirmed, detail: "확정된 금액")
            }
        }
    }

    private var savingsOptions: some View {
        editorCard {
            Toggle(isOn: boolBinding(\.includePurposeAccount, model.changePurpose)) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("목적통장 항목")
                        .font(MoaLogFont.semibold(14))
                    Text("모든 저축은 목적통장 포함 저축 합계와 저축률에 반영돼요.")
                        .font(MoaLogFont.regular(11, relativeTo: .caption2))
                        .foregroundStyle(MoaLogColor.mutedInk)
                }
            }
            .tint(MoaLogColor.teal)
            .frame(minHeight: 56)

            Divider().overlay(MoaLogColor.divider)

            Toggle(isOn: boolBinding(\.includeNetSavings, model.changeNet)) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("순저축에 포함")
                        .font(MoaLogFont.semibold(14))
                    Text("켜면 순저축 합계와 순저축률에 반영돼요.")
                        .font(MoaLogFont.regular(11, relativeTo: .caption2))
                        .foregroundStyle(MoaLogColor.mutedInk)
                }
            }
            .tint(MoaLogColor.teal)
            .frame(minHeight: 56)
        }
    }

    private var memoCard: some View {
        editorCard {
            HStack {
                fieldLabel("메모")
                Spacer()
                Text("\(model.state.memo.count)/100")
                    .font(MoaLogFont.regular(11, relativeTo: .caption2))
                    .foregroundStyle(MoaLogColor.mutedInk)
            }
            ZStack(alignment: .topLeading) {
                if model.state.memo.isEmpty {
                    Text("필요한 내용을 적어 주세요")
                        .font(MoaLogFont.regular(15))
                        .foregroundStyle(MoaLogColor.mutedInk)
                        .padding(.horizontal, 13)
                        .padding(.vertical, 15)
                }
                TextEditor(text: textBinding(\.memo, model.changeMemo))
                    .font(MoaLogFont.regular(15))
                    .scrollContentBackground(.hidden)
                    .padding(8)
                    .frame(minHeight: 108)
                    .background(Color.clear)
                    .focused($focusedField, equals: .memo)
            }
            .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(MoaLogColor.cardBorder, lineWidth: 1))
        }
    }

    private var bottomActions: some View {
        let saveAndApplyDisabled = model.state.isSaving || model.state.selectedCatalogIsArchived
        return VStack(spacing: 6) {
            Button(action: model.save) {
                HStack(spacing: 8) {
                    if model.state.isSaving { ProgressView().tint(.white) }
                    Text(model.state.isSaving ? "저장 중" : "저장하기")
                }
                .font(MoaLogFont.bold(16))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, minHeight: 52)
                .background(model.state.isSaving ? MoaLogColor.outline : MoaLogColor.teal, in: RoundedRectangle(cornerRadius: 12))
            }
            .buttonStyle(.plain)
            .disabled(model.state.isSaving)

            Button("저장 후 여러 달에 적용", action: model.saveAndApply)
                .font(MoaLogFont.semibold(14, relativeTo: .subheadline))
                .foregroundStyle(saveAndApplyDisabled ? MoaLogColor.mutedInk : MoaLogColor.teal)
                .frame(maxWidth: .infinity, minHeight: 48)
                .disabled(saveAndApplyDisabled)
                .accessibilityHint(model.state.selectedCatalogIsArchived ? "보관된 항목은 여러 달에 적용할 수 없습니다" : "저장한 뒤 적용할 달을 선택합니다")
        }
        .padding(.horizontal, 20)
        .padding(.top, 10)
        .padding(.bottom, 8)
        .background(MoaLogColor.surface.shadow(.drop(color: .black.opacity(0.08), radius: 7, y: -2)))
    }

    private func editorCard<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 11, content: content)
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(MoaLogColor.cardBorder, lineWidth: 1))
    }

    private func fieldLabel(_ title: String, required: Bool = false) -> some View {
        HStack(spacing: 3) {
            Text(title).font(MoaLogFont.semibold(13, relativeTo: .subheadline)).foregroundStyle(MoaLogColor.ink)
            if required { Text("*").foregroundStyle(MoaLogColor.error).accessibilityLabel("필수") }
        }
    }

    @ViewBuilder private func fieldError(_ message: String?) -> some View {
        if let message {
            Text(message)
                .font(MoaLogFont.regular(11, relativeTo: .caption2))
                .foregroundStyle(MoaLogColor.error)
                .accessibilityLabel("오류: \(message)")
        }
    }

    private func errorBanner(_ message: String) -> some View {
        Label(message, systemImage: "exclamationmark.circle.fill")
            .font(MoaLogFont.medium(12, relativeTo: .caption))
            .foregroundStyle(MoaLogColor.error)
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(MoaLogColor.errorSurface.opacity(0.65), in: RoundedRectangle(cornerRadius: 12))
            .accessibilityLabel("저장 오류: \(message)")
    }

    private func monthButton(_ symbol: String, label: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).frame(width: 48, height: 48)
        }
        .foregroundStyle(enabled ? MoaLogColor.ink : MoaLogColor.outline.opacity(0.35))
        .disabled(!enabled)
        .accessibilityLabel(label)
    }

    private func amountButton(_ title: String, _ value: Int64) -> some View {
        Button(title) { model.addAmount(value) }
            .font(MoaLogFont.semibold(12, relativeTo: .caption))
            .foregroundStyle(MoaLogColor.teal)
            .frame(maxWidth: .infinity, minHeight: 48)
            .background(MoaLogColor.sage, in: RoundedRectangle(cornerRadius: 10))
    }

    private func ownerButton(_ title: String, order: Int32?) -> some View {
        let selected = order == nil
            ? model.state.ownerMemberOrder == nil
            : model.state.ownerMemberOrder?.int32Value == order
        return Button {
            if let order { model.selectOwner(order) } else { model.selectCommonOwner() }
        } label: {
            Text(title)
                .font(MoaLogFont.semibold(13, relativeTo: .subheadline))
                .foregroundStyle(selected ? MoaLogColor.teal : MoaLogColor.mutedInk)
                .frame(maxWidth: .infinity, minHeight: 48)
                .background(selected ? MoaLogColor.sage : MoaLogColor.surfaceLow, in: Capsule())
                .overlay(Capsule().stroke(selected ? MoaLogColor.teal : MoaLogColor.cardBorder, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func statusButton(_ title: String, _ status: PlanItemStatus, detail: String) -> some View {
        let selected = model.state.status == status
        return Button { model.changeStatus(status) } label: {
            VStack(spacing: 2) {
                Text(title).font(MoaLogFont.semibold(14))
                Text(detail).font(MoaLogFont.regular(10, relativeTo: .caption2))
            }
            .foregroundStyle(selected ? MoaLogColor.teal : MoaLogColor.mutedInk)
            .frame(maxWidth: .infinity, minHeight: 54)
            .background(selected ? MoaLogColor.sage : MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 11))
            .overlay(RoundedRectangle(cornerRadius: 11).stroke(selected ? MoaLogColor.teal : MoaLogColor.cardBorder, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func textBinding(_ keyPath: KeyPath<PlanEditorUiState, String>, _ setter: @escaping (String) -> Void) -> Binding<String> {
        Binding(get: { model.state[keyPath: keyPath] }, set: setter)
    }

    private func boolBinding(_ keyPath: KeyPath<PlanEditorUiState, Bool>, _ setter: @escaping (Bool) -> Void) -> Binding<Bool> {
        Binding(get: { model.state[keyPath: keyPath] }, set: setter)
    }

    private var amountBinding: Binding<String> {
        Binding(
            get: { formattedAmountInput(model.state.amount) },
            set: { model.changeAmount(sanitizedAmountInput($0, allowsNegative: isSavings)) }
        )
    }

    private var discardAlertBinding: Binding<Bool> {
        Binding(get: { model.state.showDiscardConfirmation }, set: { if !$0 { model.dismissDiscard() } })
    }

    private var deleteAlertBinding: Binding<Bool> {
        Binding(get: { model.state.showDeleteConfirmation }, set: { if !$0 { model.dismissDelete() } })
    }

    private var isSavings: Bool { model.state.type == .savings }
    private var monthTitle: String {
        let parts = model.state.month.split(separator: "-")
        guard parts.count == 2 else { return model.state.month }
        return "\(parts[0])년 \(Int(parts[1]) ?? 0)월"
    }
    private var canMovePrevious: Bool { model.state.month != "1900-01" }
    private var canMoveNext: Bool { model.state.month != "9999-12" }
    private var categories: [String] {
        let managed = catalogItems.map(\.classification)
        let defaults: [String]
        if model.state.type == .income { defaults = ["급여", "부수입", "성과급", "기타 수입"] }
        else if model.state.type == .fixedexpense { defaults = ["주거비", "교통비", "생활비", "보험", "통신비", "비상금", "구독", "개별 용돈"] }
        else { defaults = ["예적금", "청약", "주식", "부동산", "대출원금", "퇴직금"] }
        return (managed + defaults).reduce(into: []) { if !$0.contains($1) { $0.append($1) } }
    }

    private var catalogItems: [PlanCatalogItem] {
        model.state.catalogItems
            .filter { $0.type == model.state.type && !$0.archived }
            .sorted { lhs, rhs in lhs.displayOrder == rhs.displayOrder ? lhs.id < rhs.id : lhs.displayOrder < rhs.displayOrder }
    }
    private var selectedCatalogName: String? {
        guard let id = model.state.selectedCatalogId?.int64Value else { return nil }
        return catalogItems.first { $0.id == id }?.name
    }

    private func fieldBorder(_ error: String?) -> Color { error == nil ? MoaLogColor.cardBorder : MoaLogColor.error }
    private func finishClose() { guard !didFinish else { return }; didFinish = true; storedDraft = ""; onClose() }

    private func focusFirstValidationError(using proxy: ScrollViewProxy) {
        let target: ValidationField
        let message: String
        if let error = model.state.errors.month {
            target = .month
            message = error
        } else if let error = model.state.errors.name {
            target = .name
            message = error
        } else if let error = model.state.errors.amount {
            target = .amount
            message = error
        } else if let error = model.state.errors.category {
            target = .category
            message = error
        } else {
            return
        }

        withAnimation { proxy.scrollTo(target, anchor: .center) }
        DispatchQueue.main.async {
            validationFocus = target
            switch target {
            case .name: focusedField = .name
            case .amount: focusedField = .amount
            case .month, .category: focusedField = nil
            }
            UIAccessibility.post(notification: .announcement, argument: "입력 오류. \(message)")
        }
    }

    private var draftFingerprint: String {
        "\(model.state.type.name)|\(model.state.month)|\(model.state.name)|\(model.state.amount)|\(model.state.category)|\(model.state.status.name)|\(model.state.ownerMemberOrder?.int32Value ?? -1)|\(model.state.memo)|\(model.state.includePurposeAccount)|\(model.state.includeNetSavings)|\(model.state.selectedCatalogId?.int64Value ?? -1)|\(model.state.hasUnsavedChanges)"
    }

    private func restoreDraftIfNeeded() {
        guard !didRestore else { return }
        didRestore = true
        guard let draft = PlanEditorRestoration.decode(storedDraft), draft.routeId == route.id else { return }
        model.restore(draft)
    }

    private func persistDraft() {
        guard didRestore else { return }
        guard model.state.hasUnsavedChanges, !model.state.completed else { storedDraft = ""; return }
        storedDraft = PlanEditorRestoration(
            routeId: route.id,
            type: model.state.type.name,
            month: model.state.month,
            name: model.state.name,
            amount: model.state.amount,
            category: model.state.category,
            status: model.state.status.name,
            ownerMemberOrder: model.state.ownerMemberOrder.map { Int($0.int32Value) },
            memo: model.state.memo,
            includePurposeAccount: model.state.includePurposeAccount,
            includeNetSavings: model.state.includeNetSavings,
            selectedCatalogId: model.state.selectedCatalogId?.int64Value
        ).encoded
    }

    private enum EditorField { case name, amount, memo }
    private enum ValidationField: Hashable { case month, name, amount, category }
}

private func sanitizedAmountInput(_ value: String, allowsNegative: Bool) -> String {
    let negative = allowsNegative && value.trimmingCharacters(in: .whitespaces).hasPrefix("-")
    let digits = value.filter(\.isNumber)
    return negative ? "-" + digits : digits
}

private func formattedAmountInput(_ raw: String) -> String {
    let negative = raw.hasPrefix("-")
    let digits = raw.filter(\.isNumber)
    guard let value = Int64(digits) else { return negative ? "-" : "" }
    let formatter = NumberFormatter()
    formatter.numberStyle = .decimal
    formatter.maximumFractionDigits = 0
    let formatted = formatter.string(from: NSNumber(value: value)) ?? digits
    return negative ? "-" + formatted : formatted
}
