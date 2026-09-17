import SharedKit
import SwiftUI

@MainActor
private final class FixedCostCheckViewModel: ObservableObject {
    @Published private(set) var state: FixedCostCheckUiState
    private let store: IosFixedCostCheckStore
    private var observation: IosObservation?

    init(dependencies: IosDependencies, setup: LedgerSetup, month: YearMonthKey) {
        let members = setup.members.sorted { $0.order < $1.order }
        let store = dependencies.fixedCostCheckStore(
            month: month,
            firstMemberOrder: members[0].order,
            secondMemberOrder: members[1].order
        )
        self.store = store
        state = store.currentState
        observation = store.observe { [weak self] state in
            DispatchQueue.main.async { self?.state = state }
        }
    }

    deinit { observation?.cancel(); store.close() }

    func toggle(_ item: FixedCostCheckItem) { store.toggleItem(id: item.id) }
    func toggleAll() { store.toggleAll() }
    func toggleGroup(_ order: Int32?) {
        if let order { store.toggleMemberPayer(memberOrder: order) }
        else { store.toggleCommonPayer() }
    }
    func add() { store.openNewEditor() }
    func edit(_ item: FixedCostCheckItem) { store.openEditor(item: item) }
    func closeEditor() { store.closeEditor() }
    func selectPayer(_ order: Int32?) {
        if let order { store.selectMemberPayer(memberOrder: order) }
        else { store.selectCommonPayer() }
    }
    func changeName(_ value: String) { store.changeName(value: value) }
    func changeAmount(_ value: String) { store.changeAmount(value: value) }
    func save() { store.save() }
    func delete() { store.delete() }
    func requestApply() { store.requestApply() }
    func overwriteExisting() { store.overwriteExisting() }
    func keepExisting() { store.keepExisting() }
    func dismissConflict() { store.dismissConflict() }
    func clearMessage() { store.clearMessage() }
    func conflictRows() -> [IosFixedCostConflictRow] { store.conflictRows() }
}

struct FixedCostCheckView: View {
    let dependencies: IosDependencies
    let setup: LedgerSetup
    let initialMonth: YearMonthKey
    let onClose: (YearMonthKey) -> Void
    let onApplied: (YearMonthKey) -> Void

    @State private var month: YearMonthKey
    @State private var didReturn = false

    init(
        dependencies: IosDependencies,
        setup: LedgerSetup,
        initialMonth: YearMonthKey,
        onClose: @escaping (YearMonthKey) -> Void,
        onApplied: @escaping (YearMonthKey) -> Void
    ) {
        self.dependencies = dependencies
        self.setup = setup
        self.initialMonth = initialMonth
        self.onClose = onClose
        self.onApplied = onApplied
        _month = State(initialValue: initialMonth)
    }

    var body: some View {
        NavigationStack {
            FixedCostCheckMonthView(
                dependencies: dependencies,
                setup: setup,
                month: month,
                onClose: { _ in finishClose() },
                onApplied: finishApplied,
                onPreviousMonth: { month = previousFixedCostMonth(month) },
                onNextMonth: { month = nextFixedCostMonth(month) }
            )
            .id("fixed-cost-\(month.year)-\(month.month)")
        }
        .tint(MoaLogColor.teal)
        .onDisappear(perform: finishClose)
    }

    private func finishClose() {
        guard !didReturn else { return }
        didReturn = true
        onClose(month)
    }

    private func finishApplied(_ appliedMonth: YearMonthKey) {
        guard !didReturn else { return }
        didReturn = true
        onApplied(appliedMonth)
    }
}

private struct FixedCostCheckMonthView: View {
    @StateObject private var model: FixedCostCheckViewModel
    @State private var showResult = false

    let setup: LedgerSetup
    let month: YearMonthKey
    let onClose: (YearMonthKey) -> Void
    let onApplied: (YearMonthKey) -> Void
    let onPreviousMonth: () -> Void
    let onNextMonth: () -> Void

