import Charts
import SharedKit
import SwiftUI

private final class MaintenanceStateCollector<State: AnyObject>: NSObject, FlowCollector {
    private let receive: (State) -> Void

    init(receive: @escaping (State) -> Void) {
        self.receive = receive
    }

    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        if let state = value as? State { receive(state) }
        completionHandler(nil)
    }
}

@MainActor
private final class MaintenanceOverviewViewModel: ObservableObject {
    @Published private(set) var state: MaintenanceOverviewUiState
    private let holder: MaintenanceOverviewStateHolder
    private var collector: MaintenanceStateCollector<MaintenanceOverviewUiState>?

    init(dependencies: IosDependencies, initialMonth: YearMonthKey) {
        let holder = dependencies.maintenanceOverviewStateHolder(args: MaintenanceOverviewArgs(initialMonth: initialMonth))
        self.holder = holder
        state = holder.state.value as! MaintenanceOverviewUiState
        let collector = MaintenanceStateCollector<MaintenanceOverviewUiState> { [weak self] value in
            DispatchQueue.main.async { self?.state = value }
        }
        self.collector = collector
        holder.state.collect(collector: collector) { _ in }
    }

    deinit { holder.close() }
    func previousMonth() { holder.onAction(action: MaintenanceOverviewActionPreviousMonth.shared) }
    func nextMonth() { holder.onAction(action: MaintenanceOverviewActionNextMonth.shared) }
    func selectDetail() { holder.onAction(action: MaintenanceOverviewActionSelectTab(tab: .detail)) }
    func selectComparison() { holder.onAction(action: MaintenanceOverviewActionSelectTab(tab: .comparison)) }
    func retry() { holder.onAction(action: MaintenanceOverviewActionRetry.shared) }
}

@MainActor
private final class MaintenanceEditorViewModel: ObservableObject {
    @Published private(set) var state: MaintenanceEditorUiState
    private let holder: MaintenanceEditorStateHolder
    private var collector: MaintenanceStateCollector<MaintenanceEditorUiState>?

    init(dependencies: IosDependencies, month: YearMonthKey) {
        let holder = dependencies.maintenanceEditorStateHolder(args: MaintenanceEditorArgs(month: month))
        self.holder = holder
        state = holder.state.value as! MaintenanceEditorUiState
        let collector = MaintenanceStateCollector<MaintenanceEditorUiState> { [weak self] value in
            DispatchQueue.main.async { self?.state = value }
        }
        self.collector = collector
        holder.state.collect(collector: collector) { _ in }
    }

    deinit { holder.close() }
    func change(_ field: MaintenanceEditorField, value: String) {
        holder.onAction(action: MaintenanceEditorActionAmountChanged(key: field.key, value: value))
    }
    func previousMonth() { changeMonth(by: -1) }
    func nextMonth() { changeMonth(by: 1) }
    func save() { holder.onAction(action: MaintenanceEditorActionSave.shared) }
    func retry() { holder.onAction(action: MaintenanceEditorActionRetry.shared) }

    private func changeMonth(by offset: Int32) {
        let current = state.month
        let index = current.year * 12 + current.month - 1 + offset
        guard index >= 1900 * 12, index <= 9999 * 12 + 11 else { return }
        holder.onAction(action: MaintenanceEditorActionMonthChanged(
            month: YearMonthKey(year: index / 12, month: index % 12 + 1)
        ))
    }
}

private struct MaintenanceEditorRoute: Identifiable {
    let month: YearMonthKey
    var id: String { "\(month.year)-\(month.month)" }
}

struct MaintenanceView: View {
    @StateObject private var model: MaintenanceOverviewViewModel
    @State private var editorRoute: MaintenanceEditorRoute?
    private let dependencies: IosDependencies
    private let onClose: () -> Void

