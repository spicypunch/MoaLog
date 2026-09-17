import SharedKit
import SwiftUI

@MainActor
final class RecordsViewModel: ObservableObject {
    @Published private(set) var state: RecordsUiState
    private let store: IosRecordsStore
    private var observation: IosObservation?

    init(dependencies: IosDependencies) {
        let store = dependencies.recordsStore()
        self.store = store
        state = store.currentState
        observation = store.observe { [weak self] state in
            DispatchQueue.main.async { self?.state = state }
        }
    }

    deinit { observation?.cancel(); store.close() }
    func previousMonth() { store.previousMonth() }
    func nextMonth() { store.nextMonth() }
    func retry() { store.retry() }
    func clearFilters() { store.clearFilters() }
    func apply(categoryIds: [String], overspentOnly: Bool, oldestFirst: Bool) {
        store.applyFilters(categoryIds: categoryIds, overspentOnly: overspentOnly, oldestFirst: oldestFirst)
    }
    func apply(_ request: RecordsSelectionRequest) {
        store.applySelection(
            year: request.month.year,
            month: request.month.month,
            categoryId: request.categoryId,
            overspentOnly: request.section.overspentOnly
        )
    }
    func restore(_ restoration: RecordsRestoration) {
        store.restoreSelection(
            year: Int32(restoration.year),
            month: Int32(restoration.month),
            categoryIds: restoration.categoryIds,
            overspentOnly: restoration.overspentOnly,
            oldestFirst: restoration.oldestFirst
        )
    }
}

struct RecordsRestoration: Codable, Equatable {
    let year: Int
    let month: Int
    let categoryIds: [String]
    let overspentOnly: Bool
    let oldestFirst: Bool

    var encoded: String { (try? JSONEncoder().encode(self)).flatMap { String(data: $0, encoding: .utf8) } ?? "" }
    static func decode(_ raw: String) -> Self? {
        guard let data = raw.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(Self.self, from: data)
    }
}

struct RecordsTabView: View {
    @StateObject private var model: RecordsViewModel
    @State private var filterPresented = false
    @State private var appliedRequestId: UUID?
    @State private var restored = false
    @SceneStorage("moalog.records.restore.v1") private var persistedRestoration = ""

    let selectionRequest: RecordsSelectionRequest?
    let onSelectionRequestConsumed: (UUID) -> Void
    let onAdd: (YearMonthKey) -> Void
    let onEdit: (ExpenseRecord, YearMonthKey) -> Void

    init(
        dependencies: IosDependencies,
        selectionRequest: RecordsSelectionRequest?,
        onSelectionRequestConsumed: @escaping (UUID) -> Void,
        onAdd: @escaping (YearMonthKey) -> Void,
        onEdit: @escaping (ExpenseRecord, YearMonthKey) -> Void
    ) {
        _model = StateObject(wrappedValue: RecordsViewModel(dependencies: dependencies))
        self.selectionRequest = selectionRequest
        self.onSelectionRequestConsumed = onSelectionRequestConsumed
        self.onAdd = onAdd
        self.onEdit = onEdit
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar
            ScrollView {
                LazyVStack(spacing: 14) {
                    monthSelector
                    if model.state.isLoading {
                        loadingContent
                    } else if let error = model.state.loadError {
                        errorContent(error)
                    } else {
                        if !model.state.visibleRecords.isEmpty { summaryCard }
                        if !model.state.records.isEmpty || model.state.filters.hasSubtotalFilter { filterBar }
                        recordsContent
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 10)
                .padding(.bottom, 18)
                .frame(maxWidth: 480)
                .frame(maxWidth: .infinity)
            }
        }
        .background(MoaLogColor.homeCanvas)
        .sheet(isPresented: $filterPresented) {
            RecordsFilterSheet(state: model.state) { categories, overspent, oldest in
                model.apply(categoryIds: categories, overspentOnly: overspent, oldestFirst: oldest)
                filterPresented = false
            } onReset: {
                model.clearFilters()
                filterPresented = false
            }
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
        }
        .onAppear(perform: applyInitialState)
        .onChange(of: selectionRequest?.id) { _, _ in applySelectionRequest() }
        .onChange(of: restorationSignature) { _, _ in persistState() }
    }