    init(
        dependencies: IosDependencies,
        setup: LedgerSetup,
        month: YearMonthKey,
        onClose: @escaping (YearMonthKey) -> Void,
        onApplied: @escaping (YearMonthKey) -> Void,
        onPreviousMonth: @escaping () -> Void,
        onNextMonth: @escaping () -> Void
    ) {
        _model = StateObject(wrappedValue: FixedCostCheckViewModel(dependencies: dependencies, setup: setup, month: month))
        self.setup = setup
        self.month = month
        self.onClose = onClose
        self.onApplied = onApplied
        self.onPreviousMonth = onPreviousMonth
        self.onNextMonth = onNextMonth
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                LazyVStack(spacing: 14) {
                    monthSelector
                    summaryCard

                    if model.state.isLoading {
                        ProgressView("고정비를 불러오는 중이에요")
                            .font(MoaLogFont.regular(13, relativeTo: .caption))
                            .tint(MoaLogColor.teal)
                            .frame(maxWidth: .infinity, minHeight: 130)
                    } else if model.state.sheet.items.isEmpty {
                        emptyState
                    } else {
                        ForEach(payerGroups, id: \.id) { group in
                            if !group.items.isEmpty { payerSection(group) }
                        }
                    }

                    if let error = model.state.error {
                        Text(error)
                            .font(MoaLogFont.medium(12, relativeTo: .caption))
                            .foregroundStyle(MoaLogColor.error)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .accessibilityLabel("오류, \(error)")
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 10)
                .padding(.bottom, 18)
                .frame(maxWidth: 520)
                .frame(maxWidth: .infinity)
            }

            actionBar
        }
        .background(MoaLogColor.homeCanvas.ignoresSafeArea())
        .navigationTitle("고정비 점검")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button { onClose(month) } label: { Image(systemName: "chevron.left") }
                    .accessibilityLabel("계획으로 돌아가기")
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button(action: model.toggleAll) {
                    Text(model.state.selectedIds.count == model.state.sheet.items.count ? "전체 해제" : "전체 선택")
                        .font(MoaLogFont.semibold(12, relativeTo: .caption))
                }
                .frame(minHeight: 44)
            }
        }
        .sheet(isPresented: editorBinding) {
            if let draft = model.state.editor {
                FixedCostEditorSheet(model: model, setup: setup, draft: draft)
                    .presentationDetents([.medium, .large])
            }
        }
        .fullScreenCover(isPresented: conflictBinding) {
            FixedCostConflictView(model: model, setup: setup)
        }
        .alert("계획 반영 완료", isPresented: $showResult) {
            Button("확인") {
                model.clearMessage()
                onApplied(month)
            }
        } message: {
            Text(model.state.resultMessage ?? "선택한 고정비를 계획에 반영했어요")
        }
        .onChange(of: model.state.resultMessage) { _, value in
            if value != nil { showResult = true }
        }
    }

    private var editorBinding: Binding<Bool> {
        Binding(get: { model.state.editor != nil }, set: { if !$0 { model.closeEditor() } })
    }

    private var conflictBinding: Binding<Bool> {
        Binding(get: { model.state.conflictPreview != nil }, set: { if !$0 { model.dismissConflict() } })
    }

    private var monthSelector: some View {
        HStack(spacing: 4) {
            monthButton("이전 달", symbol: "chevron.left", action: onPreviousMonth)
            Spacer()
            Image(systemName: "calendar")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(MoaLogColor.teal)
                .accessibilityHidden(true)
            Text("\(String(month.year))년 \(String(month.month))월")
                .font(MoaLogFont.semibold(15))
            Spacer()
            monthButton("다음 달", symbol: "chevron.right", action: onNextMonth)
        }
        .padding(.horizontal, 4)
        .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(MoaLogColor.cardBorder))
    }

    private func monthButton(_ label: String, symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).frame(width: 48, height: 48).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    private var summaryCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Image(systemName: "doc.text.fill")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(MoaLogColor.teal)
                    .frame(width: 38, height: 38)
                    .background(MoaLogColor.sage, in: Circle())
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text("매월 정기 고정 지출")
                        .font(MoaLogFont.semibold(13, relativeTo: .caption))
                    Text("\(String(month.year)).\(String(format: "%02d", month.month)) 기준")
                        .font(MoaLogFont.regular(10, relativeTo: .caption2))
                        .foregroundStyle(MoaLogColor.mutedInk)
                }
            }

            Text(totalText(model.state.sheet.items))
                .font(MoaLogFont.bold(28, relativeTo: .title))
                .foregroundStyle(MoaLogColor.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.7)

            Text("매월 정기적으로 나가는 고정비를 점검하고 월별 계획에 가져오세요.")
                .font(MoaLogFont.regular(12, relativeTo: .caption))
                .foregroundStyle(MoaLogColor.mutedInk)

            HStack(spacing: 6) {
                ForEach(payerGroups, id: \.id) { group in
                    VStack(spacing: 2) {
                        Text(group.label)
                            .font(MoaLogFont.regular(9, relativeTo: .caption2))
                            .foregroundStyle(MoaLogColor.mutedInk)
                        Text(totalText(group.items, suffix: false))
                            .font(MoaLogFont.bold(10, relativeTo: .caption2))
                            .foregroundStyle(MoaLogColor.teal)
                            .lineLimit(1)
                            .minimumScaleFactor(0.6)
                    }
                    .frame(maxWidth: .infinity, minHeight: 40)
                    .background(MoaLogColor.surfaceLow, in: Capsule())
                }
            }
        }
        .padding(16)
        .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(MoaLogColor.cardBorder))
        .accessibilityElement(children: .combine)
    }

    private func payerSection(_ group: FixedPayerGroup) -> some View {
        let selected = group.items.filter { model.state.selectedIds.contains(KotlinLong(value: $0.id)) }
        return VStack(spacing: 0) {
            HStack {
                Text("\(group.label) 고정비 목록")
                    .font(MoaLogFont.bold(15))
                Spacer()
                Button("전체 선택") { model.toggleGroup(group.order) }
                    .font(MoaLogFont.semibold(11, relativeTo: .caption2))
                    .frame(minHeight: 44)
            }

            VStack(spacing: 0) {
                ForEach(Array(group.items.enumerated()), id: \.element.id) { index, item in
                    fixedCostRow(item)
                    if index < group.items.count - 1 {
                        Divider().padding(.leading, 48)
                    }
                }
            }
            .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(MoaLogColor.cardBorder))

            HStack {
                Text("\(group.label) 소계 (\(selected.count)건 선택됨)")
                    .font(MoaLogFont.regular(10, relativeTo: .caption2))
                    .foregroundStyle(MoaLogColor.mutedInk)
                Spacer()
                Text(totalText(selected))
                    .font(MoaLogFont.bold(12, relativeTo: .caption))
                    .foregroundStyle(MoaLogColor.teal)
            }
            .padding(.horizontal, 8)
            .padding(.top, 8)
        }
    }

    private func fixedCostRow(_ item: FixedCostCheckItem) -> some View {
        let selected = model.state.selectedIds.contains(KotlinLong(value: item.id))
        return HStack(spacing: 8) {
            Button { model.toggle(item) } label: {
                Image(systemName: selected ? "checkmark.square.fill" : "square")
                    .font(.system(size: 20, weight: .medium))
                    .foregroundStyle(selected ? MoaLogColor.teal : MoaLogColor.outline)
                    .frame(width: 44, height: 52)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(item.name) 선택")
            .accessibilityValue(selected ? "선택됨" : "선택 안 됨")

            Image(systemName: item.name.contains("관리") || item.name.contains("주거") ? "building.2.fill" : "house.fill")
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(MoaLogColor.teal)
                .frame(width: 34, height: 34)
                .background(MoaLogColor.sage, in: Circle())
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 3) {
                Text(item.name)
                    .font(MoaLogFont.semibold(13))
                    .foregroundStyle(MoaLogColor.ink)
                if item.amountWon == nil {
                    Text("금액 미입력")
                        .font(MoaLogFont.regular(10, relativeTo: .caption2))
                        .foregroundStyle(MoaLogColor.error)
                }
            }
            Spacer(minLength: 2)
            Text(item.amountWon.map { formatWon($0.int64Value) } ?? "—")
                .font(MoaLogFont.bold(12, relativeTo: .caption))
                .foregroundStyle(MoaLogColor.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Button { model.edit(item) } label: {
                Image(systemName: "pencil")
                    .frame(width: 44, height: 52)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(item.name) 편집")
        }
        .padding(.horizontal, 6)
        .frame(minHeight: 64)
    }

    private var emptyState: some View {
        VStack(spacing: 8) {
            Image(systemName: "doc.badge.plus")
                .font(.system(size: 26, weight: .medium))
                .foregroundStyle(MoaLogColor.teal)
                .accessibilityHidden(true)
            Text("점검할 고정비가 없어요")
                .font(MoaLogFont.bold(15))
            Text("이번 달 정기 지출 항목을 추가해 주세요")
                .font(MoaLogFont.regular(12, relativeTo: .caption))
                .foregroundStyle(MoaLogColor.mutedInk)
            Button("첫 항목 추가", action: model.add)
                .font(MoaLogFont.semibold(13, relativeTo: .caption))
                .frame(minHeight: 44)
        }
        .frame(maxWidth: .infinity, minHeight: 150)
        .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(MoaLogColor.cardBorder))
    }

    private var actionBar: some View {
        HStack(spacing: 8) {
            Button(action: model.add) {
                Label("항목 추가", systemImage: "plus")
                    .font(MoaLogFont.semibold(13, relativeTo: .caption))
                    .foregroundStyle(MoaLogColor.teal)
                    .frame(maxWidth: .infinity, minHeight: 52)
                    .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(MoaLogColor.cardBorder))
            }
            .buttonStyle(.plain)

            Button(action: model.requestApply) {
                HStack(spacing: 5) {
                    Text("계획에 가져오기")
                    Image(systemName: "arrow.right")
                }
                .font(MoaLogFont.semibold(13, relativeTo: .caption))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, minHeight: 52)
                .background(MoaLogColor.teal, in: RoundedRectangle(cornerRadius: 12))
            }
            .buttonStyle(.plain)
            .disabled(model.state.selectedIds.isEmpty || model.state.isSaving)
            .opacity(model.state.selectedIds.isEmpty ? 0.45 : 1)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 10)
        .background(MoaLogColor.surface)
        .overlay(alignment: .top) { Divider() }
    }

    private var payerGroups: [FixedPayerGroup] {
        let members = setup.members.sorted { $0.order < $1.order }
        return [FixedPayerGroup(order: nil, label: "공동", items: model.state.sheet.items.filter { $0.payerMemberOrder == nil })]
            + members.map { member in
                FixedPayerGroup(
                    order: member.order,
                    label: member.displayName,
                    items: model.state.sheet.items.filter { $0.payerMemberOrder?.int32Value == member.order }
                )
            }
    }
}