    init(dependencies: IosDependencies, initialMonth: YearMonthKey, onClose: @escaping () -> Void) {
        self.dependencies = dependencies
        self.onClose = onClose
        _model = StateObject(wrappedValue: MaintenanceOverviewViewModel(dependencies: dependencies, initialMonth: initialMonth))
    }

    var body: some View {
        NavigationStack {
            ZStack(alignment: .bottom) {
                MoaLogColor.linen.ignoresSafeArea()
                if model.state.isLoading {
                    ProgressView("관리비를 불러오는 중이에요").font(MoaLogFont.regular(13)).tint(MoaLogColor.teal)
                } else if let error = model.state.loadError {
                    loadError(error)
                } else {
                    ScrollView {
                        LazyVStack(spacing: 16) {
                            monthSelector
                            totalCard
                            tabSelector
                            if model.state.selectedTab == .detail { detailContent } else { comparisonContent }
                        }
                        .padding(.horizontal, 20).padding(.top, 12).padding(.bottom, 96)
                        .frame(maxWidth: 520).frame(maxWidth: .infinity)
                    }
                    editBar
                }
            }
            .navigationTitle("관리비 현황")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(action: onClose) { Image(systemName: "chevron.left").frame(width: 44, height: 44) }
                        .accessibilityLabel("더보기로 돌아가기")
                }
            }
        }
        .tint(MoaLogColor.teal)
        .fullScreenCover(item: $editorRoute) { route in
            MaintenanceEditorView(dependencies: dependencies, initialMonth: route.month, onClose: { editorRoute = nil }, onSaved: { editorRoute = nil })
        }
    }

    private var monthSelector: some View {
        HStack(spacing: 4) {
            monthButton("이전 청구월", symbol: "chevron.left", disabled: isMinimumMonth(model.state.month), action: model.previousMonth)
            Spacer()
            VStack(spacing: 2) {
                Text("\(model.state.month.year)년 \(model.state.month.month)월").font(MoaLogFont.bold(19, relativeTo: .title3))
                Text("청구월 기준").font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
            }.accessibilityElement(children: .combine)
            Spacer()
            monthButton("다음 청구월", symbol: "chevron.right", disabled: isMaximumMonth(model.state.month), action: model.nextMonth)
        }
        .padding(.horizontal, 8).frame(minHeight: 58).maintenanceCard()
    }

    private func monthButton(_ label: String, symbol: String, disabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: symbol).frame(width: 44, height: 44) }
            .buttonStyle(.plain).disabled(disabled).accessibilityLabel(label)
    }

    private var totalCard: some View {
        VStack(spacing: 7) {
            Text("이번 달 관리비 총액").font(MoaLogFont.medium(12, relativeTo: .caption)).foregroundStyle(MoaLogColor.mutedInk)
            Text(model.state.totalWon.map { formatWon($0.int64Value) } ?? "미입력")
                .font(MoaLogFont.bold(30, relativeTo: .largeTitle))
                .foregroundStyle(model.state.totalWon == nil ? MoaLogColor.outline : MoaLogColor.ink)
                .minimumScaleFactor(0.6).lineLimit(1)
            Text("21개 중 \(model.state.enteredCount)개 입력").font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.outline)
        }
        .padding(18).frame(maxWidth: .infinity).maintenanceCard().accessibilityElement(children: .combine)
    }

    private var tabSelector: some View {
        HStack(spacing: 0) {
            tabButton("상세", selected: model.state.selectedTab == .detail, action: model.selectDetail)
            tabButton("월별 비교", selected: model.state.selectedTab == .comparison, action: model.selectComparison)
        }
        .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(MoaLogColor.cardBorder))
    }

    private func tabButton(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(MoaLogFont.semibold(13, relativeTo: .caption))
                .foregroundStyle(selected ? Color.white : MoaLogColor.mutedInk)
                .frame(maxWidth: .infinity, minHeight: 48)
                .background(selected ? MoaLogColor.teal : Color.clear, in: RoundedRectangle(cornerRadius: 10)).padding(3)
        }
        .buttonStyle(.plain).accessibilityAddTraits(selected ? .isSelected : [])
    }

    private var detailContent: some View {
        VStack(spacing: 12) {
            MaintenanceDetailGroup(title: "공용·관리비", rows: Array(model.state.rows.prefix(11)), initiallyExpanded: true)
            MaintenanceDetailGroup(title: "세대 사용료", rows: Array(model.state.rows.dropFirst(11).prefix(8)), initiallyExpanded: false)
            MaintenanceDetailGroup(title: "차감 및 기타", rows: Array(model.state.rows.dropFirst(19).prefix(2)), initiallyExpanded: false)
        }
    }

    private var comparisonContent: some View {
        VStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 12) {
                Text("최근 \(model.state.comparisonPoints.count)개월 관리비").font(MoaLogFont.bold(15, relativeTo: .headline))
                Chart(model.state.comparisonPoints, id: \.month) { point in
                    if let total = point.totalWon?.int64Value {
                        BarMark(x: .value("월", "\(point.month.month)월"), y: .value("관리비", total))
                            .foregroundStyle(point.month == model.state.month ? MoaLogColor.teal : MoaLogColor.secondaryContainer)
                            .cornerRadius(4)
                    }
                }
                .chartYAxis(.hidden).frame(height: 170)
                .accessibilityLabel(comparisonAccessibilityLabel)
                .accessibilityValue("아래 월별 목록에서 금액과 전월 대비 변화를 확인할 수 있습니다")
            }
            .padding(16).maintenanceCard()

            HStack {
                Text("월별 상세 내역").font(MoaLogFont.semibold(13))
                Spacer()
                Text(comparisonRangeLabel).font(MoaLogFont.regular(9, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
            }

            VStack(spacing: 0) {
                ForEach(Array(model.state.comparisonPoints.enumerated().reversed()), id: \.element.month) { index, point in
                    comparisonRow(point, previous: index > 0 ? model.state.comparisonPoints[index - 1] : nil)
                    if point.month != model.state.comparisonPoints.first?.month { Divider().padding(.leading, 68) }
                }
            }.maintenanceCard()
        }
    }

    private func comparisonRow(_ point: MaintenanceComparisonPoint, previous: MaintenanceComparisonPoint?) -> some View {
        let total = point.totalWon?.int64Value
        let previousTotal = previous?.totalWon?.int64Value
        let delta = total.flatMap { current in
            previousTotal.flatMap { previous in
                let result = current.subtractingReportingOverflow(previous)
                return result.overflow ? nil : result.partialValue
            }
        }
        return HStack(spacing: 12) {
            Text("\(point.month.month)월").font(MoaLogFont.bold(11, relativeTo: .caption)).frame(width: 42, height: 42)
                .background(point.month == model.state.month ? MoaLogColor.secondaryContainer : MoaLogColor.surfaceLow, in: Circle())
            VStack(alignment: .leading, spacing: 3) {
                Text("\(point.month.year)년").font(MoaLogFont.regular(9, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
                Text(total.map(formatWon) ?? "미입력").font(MoaLogFont.semibold(13))
            }
            Spacer()
            Text(delta.map(signedMaintenanceWon) ?? "비교 불가").font(MoaLogFont.semibold(10, relativeTo: .caption2))
                .foregroundStyle(delta.map { $0 > 0 ? MoaLogColor.error : MoaLogColor.teal } ?? MoaLogColor.outline)
        }
        .padding(.horizontal, 14).frame(minHeight: 66).accessibilityElement(children: .combine)
    }

    private var editBar: some View {
        VStack(spacing: 0) {
            Divider()
            Button { editorRoute = MaintenanceEditorRoute(month: model.state.month) } label: {
                Label(model.state.enteredCount == 0 ? "이번 달 관리비 입력" : "관리비 수정", systemImage: "square.and.pencil")
                    .font(MoaLogFont.bold(15)).foregroundStyle(.white).frame(maxWidth: .infinity, minHeight: 52)
                    .background(MoaLogColor.teal, in: RoundedRectangle(cornerRadius: 12))
            }
            .buttonStyle(.plain).padding(.horizontal, 20).padding(.vertical, 12)
        }.background(MoaLogColor.surface.opacity(0.98))
    }

    private func loadError(_ message: String) -> some View {
        VStack(spacing: 14) {
            Image(systemName: "exclamationmark.circle").font(.system(size: 30)).foregroundStyle(MoaLogColor.error)
            Text(message).font(MoaLogFont.medium(13)).foregroundStyle(MoaLogColor.error).multilineTextAlignment(.center)
            Button("다시 시도", action: model.retry).font(MoaLogFont.semibold(14)).frame(minHeight: 48)
        }.padding(24)
    }

    private var comparisonRangeLabel: String {
        String(format: "%04d.%02d ~ %04d.%02d", model.state.comparisonStart.year, model.state.comparisonStart.month, model.state.comparisonEnd.year, model.state.comparisonEnd.month)
    }

    private var comparisonAccessibilityLabel: String {
        model.state.comparisonPoints.map { point in
            "\(point.month.year)년 \(point.month.month)월 \(point.totalWon.map { formatWon($0.int64Value) } ?? "미입력")"
        }.joined(separator: ", ")
    }
}

private struct MaintenanceDetailGroup: View {
    let title: String
    let rows: [MaintenanceOverviewRow]
    @State private var expanded: Bool

    init(title: String, rows: [MaintenanceOverviewRow], initiallyExpanded: Bool) {
        self.title = title
        self.rows = rows
        _expanded = State(initialValue: initiallyExpanded)
    }

    var body: some View {
        VStack(spacing: 0) {
            Button { withAnimation(.easeInOut(duration: 0.2)) { expanded.toggle() } } label: {
                HStack(spacing: 10) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("\(title) (\(rows.count)건)").font(MoaLogFont.semibold(14))
                        Text(subtotal.map { "소계 \(formatWon($0))" } ?? "모두 미입력")
                            .font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
                    }
                    Spacer()
                    Image(systemName: expanded ? "chevron.up" : "chevron.down").foregroundStyle(MoaLogColor.outline)
                }.padding(16).frame(minHeight: 64).contentShape(Rectangle())
            }
            .buttonStyle(.plain).accessibilityLabel("\(title), \(expanded ? "접기" : "펼치기")")

            if expanded {
                Divider()
                VStack(spacing: 0) {
                    ForEach(Array(rows.enumerated()), id: \.element.key) { index, row in
                        HStack {
                            Text(row.label).font(MoaLogFont.regular(12)).foregroundStyle(MoaLogColor.mutedInk)
                            Spacer()
                            Text(row.amountWon.map { formatWon($0.int64Value) } ?? "미입력").font(MoaLogFont.semibold(12))
                                .foregroundStyle(row.amountWon == nil ? MoaLogColor.outline : (row.amountWon!.int64Value < 0 ? MoaLogColor.error : MoaLogColor.ink))
                        }.frame(minHeight: 46)
                        if index < rows.count - 1 { Divider() }
                    }
                }.padding(.horizontal, 16)
            }
        }.maintenanceCard()
    }

    private var subtotal: Int64? {
        let amounts = rows.compactMap { $0.amountWon?.int64Value }
        return safeMaintenanceSum(amounts)
    }
}