    private var topBar: some View {
        HStack {
            Text("모아로그")
                .font(MoaLogFont.bold(17, relativeTo: .headline))
                .foregroundStyle(MoaLogColor.teal)
            Spacer()
            Text("지출 기록")
                .font(MoaLogFont.semibold(13, relativeTo: .caption))
                .foregroundStyle(MoaLogColor.mutedInk)
        }
        .padding(.horizontal, 18)
        .frame(minHeight: 48)
        .background(MoaLogColor.homeCanvas.opacity(0.97))
        .overlay(alignment: .bottom) { Rectangle().fill(MoaLogColor.homeBorder).frame(height: 1) }
    }

    private var monthSelector: some View {
        HStack(spacing: 8) {
            monthButton("이전 달", symbol: "chevron.left", action: model.previousMonth)
            Spacer()
            VStack(spacing: 2) {
                Text(String(model.state.month.year))
                    .font(MoaLogFont.regular(10, relativeTo: .caption2))
                    .foregroundStyle(MoaLogColor.mutedInk)
                Text("\(model.state.month.month)월")
                    .font(MoaLogFont.bold(22, relativeTo: .title2))
                    .foregroundStyle(MoaLogColor.ink)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(model.state.month.year)년 \(model.state.month.month)월")
            Spacer()
            monthButton("다음 달", symbol: "chevron.right", action: model.nextMonth)
        }
        .padding(.horizontal, 8)
        .frame(minHeight: 64)
        .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(MoaLogColor.cardBorder))
    }