private struct FixedCostEditorSheet: View {
    @ObservedObject var model: FixedCostCheckViewModel
    let setup: LedgerSetup
    let draft: FixedCostDraft

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    VStack(alignment: .leading, spacing: 7) {
                        Text("결제 주체")
                            .font(MoaLogFont.semibold(12, relativeTo: .caption))
                        HStack(spacing: 7) {
                            payerButton("공동", order: nil)
                            ForEach(setup.members.sorted { $0.order < $1.order }, id: \.order) { member in
                                payerButton(member.displayName, order: member.order)
                            }
                        }
                    }

                    fixedCostField("항목명 *", value: Binding(get: { model.state.editor?.name ?? "" }, set: model.changeName), placeholder: "예: 주거비")
                    fixedCostField("월 금액 *", value: Binding(get: { model.state.editor?.amountInput ?? "" }, set: model.changeAmount), placeholder: "0", suffix: "원")

                    if let error = model.state.error {
                        Text(error)
                            .font(MoaLogFont.medium(12, relativeTo: .caption))
                            .foregroundStyle(MoaLogColor.error)
                            .accessibilityLabel("오류, \(error)")
                    }

                    if draft.id != 0 {
                        Button(role: .destructive, action: model.delete) {
                            Label("항목 삭제", systemImage: "trash")
                                .font(MoaLogFont.semibold(14))
                                .frame(maxWidth: .infinity, minHeight: 48)
                        }
                    }