private struct MaintenanceEditorView: View {
    @StateObject private var model: MaintenanceEditorViewModel
    @State private var commonExpanded = true
    @State private var householdExpanded = false
    @State private var deductionExpanded = false
    let onClose: () -> Void
    let onSaved: () -> Void

    init(dependencies: IosDependencies, initialMonth: YearMonthKey, onClose: @escaping () -> Void, onSaved: @escaping () -> Void) {
        self.onClose = onClose
        self.onSaved = onSaved
        _model = StateObject(wrappedValue: MaintenanceEditorViewModel(dependencies: dependencies, month: initialMonth))
    }

    var body: some View {
        NavigationStack {
            ZStack(alignment: .bottom) {
                MoaLogColor.linen.ignoresSafeArea()
                if model.state.isLoading {
                    ProgressView("관리비 입력을 준비하는 중이에요").font(MoaLogFont.regular(13)).tint(MoaLogColor.teal)
                } else if let error = model.state.loadError {
                    VStack(spacing: 14) {
                        Text(error).font(MoaLogFont.medium(13)).foregroundStyle(MoaLogColor.error).multilineTextAlignment(.center)
                        Button("다시 시도", action: model.retry).frame(minHeight: 48)
                    }.padding(24)
                } else {
                    ScrollView {
                        LazyVStack(spacing: 14) {
                            editorMonthSelector
                            inputSummary
                            editorGroup("공용·관리비", fields: Array(model.state.fields.prefix(11)), expanded: $commonExpanded)
                            editorGroup("세대 사용료", fields: Array(model.state.fields.dropFirst(11).prefix(8)), expanded: $householdExpanded)
                            editorGroup("차감 및 기타", fields: Array(model.state.fields.dropFirst(19).prefix(2)), expanded: $deductionExpanded)
                            if let error = model.state.totalError ?? model.state.persistenceError {
                                Label(error, systemImage: "exclamationmark.circle.fill").font(MoaLogFont.medium(11, relativeTo: .caption))
                                    .foregroundStyle(MoaLogColor.error).frame(maxWidth: .infinity, alignment: .leading)
                                    .accessibilityLabel("오류, \(error)")
                            }
                        }
                        .padding(.horizontal, 20).padding(.top, 12).padding(.bottom, 108)
                        .frame(maxWidth: 520).frame(maxWidth: .infinity)
                    }
                    saveBar
                }
            }
            .navigationTitle("관리비 입력")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(action: onClose) { Image(systemName: "chevron.left").frame(width: 44, height: 44) }
                        .accessibilityLabel("관리비 현황으로 돌아가기")
                }
            }
        }
        .tint(MoaLogColor.teal)
        .onChange(of: model.state.completed) { _, completed in if completed { onSaved() } }
    }

    private var editorMonthSelector: some View {
        HStack {
            Button(action: model.previousMonth) { Image(systemName: "chevron.left").frame(width: 44, height: 44) }
                .disabled(isMinimumMonth(model.state.month)).accessibilityLabel("이전 청구월")
            Spacer()
            Text("\(model.state.month.year)년 \(model.state.month.month)월").font(MoaLogFont.bold(18, relativeTo: .title3))
            Spacer()
            Button(action: model.nextMonth) { Image(systemName: "chevron.right").frame(width: 44, height: 44) }
                .disabled(isMaximumMonth(model.state.month)).accessibilityLabel("다음 청구월")
        }.padding(.horizontal, 8).frame(minHeight: 56).maintenanceCard()
    }

    private var inputSummary: some View {
        VStack(alignment: .leading, spacing: 7) {
            Text("입력 중 합계").font(MoaLogFont.medium(11, relativeTo: .caption)).foregroundStyle(MoaLogColor.mutedInk)
            HStack(alignment: .firstTextBaseline) {
                Text(model.state.calculatedTotalWon.map { formatWon($0.int64Value) } ?? "미입력")
                    .font(MoaLogFont.bold(26, relativeTo: .title)).foregroundStyle(MoaLogColor.teal).minimumScaleFactor(0.6).lineLimit(1)
                Spacer()
                Text("21개 중 \(model.state.enteredCount)개").font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.outline)
            }
        }.padding(16).maintenanceCard().accessibilityElement(children: .combine)
    }

    private func editorGroup(_ title: String, fields: [MaintenanceEditorField], expanded: Binding<Bool>) -> some View {
        VStack(spacing: 0) {
            Button { withAnimation(.easeInOut(duration: 0.2)) { expanded.wrappedValue.toggle() } } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(title).font(MoaLogFont.semibold(14))
                        Text(editorSubtotal(fields).map { "소계 \(formatWon($0))" } ?? "모두 미입력")
                            .font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
                    }
                    Spacer()
                    Image(systemName: expanded.wrappedValue ? "chevron.up" : "chevron.down").foregroundStyle(MoaLogColor.outline)
                }.padding(16).frame(minHeight: 64).contentShape(Rectangle())
            }.buttonStyle(.plain)
            if expanded.wrappedValue {
                Divider()
                VStack(spacing: 14) {
                    ForEach(Array(fields.enumerated()), id: \.element.key) { _, field in maintenanceField(field) }
                }.padding(16)
            }
        }.maintenanceCard()
    }

    private func maintenanceField(_ field: MaintenanceEditorField) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(field.label).font(MoaLogFont.semibold(12, relativeTo: .caption))
                Spacer()
                if field.label == "관리비차감" {
                    Text("마이너스(-) 입력 가능").font(MoaLogFont.regular(9, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
                }
            }
            HStack {
                TextField("금액 입력", text: Binding(get: { field.text }, set: { model.change(field, value: $0) }))
                    .font(MoaLogFont.medium(14)).keyboardType(field.label == "관리비차감" ? .numbersAndPunctuation : .numberPad)
                    .multilineTextAlignment(.trailing)
                Text("원").font(MoaLogFont.medium(13)).foregroundStyle(MoaLogColor.mutedInk)
            }
            .padding(.horizontal, 12).frame(minHeight: 50).background(MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 11))
            .overlay(RoundedRectangle(cornerRadius: 11).stroke(field.error == nil ? MoaLogColor.cardBorder : MoaLogColor.error))
            .accessibilityElement(children: .combine).accessibilityLabel(field.label)
            .accessibilityValue(field.text.isEmpty ? "미입력" : "\(field.text)원")
            if let error = field.error { Text(error).font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.error) }
        }
    }

    private var saveBar: some View {
        VStack(spacing: 0) {
            Divider()
            HStack(spacing: 14) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("총 관리비").font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
                    Text(model.state.calculatedTotalWon.map { formatWon($0.int64Value) } ?? "미입력")
                        .font(MoaLogFont.bold(15)).lineLimit(1).minimumScaleFactor(0.7)
                }.frame(minWidth: 105, alignment: .leading)
                Button(action: model.save) {
                    Group {
                        if model.state.isSaving { ProgressView().tint(.white) } else { Text("저장").font(MoaLogFont.bold(15)) }
                    }
                    .foregroundStyle(.white).frame(maxWidth: .infinity, minHeight: 52)
                    .background(MoaLogColor.teal, in: RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain).disabled(model.state.isSaving)
                .accessibilityLabel(model.state.isSaving ? "저장 중" : "관리비 저장")
            }.padding(.horizontal, 20).padding(.vertical, 12)
        }.background(MoaLogColor.surface.opacity(0.98))
    }

    private func editorSubtotal(_ fields: [MaintenanceEditorField]) -> Int64? {
        let amounts = fields.compactMap { Int64($0.text.replacingOccurrences(of: ",", with: "").trimmingCharacters(in: .whitespaces)) }
        return safeMaintenanceSum(amounts)
    }
}

private func signedMaintenanceWon(_ value: Int64) -> String { value > 0 ? "+\(formatWon(value))" : formatWon(value) }

private func safeMaintenanceSum(_ values: [Int64]) -> Int64? {
    guard !values.isEmpty else { return nil }
    var total: Int64 = 0
    for value in values {
        let result = total.addingReportingOverflow(value)
        guard !result.overflow else { return nil }
        total = result.partialValue
    }
    return total
}

private extension View {
    func maintenanceCard() -> some View {
        background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(MoaLogColor.cardBorder))
    }
}