    private func monthButton(_ label: String, symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).frame(width: 44, height: 44).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    private var summaryCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("\(model.state.month.month)월 변동지출 총액")
                    .font(MoaLogFont.medium(12, relativeTo: .caption))
                    .foregroundStyle(MoaLogColor.mutedInk)
                Spacer()
                Text("총 \(model.state.records.count)건")
                    .font(MoaLogFont.medium(10, relativeTo: .caption2))
                    .foregroundStyle(MoaLogColor.mutedInk)
            }
            Text(model.state.hasFullMonthTotalOverflow ? "금액 범위 초과" : formatWon(model.state.fullMonthTotalWon))
                .font(MoaLogFont.bold(28, relativeTo: .title))
                .foregroundStyle(MoaLogColor.ink)
                .minimumScaleFactor(0.7)
                .lineLimit(1)
            if model.state.hasFullMonthOverspentOverflow || model.state.fullMonthOverspentWon > 0 {
                Label("사용자가 과소비로 표시한 지출 \(model.state.hasFullMonthOverspentOverflow ? "금액 범위 초과" : formatWon(model.state.fullMonthOverspentWon))", systemImage: "exclamationmark.triangle.fill")
                    .font(MoaLogFont.medium(11, relativeTo: .caption))
                    .foregroundStyle(MoaLogColor.error)
            }
            if let budget = model.state.plannedVariableExpenseWon?.int64Value {
                VStack(spacing: 6) {
                    GeometryReader { proxy in
                        Capsule().fill(MoaLogColor.homeBorder)
                            .overlay(alignment: .leading) {
                                Capsule().fill(MoaLogColor.teal).frame(width: proxy.size.width * budgetProgress)
                            }
                    }.frame(height: 6)
                    HStack {
                        Text("예산 \(formatWon(budget))")
                        Spacer()
                        Text(model.state.budgetUsagePercent.map { "사용률 \(Int($0.doubleValue.rounded()))%" } ?? "사용률 계산 불가")
                    }.font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
                }
            }
            if let comparison = model.state.comparison, shouldShowComparison(comparison) {
                comparisonText(comparison)
                    .font(MoaLogFont.medium(10, relativeTo: .caption2))
                    .foregroundStyle(comparison.selectedSpentMore ? MoaLogColor.error : MoaLogColor.teal)
            }
        }
        .padding(16)
        .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(MoaLogColor.cardBorder))
        .accessibilityElement(children: .combine)
    }

    private var filterBar: some View {
        VStack(spacing: 10) {
            HStack(spacing: 8) {
                if model.state.filters.overspentOnly {
                    filterChip("과소비", error: true)
                }
                ForEach(selectedCategoryNames, id: \.self) { filterChip($0, error: false) }
                Button { filterPresented = true } label: {
                    Label("상세히 조회", systemImage: "slider.horizontal.3")
                        .font(MoaLogFont.semibold(11, relativeTo: .caption2))
                        .frame(minHeight: 44)
                        .padding(.horizontal, 10)
                        .background(MoaLogColor.surface, in: Capsule())
                        .overlay(Capsule().stroke(MoaLogColor.cardBorder))
                }
                .buttonStyle(.plain)
                Spacer(minLength: 0)
            }
            .scrollClipDisabled()

            HStack {
                Text(model.state.filters.hasSubtotalFilter ? "조건에 맞는 기록 \(model.state.visibleRecords.count)건" : "등록된 기록 \(model.state.visibleRecords.count)건")
                    .font(MoaLogFont.regular(10, relativeTo: .caption2))
                    .foregroundStyle(MoaLogColor.mutedInk)
                Spacer()
                Button { filterPresented = true } label: {
                    Text(model.state.filters.sort == .oldest ? "오래된순" : "최신순")
                        .font(MoaLogFont.semibold(11, relativeTo: .caption2))
                        .frame(minHeight: 44)
                }
                .buttonStyle(.plain)
                .accessibilityHint("필터 및 정렬 화면을 엽니다")
            }
            if model.state.filters.hasSubtotalFilter {
                HStack {
                    Text("필터 합계")
                    Spacer()
                    Text(model.state.hasFilteredSubtotalOverflow ? "금액 범위 초과" : formatWon(model.state.filteredSubtotalWon))
                        .foregroundStyle(model.state.hasFilteredSubtotalOverflow ? MoaLogColor.error : MoaLogColor.ink)
                }
                .font(MoaLogFont.semibold(11, relativeTo: .caption2))
                .accessibilityElement(children: .combine)
            }
        }
    }

    private func filterChip(_ text: String, error: Bool) -> some View {
        Text(text)
            .font(MoaLogFont.semibold(10, relativeTo: .caption2))
            .foregroundStyle(error ? MoaLogColor.error : MoaLogColor.teal)
            .padding(.horizontal, 10)
            .frame(minHeight: 36)
            .background((error ? MoaLogColor.error : MoaLogColor.teal).opacity(0.08), in: Capsule())
    }

    @ViewBuilder private var recordsContent: some View {
        if model.state.visibleRecords.isEmpty {
            emptyContent(filtered: model.state.filters.hasSubtotalFilter)
        } else {
            ForEach(groupedRecords, id: \.key) { group in
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text(group.title).font(MoaLogFont.bold(13, relativeTo: .caption))
                        Spacer()
                        Text(group.total.map(formatWon) ?? "금액 범위 초과").font(MoaLogFont.semibold(11, relativeTo: .caption2))
                    }
                    ForEach(group.records, id: \.id) { record in
                        Button { onEdit(record, model.state.month) } label: { recordRow(record) }
                            .buttonStyle(.plain)
                            .accessibilityHint("지출 기록을 편집합니다")
                    }
                }
            }
            addButton(title: "지출 추가하기")
        }
    }

    private func recordRow(_ record: ExpenseRecord) -> some View {
        HStack(spacing: 12) {
            Image(systemName: categorySymbol(record.categoryId))
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(MoaLogColor.teal)
                .frame(width: 40, height: 40)
                .background(MoaLogColor.sage, in: RoundedRectangle(cornerRadius: 12))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(record.categoryName).font(MoaLogFont.semibold(12, relativeTo: .caption))
                Text(record.detail?.isEmpty == false ? record.detail! : "세부 내용 없음")
                    .font(MoaLogFont.regular(11, relativeTo: .caption2))
                    .foregroundStyle(MoaLogColor.mutedInk)
                    .lineLimit(1)
                if let metadata = recordMonthMetadata(record) {
                    Text(metadata)
                        .font(MoaLogFont.regular(9, relativeTo: .caption2))
                        .foregroundStyle(MoaLogColor.mutedInk)
                        .lineLimit(1)
                }
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 4) {
                Text(formatWon(record.amountWon)).font(MoaLogFont.bold(12, relativeTo: .caption))
                if record.overspent {
                    Text("과소비")
                        .font(MoaLogFont.bold(9, relativeTo: .caption2))
                        .foregroundStyle(MoaLogColor.error)
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(MoaLogColor.error.opacity(0.08), in: Capsule())
                }
            }
        }
        .padding(.horizontal, 12)
        .frame(minHeight: 68)
        .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(MoaLogColor.cardBorder))
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    private func emptyContent(filtered: Bool) -> some View {
        VStack(spacing: 14) {
            Image(systemName: filtered ? "line.3.horizontal.decrease.circle" : "list.clipboard")
                .font(.system(size: 36, weight: .medium))
                .foregroundStyle(MoaLogColor.mutedInk)
                .frame(width: 92, height: 92)
                .background(MoaLogColor.homeBorder.opacity(0.7), in: Circle())
                .accessibilityHidden(true)
            Text(filtered ? "조건에 맞는 지출 내역이 없습니다" : "이번 달 지출 내역이 없습니다.")
                .font(MoaLogFont.bold(16, relativeTo: .headline))
            Text(filtered ? "설정하신 필터와 과소비 필터에 해당하는 기록이 이번 달에는 없네요." : "첫 지출을 기록하고 두 사람만의 따뜻한 가계부를 시작해보세요.")
                .font(MoaLogFont.regular(12, relativeTo: .caption))
                .foregroundStyle(MoaLogColor.mutedInk)
                .multilineTextAlignment(.center)
            addButton(title: filtered ? "지출 추가하기" : "첫 지출 기록하기")
            if filtered {
                Button(action: model.clearFilters) {
                    Label("필터 초기화", systemImage: "arrow.counterclockwise")
                        .font(MoaLogFont.semibold(13, relativeTo: .caption))
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(MoaLogColor.secondaryContainer, in: RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.vertical, 36)
        .padding(.horizontal, 12)
    }

    private func addButton(title: String) -> some View {
        Button { onAdd(model.state.month) } label: {
            Label(title, systemImage: "plus")
                .font(MoaLogFont.semibold(14))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, minHeight: 52)
                .background(MoaLogColor.teal, in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
    }

    private var loadingContent: some View {
        VStack(spacing: 12) {
            ProgressView().tint(MoaLogColor.teal)
            Text("기록을 불러오는 중이에요").font(MoaLogFont.medium(13, relativeTo: .caption))
        }.frame(maxWidth: .infinity, minHeight: 320)
    }

    private func errorContent(_ message: String) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle").font(.system(size: 30)).foregroundStyle(MoaLogColor.error)
            Text(message).font(MoaLogFont.medium(13, relativeTo: .caption)).multilineTextAlignment(.center)
            Button("다시 시도", action: model.retry)
                .font(MoaLogFont.semibold(14)).frame(minWidth: 120, minHeight: 48)
                .background(MoaLogColor.teal, in: RoundedRectangle(cornerRadius: 12)).foregroundStyle(.white)
        }.frame(maxWidth: .infinity, minHeight: 320)
    }

    private var selectedCategoryNames: [String] {
        model.state.categories.filter { model.state.filters.categoryIds.contains($0.id) }.map(\.name)
    }

    private var budgetProgress: CGFloat {
        guard let percent = model.state.budgetUsagePercent?.doubleValue, percent.isFinite else { return 0 }
        return CGFloat(min(max(percent / 100, 0), 1))
    }

    private func comparisonText(_ comparison: ExpenseComparison) -> Text {
        if comparison.hasOverflow { return Text("지난달 비교 금액 범위를 초과했어요") }
        let period = comparison.period == .samedaypriormonth ? "지난달 같은 기간보다" : "지난달보다"
        if comparison.isEqual { return Text("\(period) 같은 금액을 사용했어요") }
        return Text("\(period) \(formatWon(comparison.differenceWon)) \(comparison.selectedSpentMore ? "더 썼어요" : "덜 썼어요")")
    }

    private func shouldShowComparison(_ comparison: ExpenseComparison) -> Bool {
        !(comparison.isEqual && comparison.selectedPeriodTotalWon == 0 && comparison.priorPeriodTotalWon == 0 && model.state.fullMonthTotalWon > 0)
    }

    private var groupedRecords: [RecordDateGroup] {
        var order: [String] = []
        var grouped: [String: [ExpenseRecord]] = [:]
        for record in model.state.visibleRecords {
            let key = record.actualDate?.description ?? "date-unknown"
            if grouped[key] == nil { order.append(key) }
            grouped[key, default: []].append(record)
        }
        return order.map { key in
            let records = grouped[key] ?? []
            return RecordDateGroup(key: key, title: dateTitle(records.first?.actualDate), records: records)
        }
    }

    private func dateTitle(_ date: LocalDateKey?) -> String {
        guard let date else { return "날짜 미입력" }
        let components = DateComponents(calendar: Calendar(identifier: .gregorian), year: Int(date.year), month: Int(date.month), day: Int(date.day))
        let weekday = components.date.map { ["일", "월", "화", "수", "목", "금", "토"][Calendar(identifier: .gregorian).component(.weekday, from: $0) - 1] } ?? ""
        return "\(date.month)월 \(date.day)일 \(weekday)요일"
    }

    private func categorySymbol(_ id: String) -> String {
        if id.contains("food") || id.contains("meal") { return "fork.knife" }
        if id.contains("transport") { return "bus.fill" }
        if id.contains("shopping") { return "cart.fill" }
        if id.contains("housing") { return "house.fill" }
        return "creditcard.fill"
    }

    private var restorationSignature: String {
        let ids = model.state.categories.filter { model.state.filters.categoryIds.contains($0.id) }.map(\.id).joined(separator: ",")
        return "\(model.state.month)-\(ids)-\(model.state.filters.overspentOnly)-\(model.state.filters.sort.name)"
    }

    private func applyInitialState() {
        guard !restored else { return }
        restored = true
        if selectionRequest != nil { applySelectionRequest() }
        else if let restoration = RecordsRestoration.decode(persistedRestoration) { model.restore(restoration) }
    }

    private func applySelectionRequest() {
        guard let request = selectionRequest, request.id != appliedRequestId else { return }
        appliedRequestId = request.id
        model.apply(request)
        onSelectionRequestConsumed(request.id)
    }

    private func persistState() {
        guard restored, !model.state.isLoading else { return }
        let ids = model.state.categories.filter { model.state.filters.categoryIds.contains($0.id) }.map(\.id)
        persistedRestoration = RecordsRestoration(
            year: Int(model.state.month.year), month: Int(model.state.month.month), categoryIds: ids,
            overspentOnly: model.state.filters.overspentOnly, oldestFirst: model.state.filters.sort == .oldest
        ).encoded
    }

    private func recordMonthMetadata(_ record: ExpenseRecord) -> String? {
        guard let date = record.actualDate else { return "실제 지출일 미입력" }
        guard date.year != record.attributionMonth.year || date.month != record.attributionMonth.month else { return nil }
        return "실제 지출일 \(date.year).\(date.month).\(date.day) · 귀속 \(record.attributionMonth.year)년 \(record.attributionMonth.month)월"
    }
}

private struct RecordDateGroup {
    let key: String
    let title: String
    let records: [ExpenseRecord]
    var total: Int64? {
        records.reduce(Optional<Int64>(0)) { partial, record in
            guard let partial else { return nil }
            let result = partial.addingReportingOverflow(record.amountWon)
            return result.overflow ? nil : result.partialValue
        }
    }
}

private struct RecordsFilterSheet: View {
    let state: RecordsUiState
    let onApply: ([String], Bool, Bool) -> Void
    let onReset: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var categoryIds: Set<String>
    @State private var overspentOnly: Bool
    @State private var oldestFirst: Bool

    init(state: RecordsUiState, onApply: @escaping ([String], Bool, Bool) -> Void, onReset: @escaping () -> Void) {
        self.state = state; self.onApply = onApply; self.onReset = onReset
        _categoryIds = State(initialValue: Set(state.categories.filter { state.filters.categoryIds.contains($0.id) }.map(\.id)))
        _overspentOnly = State(initialValue: state.filters.overspentOnly)
        _oldestFirst = State(initialValue: state.filters.sort == .oldest)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    sectionTitle("카테고리")
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 8)], spacing: 8) {
                        ForEach(state.categories, id: \.id) { category in
                            choice(category.name, selected: categoryIds.contains(category.id)) {
                                if categoryIds.contains(category.id) { categoryIds.remove(category.id) }
                                else { categoryIds.insert(category.id) }
                            }
                        }
                    }
                    sectionTitle("과소비 선택")
                    HStack(spacing: 8) {
                        choice("전체", selected: !overspentOnly) { overspentOnly = false }
                        choice("사용자가 체크한 지출만", selected: overspentOnly) { overspentOnly = true }
                    }
                    sectionTitle("정렬")
                    HStack(spacing: 8) {
                        choice("최신순", selected: !oldestFirst) { oldestFirst = false }
                        choice("오래된순", selected: oldestFirst) { oldestFirst = true }
                    }
                }
                .padding(20)
            }
            .background(MoaLogColor.homeCanvas)
            .navigationTitle("필터 및 정렬")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("닫기") { dismiss() }.frame(minHeight: 44) } }
            .safeAreaInset(edge: .bottom) {
                HStack(spacing: 8) {
                    Button(action: onReset) { Text("초기화").frame(maxWidth: .infinity, minHeight: 50) }
                        .buttonStyle(.plain).background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(MoaLogColor.cardBorder))
                    Button { onApply(Array(categoryIds), overspentOnly, oldestFirst) } label: {
                        Text("필터 적용").foregroundStyle(.white).frame(maxWidth: .infinity, minHeight: 50)
                    }
                    .buttonStyle(.plain).background(MoaLogColor.teal, in: RoundedRectangle(cornerRadius: 12))
                }.font(MoaLogFont.semibold(14)).padding(16).background(MoaLogColor.surface)
            }
        }
    }

    private func sectionTitle(_ title: String) -> some View { Text(title).font(MoaLogFont.bold(14, relativeTo: .headline)) }
    private func choice(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(MoaLogFont.medium(12, relativeTo: .caption)).lineLimit(1).minimumScaleFactor(0.75)
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(selected ? MoaLogColor.teal : MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
                .foregroundStyle(selected ? .white : MoaLogColor.ink)
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(selected ? MoaLogColor.teal : MoaLogColor.cardBorder))
        }.buttonStyle(.plain).accessibilityAddTraits(selected ? .isSelected : [])
    }
}