                    Button(action: model.save) {
                        Text(model.state.isSaving ? "저장 중…" : "저장하기")
                            .font(MoaLogFont.semibold(15))
                            .foregroundStyle(.white)
                            .frame(maxWidth: .infinity, minHeight: 52)
                            .background(MoaLogColor.teal, in: RoundedRectangle(cornerRadius: 12))
                    }
                    .buttonStyle(.plain)
                    .disabled(model.state.isSaving)
                }
                .padding(20)
            }
            .background(MoaLogColor.homeCanvas)
            .navigationTitle("고정비 항목 편집")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("닫기", action: model.closeEditor) } }
        }
    }

    private func payerButton(_ label: String, order: Int32?) -> some View {
        let selected = model.state.editor?.payerMemberOrder?.int32Value == order
        return Button { model.selectPayer(order) } label: {
            HStack(spacing: 4) {
                Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                    .font(.system(size: 13))
                    .accessibilityHidden(true)
                Text(label).lineLimit(1).minimumScaleFactor(0.7)
            }
            .font(MoaLogFont.medium(12, relativeTo: .caption))
            .foregroundStyle(selected ? MoaLogColor.teal : MoaLogColor.mutedInk)
            .frame(maxWidth: .infinity, minHeight: 48)
            .background(selected ? MoaLogColor.sage : MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 9))
            .overlay(RoundedRectangle(cornerRadius: 9).stroke(selected ? MoaLogColor.teal : MoaLogColor.cardBorder))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

private struct FixedCostConflictView: View {
    @ObservedObject var model: FixedCostCheckViewModel
    let setup: LedgerSetup

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 12) {
                        if model.state.conflictPreview != nil {
                            Text("\(String(model.state.month.year))년 \(String(model.state.month.month))월 고정비")
                                .font(MoaLogFont.bold(20, relativeTo: .title3))
                            Text("선택한 달에 이미 등록된 항목이 있습니다. 적용 방식을 확인해 주세요.")
                                .font(MoaLogFont.regular(13, relativeTo: .caption))
                                .foregroundStyle(MoaLogColor.mutedInk)

                            ForEach(conflictGroups(), id: \.id) { group in
                                if !group.rows.isEmpty {
                                    Text(group.label)
                                        .font(MoaLogFont.semibold(12, relativeTo: .caption))
                                        .foregroundStyle(MoaLogColor.teal)
                                        .padding(.top, 4)
                                    ForEach(Array(group.rows.enumerated()), id: \.element.id) { _, row in
                                        HStack(spacing: 10) {
                                            Image(systemName: "checkmark.square.fill")
                                                .foregroundStyle(MoaLogColor.teal)
                                                .accessibilityHidden(true)
                                            VStack(alignment: .leading, spacing: 3) {
                                                Text(row.name)
                                                    .font(MoaLogFont.semibold(14))
                                                Text("고정비")
                                                    .font(MoaLogFont.regular(10, relativeTo: .caption2))
                                                    .foregroundStyle(MoaLogColor.mutedInk)
                                            }
                                            Spacer()
                                            VStack(alignment: .trailing, spacing: 4) {
                                                Text(row.amountWon.map { formatWon($0.int64Value) } ?? "미입력")
                                                    .font(MoaLogFont.bold(13, relativeTo: .caption))
                                                if row.hasExistingItem {
                                                    Text("기존 항목 있음")
                                                        .font(MoaLogFont.semibold(9, relativeTo: .caption2))
                                                        .foregroundStyle(MoaLogColor.error)
                                                        .padding(.horizontal, 7)
                                                        .padding(.vertical, 3)
                                                        .background(MoaLogColor.errorSurface.opacity(0.55), in: Capsule())
                                                }
                                            }
                                        }
                                        .padding(13)
                                        .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
                                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(MoaLogColor.cardBorder))
                                        .accessibilityElement(children: .combine)
                                    }
                                }
                            }
                        }
                    }
                    .padding(20)
                }

                VStack(spacing: 8) {
                    Text("충돌 항목이 있습니다")
                        .font(MoaLogFont.bold(15))
                    Text("선택한 항목을 어떻게 적용할까요?")
                        .font(MoaLogFont.regular(12, relativeTo: .caption))
                        .foregroundStyle(MoaLogColor.mutedInk)
                    Button(action: model.overwriteExisting) {
                        Text("선택한 금액으로 업데이트")
                            .font(MoaLogFont.semibold(15))
                            .foregroundStyle(.white)
                            .frame(maxWidth: .infinity, minHeight: 52)
                            .background(MoaLogColor.teal, in: RoundedRectangle(cornerRadius: 10))
                    }
                    .buttonStyle(.plain)
                    .disabled(model.state.isSaving)
                    Button("기존 금액 유지 (건너뛰기)", action: model.keepExisting)
                        .font(MoaLogFont.semibold(14))
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .disabled(model.state.isSaving)
                }
                .padding(12)
                .background(MoaLogColor.surface)
                .overlay(alignment: .top) { Divider() }
            }
            .background(MoaLogColor.homeCanvas.ignoresSafeArea())
            .navigationTitle("예정된 예산 적용")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(action: model.dismissConflict) { Image(systemName: "xmark") }
                        .accessibilityLabel("충돌 확인 닫기")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Text("2/3")
                        .font(MoaLogFont.medium(12, relativeTo: .caption))
                        .foregroundStyle(MoaLogColor.mutedInk)
                }
            }
        }
        .interactiveDismissDisabled(model.state.isSaving)
    }

    private func conflictGroups() -> [ConflictPayerGroup] {
        let rows = model.conflictRows()
        let members = setup.members.sorted { $0.order < $1.order }
        return [ConflictPayerGroup(id: "common", label: "공동", rows: rows.filter { $0.payerMemberOrder == nil })]
            + members.map { member in
                ConflictPayerGroup(
                    id: "member-\(member.order)",
                    label: member.displayName,
                    rows: rows.filter { $0.payerMemberOrder?.int32Value == member.order }
                )
            }
    }
}

private struct FixedPayerGroup: Identifiable {
    let order: Int32?
    let label: String
    let items: [FixedCostCheckItem]
    var id: String { order.map { "member-\($0)" } ?? "common" }
}

private struct ConflictPayerGroup: Identifiable {
    let id: String
    let label: String
    let rows: [IosFixedCostConflictRow]
}

private func fixedCostField(
    _ label: String,
    value: Binding<String>,
    placeholder: String,
    suffix: String? = nil
) -> some View {
    VStack(alignment: .leading, spacing: 6) {
        Text(label).font(MoaLogFont.semibold(12, relativeTo: .caption))
        HStack {
            TextField(placeholder, text: value)
                .font(MoaLogFont.regular(15))
                .keyboardType(suffix == nil ? .default : .numberPad)
                .accessibilityLabel(label)
            if let suffix {
                Text(suffix)
                    .font(MoaLogFont.medium(13, relativeTo: .caption))
                    .foregroundStyle(MoaLogColor.mutedInk)
            }
        }
        .padding(.horizontal, 12)
        .frame(minHeight: 52)
        .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(MoaLogColor.cardBorder))
    }
}

private func totalText(_ items: [FixedCostCheckItem], suffix: Bool = true) -> String {
    let values = items.compactMap { $0.amountWon?.int64Value }
    var total: Int64 = 0
    for value in values {
        let result = total.addingReportingOverflow(value)
        guard !result.overflow else { return "금액 범위 초과" }
        total = result.partialValue
    }
    let value = suffix ? formatWon(total) : formatNumberForFixedCost(total)
    return items.contains(where: { $0.amountWon == nil }) ? "\(value) + 미입력" : value
}

private func formatNumberForFixedCost(_ value: Int64) -> String {
    let formatter = NumberFormatter()
    formatter.numberStyle = .decimal
    formatter.groupingSeparator = ","
    return formatter.string(from: NSNumber(value: value)) ?? String(value)
}

private func previousFixedCostMonth(_ month: YearMonthKey) -> YearMonthKey {
    if month.year == 1900 && month.month == 1 { return month }
    return month.month == 1
        ? YearMonthKey(year: month.year - 1, month: 12)
        : YearMonthKey(year: month.year, month: month.month - 1)
}

private func nextFixedCostMonth(_ month: YearMonthKey) -> YearMonthKey {
    if month.year == 9999 && month.month == 12 { return month }
    return month.month == 12
        ? YearMonthKey(year: month.year + 1, month: 1)
        : YearMonthKey(year: month.year, month: month.month + 1)
}
