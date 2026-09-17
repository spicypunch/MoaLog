import SharedKit
import SwiftUI
import UIKit

@MainActor
final class MultiMonthApplyViewModel: ObservableObject {
    @Published private(set) var state: MultiMonthApplyUiState

    private let store: IosMultiMonthApplyStore
    private var observation: IosObservation?

    init(dependencies: IosDependencies, route: MultiMonthApplyRoute) {
        let store = dependencies.multiMonthApplyStore(
            sourceMonth: route.month,
            type: route.type,
            sourceItemId: route.itemId.map(KotlinLong.init(value:))
        )
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

    func previousYear() { store.previousYear() }
    func nextYear() { store.nextYear() }
    func toggleMonth(_ month: Int) { store.toggleMonth(year: state.displayedYear, month: Int32(month)) }
    func selectDisplayedYear() { store.selectDisplayedYear() }
    func clearSelection() { store.clearSelection() }
    func continueToPreview() { store.continueToPreview() }
    func previousStep() { store.previousStep() }
    func keepExisting() { store.keepExisting() }
    func overwrite() { store.overwrite() }
    func requestApply() { store.requestApply() }
    func dismissApply() { store.dismissApply() }
    func confirmApply() { store.confirmApply() }
    func retry() { store.retry() }
    func draftSnapshot() -> MultiMonthApplyDraftSnapshot { store.draftSnapshot() }
    func resultSummary() -> IosMultiMonthApplyResultSummary? { store.resultSummary() }
    func restoreDraft(_ draft: PersistedMultiMonthApplyDraft) {
        store.restoreDraft(
            displayedYear: draft.displayedYear,
            selectedMonths: draft.selectedMonths.map { YearMonthKey(year: $0.year, month: $0.month) },
            overwrite: draft.overwrite,
            previewRequested: draft.previewRequested
        )
    }
}

struct MultiMonthApplyView: View {
    @StateObject private var model: MultiMonthApplyViewModel
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @AccessibilityFocusState private var workflowFocus: WorkflowFocus?
    @SceneStorage("moalog.p07.draft.v1") private var persistedDraft = ""
    @State private var didRestoreDraft = false
    @State private var pendingRestoredPreview = false
    @State private var expectedRestoredDraftSignature: String?
    @State private var didObserveRestoredDraftState = false
    @State private var didReportApplied = false

    let onApplied: () -> Void
    let onCancel: () -> Void
    let onCompleted: () -> Void

    init(
        dependencies: IosDependencies,
        route: MultiMonthApplyRoute,
        onApplied: @escaping () -> Void,
        onCancel: @escaping () -> Void,
        onCompleted: @escaping () -> Void
    ) {
        _model = StateObject(wrappedValue: MultiMonthApplyViewModel(dependencies: dependencies, route: route))
        self.onApplied = onApplied
        self.onCancel = onCancel
        self.onCompleted = onCompleted
    }

    var body: some View {
        VStack(spacing: 0) {
            toolbar

            switch model.state.step {
            case .selectmonths:
                selectionStep
            case .preview:
                previewStep
            default:
                completionStep
            }
        }
        .background(MoaLogColor.homeCanvas.ignoresSafeArea())
        .interactiveDismissDisabled()
        .onAppear(perform: restorePersistedDraftIfNeeded)
        .onReceive(model.$state) { state in persistDraftIfReady(state) }
        .onChange(of: model.state.step) { _, step in
            DispatchQueue.main.async {
                if step == .preview {
                    pendingRestoredPreview = false
                    workflowFocus = .previewTitle
                } else if step == .complete {
                    persistedDraft = ""
                    if !didReportApplied {
                        didReportApplied = true
                        onApplied()
                    }
                    workflowFocus = .completionTitle
                    UIAccessibility.post(notification: .announcement, argument: completionSummary)
                } else {
                    workflowFocus = .selectionTitle
                }
            }
        }
        .onChange(of: model.state.error) { _, error in
            guard let error else { return }
            DispatchQueue.main.async {
                workflowFocus = .error
                UIAccessibility.post(notification: .announcement, argument: error)
            }
        }
        .alert("선택한 달에 적용할까요?", isPresented: confirmationBinding) {
            Button("취소", role: .cancel) { model.dismissApply() }
            Button(confirmationButtonTitle) { model.confirmApply() }
        } message: {
            Text(confirmationMessage)
        }
    }

    private var toolbar: some View {
        HStack(spacing: 4) {
            Button {
                if model.state.step == .preview {
                    model.previousStep()
                } else if model.state.step == .complete {
                    finishCompleted()
                } else {
                    cancel()
                }
            } label: {
                Image(systemName: model.state.step == .preview ? "chevron.left" : "xmark")
                    .font(.system(size: 15, weight: .semibold))
                    .frame(width: 48, height: 48)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .foregroundStyle(MoaLogColor.ink)
            .disabled(model.state.isLoading)
            .accessibilityLabel(model.state.step == .preview ? "달 선택으로 돌아가기" : "여러 달 적용 닫기")

            Text("여러 달 적용")
                .font(MoaLogFont.semibold(14, relativeTo: .headline))
                .foregroundStyle(MoaLogColor.teal)
                .frame(maxWidth: .infinity, alignment: model.state.step == .preview ? .leading : .center)

            if model.state.step == .preview {
                Button("취소", action: cancel)
                    .font(MoaLogFont.medium(12, relativeTo: .caption))
                    .foregroundStyle(MoaLogColor.mutedInk)
                    .frame(width: 48, height: 48)
                    .disabled(model.state.isLoading)
            } else {
                Text(model.state.step == .complete ? "완료" : "1 / 2")
                    .font(MoaLogFont.medium(11, relativeTo: .caption2))
                    .foregroundStyle(MoaLogColor.mutedInk)
                    .frame(width: 48, height: 48)
                    .accessibilityLabel(model.state.step == .complete ? "적용 완료" : "2단계 중 1단계")
            }
        }
        .padding(.horizontal, 4)
        .background(MoaLogColor.surface)
        .overlay(alignment: .bottom) { Rectangle().fill(MoaLogColor.cardBorder).frame(height: 1) }
    }

    private var selectionStep: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text(selectionHeadline)
                        .font(MoaLogFont.bold(22, relativeTo: .title2))
                        .foregroundStyle(MoaLogColor.ink)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityAddTraits(.isHeader)
                        .accessibilityFocused($workflowFocus, equals: .selectionTitle)

                    if model.state.isSourceLoading {
                        ProgressView("적용할 항목을 확인하고 있어요")
                            .font(MoaLogFont.regular(13, relativeTo: .caption))
                            .tint(MoaLogColor.teal)
                            .frame(maxWidth: .infinity, minHeight: 48)
                    } else if model.state.initialLoadFailed {
                        loadFailure
                    } else {
                        yearSelector
                        monthSelection
                    }

                    if let error = model.state.error, !model.state.initialLoadFailed {
                        errorBanner(error)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 24)
                .padding(.bottom, 28)
                .frame(maxWidth: 520)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            selectionBottomBar
        }
    }

    private var yearSelector: some View {
        HStack(spacing: 4) {
            yearButton(symbol: "chevron.left", label: "이전 연도", enabled: model.state.displayedYear > 1900, action: model.previousYear)
            Spacer()
            Text(String(model.state.displayedYear) + "년")
                .font(MoaLogFont.medium(14))
                .foregroundStyle(MoaLogColor.ink)
                .accessibilityAddTraits(.isHeader)
            Spacer()
            yearButton(symbol: "chevron.right", label: "다음 연도", enabled: model.state.displayedYear < 9999, action: model.nextYear)
        }
        .padding(.horizontal, 4)
        .frame(minHeight: 64)
        .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(MoaLogColor.cardBorder, lineWidth: 1))
        .shadow(color: .black.opacity(0.025), radius: 4, y: 2)
    }

    private func yearButton(symbol: String, label: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 13, weight: .semibold))
                .frame(width: 48, height: 48)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(enabled ? MoaLogColor.ink : MoaLogColor.outline.opacity(0.35))
        .disabled(!enabled)
        .accessibilityLabel(label)
    }

    private var monthSelection: some View {
        VStack(spacing: 12) {
            HStack {
                Button("전체 선택", action: model.selectDisplayedYear)
                    .frame(minWidth: 88, minHeight: 44, alignment: .leading)
                    .contentShape(Rectangle())
                    .accessibilityHint(Text(verbatim: String(model.state.displayedYear) + "년의 기준월을 제외한 모든 달을 선택합니다"))
                Spacer()
                Button("선택 해제", action: model.clearSelection)
                    .frame(minWidth: 88, minHeight: 44, alignment: .trailing)
                    .contentShape(Rectangle())
                    .disabled(model.state.selectedMonths.isEmpty)
            }
            .font(MoaLogFont.medium(12, relativeTo: .caption))
            .foregroundStyle(MoaLogColor.ink)
            .frame(minHeight: 44)

            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: 3), spacing: 12) {
                ForEach(1...12, id: \.self) { month in
                    monthButton(month)
                }
            }
        }
    }

    private func monthButton(_ month: Int) -> some View {
        let key = YearMonthKey(year: model.state.displayedYear, month: Int32(month))
        let isSource = key == model.state.args.sourceMonth
        let selected = model.state.selectedMonths.contains(key)

        return Button { model.toggleMonth(month) } label: {
            ZStack(alignment: .topTrailing) {
                VStack(spacing: 2) {
                    Text("\(month)월")
                        .font(MoaLogFont.medium(13, relativeTo: .subheadline))
                    if isSource {
                        Text("기준월")
                            .font(MoaLogFont.regular(9, relativeTo: .caption2))
                    }
                }
                .frame(maxWidth: .infinity, minHeight: 58)

                if selected {
                    Image(systemName: "checkmark.circle")
                        .font(.system(size: 11, weight: .medium))
                        .padding(7)
                        .accessibilityHidden(true)
                }
            }
            .foregroundStyle(isSource ? MoaLogColor.outline.opacity(0.55) : selected ? MoaLogColor.teal : MoaLogColor.ink)
            .background(isSource ? MoaLogColor.surfaceLow.opacity(0.7) : selected ? MoaLogColor.sage : MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(selected ? MoaLogColor.teal : MoaLogColor.cardBorder, lineWidth: selected ? 1.5 : 1))
        }
        .buttonStyle(.plain)
        .disabled(isSource || model.state.isLoading)
        .accessibilityLabel(Text(verbatim: String(model.state.displayedYear) + "년 \(month)월\(isSource ? ", 기준월" : "")"))
        .accessibilityValue(selected ? "선택됨" : "선택 안 됨")
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private var selectionBottomBar: some View {
        VStack(spacing: 8) {
            Text(selectionHelper)
                .font(MoaLogFont.regular(10, relativeTo: .caption2))
                .foregroundStyle(MoaLogColor.mutedInk)
                .multilineTextAlignment(.center)

            Button(action: model.continueToPreview) {
                HStack(spacing: 7) {
                    if model.state.isLoading { ProgressView().tint(.white) }
                    Text(model.state.isLoading ? "변경 내용 확인 중" : "다음 · \(selectedCount)개월 선택")
                    if !model.state.isLoading {
                        Image(systemName: "chevron.right").font(.system(size: 11, weight: .bold))
                    }
                }
                .font(MoaLogFont.bold(15))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, minHeight: 52)
                .background(model.state.canContinue ? MoaLogColor.teal : MoaLogColor.outline.opacity(0.45), in: RoundedRectangle(cornerRadius: 11))
            }
            .buttonStyle(.plain)
            .disabled(!model.state.canContinue)
            .accessibilityHint(model.state.canContinue ? "선택한 달의 변경 내용을 확인합니다" : "한 개 이상의 달을 선택해 주세요")
        }
        .padding(.horizontal, 20)
        .padding(.top, 10)
        .padding(.bottom, 8)
        .background(MoaLogColor.surface.shadow(.drop(color: .black.opacity(0.08), radius: 7, y: -2)))
    }

    private var previewStep: some View {
        VStack(spacing: 0) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 14) {
                    previewProgress

                    Text("변경 내용 확인")
                        .font(MoaLogFont.bold(22, relativeTo: .title2))
                        .foregroundStyle(MoaLogColor.ink)
                        .accessibilityAddTraits(.isHeader)
                        .accessibilityFocused($workflowFocus, equals: .previewTitle)

                    Text("선택한 \(selectedCount)개월에 아래 계획을 적용합니다.")
                        .font(MoaLogFont.regular(13, relativeTo: .subheadline))
                        .foregroundStyle(MoaLogColor.mutedInk)

                    sourceCard
                    policySection

                    HStack {
                        Text("월별 변경 내역")
                            .font(MoaLogFont.bold(15, relativeTo: .headline))
                            .foregroundStyle(MoaLogColor.ink)
                        Text("총 \(selectedCount)개월")
                            .font(MoaLogFont.semibold(9, relativeTo: .caption2))
                            .foregroundStyle(MoaLogColor.teal)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 3)
                            .background(MoaLogColor.sage, in: Capsule())
                        Spacer()
                        Text("\(model.state.preview?.conflictCount ?? 0)개 기존값")
                            .font(MoaLogFont.regular(10, relativeTo: .caption2))
                            .foregroundStyle(MoaLogColor.mutedInk)
                    }

                    if model.state.isLoading && model.state.preview == nil {
                        ProgressView("변경 내용을 불러오는 중이에요")
                            .font(MoaLogFont.regular(13, relativeTo: .caption))
                            .tint(MoaLogColor.teal)
                            .frame(maxWidth: .infinity, minHeight: 120)
                    } else if let preview = model.state.preview {
                        ForEach(preview.targetMonths, id: \.month.description) { target in
                            targetCard(target)
                        }
                    }

                    if let error = model.state.error {
                        errorBanner(error)
                    }

                    Label("적용 후에도 각 월에서 계획을 수정할 수 있어요.", systemImage: "info.circle")
                        .font(MoaLogFont.regular(11, relativeTo: .caption))
                        .foregroundStyle(MoaLogColor.mutedInk)
                        .padding(12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(MoaLogColor.surfaceLow.opacity(0.8), in: RoundedRectangle(cornerRadius: 10))
                }
                .padding(.horizontal, 20)
                .padding(.top, 22)
                .padding(.bottom, 24)
                .frame(maxWidth: 520)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            previewBottomBar
        }
    }

    private var sourceCard: some View {
        VStack(spacing: 10) {
            HStack {
                Label("기준 \(monthText(model.state.args.sourceMonth))", systemImage: "calendar")
                    .font(MoaLogFont.semibold(11, relativeTo: .caption))
                    .foregroundStyle(MoaLogColor.teal)
                Spacer()
                Text(planEditorTypeTitle(model.state.args.type))
                    .font(MoaLogFont.semibold(9, relativeTo: .caption2))
                    .foregroundStyle(MoaLogColor.mutedInk)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(MoaLogColor.surfaceLow, in: Capsule())
            }

            if let items = model.state.preview?.sourceItems, !items.isEmpty {
                ForEach(items, id: \.id) { item in
                    sourceAmountRow(item)
                }
            } else {
                HStack {
                    Text(model.state.sourceItemName ?? "\(planEditorTypeTitle(model.state.args.type)) 전체 계획")
                        .font(MoaLogFont.semibold(13))
                    Spacer()
                    ProgressView().tint(MoaLogColor.teal)
                }
            }
        }
        .padding(14)
        .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(MoaLogColor.cardBorder, lineWidth: 1))
        .accessibilityElement(children: .contain)
    }

    private var policySection: some View {
        VStack(alignment: .leading, spacing: 9) {
            Text("충돌 처리 방식")
                .font(MoaLogFont.semibold(12, relativeTo: .caption))
                .foregroundStyle(MoaLogColor.ink)
            HStack(spacing: 8) {
                policyButton(title: "기존 값 유지", symbol: "shield.checkered", selected: model.state.policy == .keepexisting, action: model.keepExisting)
                policyButton(title: "덮어쓰기 적용", symbol: "arrow.triangle.2.circlepath", selected: model.state.policy == .overwrite, action: model.overwrite)
            }
            Text(model.state.policy == .keepexisting ? "이미 입력된 항목은 건너뛰고 빈 달에만 적용해요." : "같은 항목이 있으면 선택한 기준월 값으로 바꿔요.")
                .font(MoaLogFont.regular(10, relativeTo: .caption2))
                .foregroundStyle(MoaLogColor.mutedInk)
        }
    }

    private var previewProgress: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack {
                Label("2단계 중 2단계", systemImage: "circle.fill")
                    .font(MoaLogFont.semibold(10, relativeTo: .caption2))
                    .foregroundStyle(MoaLogColor.teal)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 5)
                    .background(MoaLogColor.sage, in: Capsule())
                Spacer()
                Text("완료 직전")
                    .font(MoaLogFont.medium(10, relativeTo: .caption2))
                    .foregroundStyle(MoaLogColor.mutedInk)
            }
            Capsule()
                .fill(MoaLogColor.teal)
                .frame(height: 6)
                .accessibilityHidden(true)
        }
    }

    private func policyButton(title: String, symbol: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 7) {
                Image(systemName: symbol).accessibilityHidden(true)
                Text(title)
            }
            .font(MoaLogFont.semibold(12, relativeTo: .caption))
            .foregroundStyle(selected ? MoaLogColor.teal : MoaLogColor.mutedInk)
            .frame(maxWidth: .infinity, minHeight: 48)
            .background(selected ? MoaLogColor.sage : MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(selected ? MoaLogColor.teal : MoaLogColor.cardBorder, lineWidth: selected ? 1.5 : 1))
        }
        .buttonStyle(.plain)
        .disabled(model.state.isLoading)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func targetCard(_ target: DatabasePlanCopyTargetPreview) -> some View {
        VStack(spacing: 0) {
            HStack {
                Text("\(target.month.month)월")
                    .font(MoaLogFont.bold(13, relativeTo: .headline))
                    .foregroundStyle(MoaLogColor.ink)
                Text(monthText(target.month))
                    .font(MoaLogFont.regular(10, relativeTo: .caption2))
                    .foregroundStyle(MoaLogColor.mutedInk)
                Spacer()
                Text(target.rows.contains(where: { $0.existingItem != nil }) ? conflictBadge : "새로 적용")
                    .font(MoaLogFont.semibold(9, relativeTo: .caption2))
                    .foregroundStyle(target.rows.contains(where: { $0.existingItem != nil }) ? conflictBadgeColor : MoaLogColor.teal)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background((target.rows.contains(where: { $0.existingItem != nil }) ? conflictBadgeColor : MoaLogColor.teal).opacity(0.10), in: Capsule())
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)

            ForEach(Array(target.rows.enumerated()), id: \.offset) { index, row in
                if index > 0 { Divider().overlay(MoaLogColor.divider) }
                previewRow(row)
            }
        }
        .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(MoaLogColor.cardBorder, lineWidth: 1))
    }

    private func previewRow(_ row: DatabasePlanCopyPreviewRow) -> some View {
        let sourceAmount = row.sourceItem.amountWon?.int64Value
        let existingAmount = row.existingItem?.amountWon?.int64Value
        let afterAmount = model.state.policy == .keepexisting && row.existingItem != nil ? existingAmount : sourceAmount

        return VStack(alignment: .leading, spacing: 7) {
            adaptiveAmountRow(name: row.sourceItem.name, amount: amountText(afterAmount), amountSize: 14)

            HStack(spacing: 6) {
                Text("기존 \(amountText(existingAmount))")
                Image(systemName: "arrow.right").font(.system(size: 8, weight: .semibold)).accessibilityHidden(true)
                Text("적용 후 \(amountText(afterAmount))")
            }
            .font(MoaLogFont.regular(10, relativeTo: .caption2))
            .foregroundStyle(MoaLogColor.mutedInk)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(row.sourceItem.name), 기존 \(amountText(existingAmount)), 적용 후 \(amountText(afterAmount))")
    }

    private var previewBottomBar: some View {
        HStack(spacing: 8) {
            Button(action: model.previousStep) {
                Label("이전 단계", systemImage: "chevron.left")
                    .font(MoaLogFont.semibold(12, relativeTo: .caption))
                    .foregroundStyle(MoaLogColor.mutedInk)
                    .frame(minWidth: 92, minHeight: 52)
                    .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 11))
                    .overlay(RoundedRectangle(cornerRadius: 11).stroke(MoaLogColor.cardBorder, lineWidth: 1))
            }
            .buttonStyle(.plain)
            .disabled(model.state.isLoading)

            Button(action: model.requestApply) {
                HStack(spacing: 7) {
                    if model.state.isLoading { ProgressView().tint(.white) }
                    Text(model.state.isLoading ? "적용 중" : "\(selectedCount)개월에 적용하기")
                    if !model.state.isLoading { Image(systemName: "checkmark").font(.system(size: 11, weight: .bold)) }
                }
                .font(MoaLogFont.bold(14))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, minHeight: 52)
                .background(model.state.canApply ? MoaLogColor.teal : MoaLogColor.outline.opacity(0.45), in: RoundedRectangle(cornerRadius: 11))
            }
            .buttonStyle(.plain)
            .disabled(!model.state.canApply)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(MoaLogColor.surface.shadow(.drop(color: .black.opacity(0.08), radius: 7, y: -2)))
    }

    private var completionStep: some View {
        VStack(spacing: 18) {
            Spacer()
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 58, weight: .semibold))
                .foregroundStyle(MoaLogColor.teal)
                .accessibilityHidden(true)
            Text(completionTitle)
                .font(MoaLogFont.bold(23, relativeTo: .title2))
                .foregroundStyle(MoaLogColor.ink)
                .multilineTextAlignment(.center)
                .accessibilityAddTraits(.isHeader)
                .accessibilityFocused($workflowFocus, equals: .completionTitle)
            Text(completionSummary)
                .font(MoaLogFont.regular(14))
                .foregroundStyle(MoaLogColor.mutedInk)
                .multilineTextAlignment(.center)
            if let summary = model.resultSummary() {
                HStack(spacing: 0) {
                    resultMetric("새로 적용", value: summary.newlyInsertedItemCount)
                    Divider().frame(height: 40)
                    resultMetric("덮어씀", value: summary.overwrittenItemCount)
                    Divider().frame(height: 40)
                    resultMetric("유지", value: summary.keptItemCount)
                }
                .padding(.vertical, 16)
                .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 14))
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(MoaLogColor.cardBorder, lineWidth: 1))
                .padding(.horizontal, 20)
            }
            Spacer()
            Button("월별 계획으로 돌아가기", action: finishCompleted)
                .font(MoaLogFont.bold(15))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, minHeight: 52)
                .background(MoaLogColor.teal, in: RoundedRectangle(cornerRadius: 11))
                .padding(.horizontal, 20)
                .padding(.bottom, 8)
        }
        .frame(maxWidth: 520)
        .frame(maxWidth: .infinity)
        .padding(.top, 20)
        .accessibilityElement(children: .contain)
    }

    private func resultMetric(_ title: String, value: Int32) -> some View {
        VStack(spacing: 4) {
            Text("\(value)개").font(MoaLogFont.bold(17)).foregroundStyle(MoaLogColor.ink)
            Text(title).font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private func sourceAmountRow(_ item: MonthlyPlanItem) -> some View {
        adaptiveAmountRow(name: item.name, amount: amountText(item.amountWon?.int64Value), amountSize: 20)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(item.name), \(amountText(item.amountWon?.int64Value))")
    }

    @ViewBuilder
    private func adaptiveAmountRow(name: String, amount: String, amountSize: CGFloat) -> some View {
        if dynamicTypeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 5) {
                Text(name)
                    .font(MoaLogFont.semibold(13))
                    .foregroundStyle(MoaLogColor.ink)
                Text(amount)
                    .font(MoaLogFont.bold(amountSize, relativeTo: .title3))
                    .foregroundStyle(MoaLogColor.ink)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(name)
                    .font(MoaLogFont.semibold(13))
                    .foregroundStyle(MoaLogColor.ink)
                    .lineLimit(2)
                Spacer(minLength: 8)
                Text(amount)
                    .font(MoaLogFont.bold(amountSize, relativeTo: .title3))
                    .foregroundStyle(MoaLogColor.ink)
                    .lineLimit(1)
            }
        }
    }

    private var loadFailure: some View {
        VStack(spacing: 10) {
            Image(systemName: "exclamationmark.circle.fill")
                .font(.system(size: 28, weight: .semibold))
                .foregroundStyle(MoaLogColor.error)
                .accessibilityHidden(true)
            Text(model.state.error ?? "복사할 계획 항목을 불러오지 못했어요")
                .font(MoaLogFont.semibold(14))
                .foregroundStyle(MoaLogColor.ink)
                .multilineTextAlignment(.center)
            Button("다시 불러오기", action: model.retry)
                .font(MoaLogFont.semibold(14))
                .foregroundStyle(.white)
                .frame(maxWidth: 240, minHeight: 48)
                .background(MoaLogColor.teal, in: RoundedRectangle(cornerRadius: 10))
        }
        .padding(18)
        .frame(maxWidth: .infinity)
        .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(MoaLogColor.cardBorder, lineWidth: 1))
        .accessibilityFocused($workflowFocus, equals: .error)
    }

    private func errorBanner(_ message: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "exclamationmark.circle.fill").accessibilityHidden(true)
            Text(message)
                .font(MoaLogFont.medium(12, relativeTo: .caption))
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .foregroundStyle(MoaLogColor.error)
        .padding(.horizontal, 12)
        .background(MoaLogColor.errorSurface.opacity(0.55), in: RoundedRectangle(cornerRadius: 10))
        .accessibilityElement(children: .combine)
        .accessibilityFocused($workflowFocus, equals: .error)
    }

    private var selectedCount: Int { model.state.selectedMonths.count }

    private var selectionHeadline: String {
        let source = model.state.args.sourceMonth
        if let name = model.state.sourceItemName {
            return "\(source.year)년 \(source.month)월 ‘\(name)’을\n적용할 달을 선택해 주세요"
        }
        return "\(source.year)년 \(source.month)월 계획을\n적용할 달을 선택해 주세요"
    }

    private var selectionHelper: String {
        if selectedCount == 0 { return "적용할 달을 하나 이상 선택해 주세요." }
        return "다음 단계에서 적용할 변경 내용을 확인합니다."
    }

    private var conflictBadge: String { model.state.policy == .keepexisting ? "기존 값 유지" : "덮어쓰기" }
    private var conflictBadgeColor: Color { model.state.policy == .keepexisting ? MoaLogColor.mutedInk : MoaLogColor.error }
    private var confirmationButtonTitle: String { "\(selectedCount)개월에 적용" }
    private var confirmationMessage: String {
        model.state.policy == .keepexisting
            ? "기존에 입력된 항목은 유지하고, 비어 있는 달에만 적용합니다."
            : "같은 항목의 기존 값을 기준월 값으로 바꿉니다."
    }
    private var completionSummary: String {
        guard let result = model.state.result else { return "선택한 달의 계획을 업데이트했습니다." }
        if model.resultSummary()?.allExistingKept == true {
            return "이미 입력된 항목은 바꾸지 않고 그대로 두었습니다."
        }
        return "\(result.targetMonthCount)개월의 계획을 업데이트했습니다."
    }

    private var completionTitle: String {
        model.resultSummary()?.allExistingKept == true
            ? "기존 값을 그대로 유지했어요"
            : "여러 달 적용을 완료했어요"
    }

    private func restorePersistedDraftIfNeeded() {
        guard !didRestoreDraft else { return }
        defer { didRestoreDraft = true }
        guard !persistedDraft.isEmpty else { return }
        guard let draft = PersistedMultiMonthApplyDraft.decode(persistedDraft) else {
            persistedDraft = ""
            return
        }
        pendingRestoredPreview = draft.previewRequested
        expectedRestoredDraftSignature = draft.stateSignature
        model.restoreDraft(draft)
    }

    private func persistDraftIfReady(_ state: MultiMonthApplyUiState) {
        guard didRestoreDraft, state.step != .complete else { return }
        let snapshot = model.draftSnapshot()
        let currentDraft = PersistedMultiMonthApplyDraft(snapshot)
        if let expectedRestoredDraftSignature, !didObserveRestoredDraftState {
            // StateFlow can deliver its initial state after onAppear dispatched restore.
            // Ignore it until the Kotlin holder publishes the restored selection/policy.
            guard currentDraft.stateSignature == expectedRestoredDraftSignature else { return }
            didObserveRestoredDraftState = true
        }
        if pendingRestoredPreview && state.step != .preview {
            if state.error == nil {
                persistedDraft = PersistedMultiMonthApplyDraft(
                    snapshot,
                    previewRequested: true
                ).encoded
                return
            }
            // Once regeneration fails, keep the restored selection/policy but return the
            // persisted workflow to the selection step. Subsequent user edits are saved
            // normally and will not retry a stale preview on the next scene recreation.
            pendingRestoredPreview = false
        }
        persistedDraft = currentDraft.encoded
    }

    private func cancel() {
        persistedDraft = ""
        onCancel()
    }

    private func finishCompleted() {
        persistedDraft = ""
        onCompleted()
    }

    private var confirmationBinding: Binding<Bool> {
        Binding(
            get: { model.state.showApplyConfirmation },
            set: { if !$0 { model.dismissApply() } }
        )
    }

    private func monthText(_ month: YearMonthKey) -> String { "\(month.year)년 \(month.month)월" }
    private func amountText(_ amount: Int64?) -> String {
        guard let amount else { return "미입력" }
        return amount.formatted(.number.grouping(.automatic)) + "원"
    }
}

private enum WorkflowFocus: Hashable {
    case selectionTitle
    case previewTitle
    case completionTitle
    case error
}

struct PersistedMultiMonthApplyDraft: Codable {
    struct Month: Codable {
        let year: Int32
        let month: Int32
    }

    let displayedYear: Int32
    let selectedMonths: [Month]
    let overwrite: Bool
    let previewRequested: Bool

    init(_ snapshot: MultiMonthApplyDraftSnapshot, previewRequested: Bool? = nil) {
        displayedYear = snapshot.displayedYear
        selectedMonths = snapshot.selectedMonths.map { Month(year: $0.year, month: $0.month) }
        overwrite = snapshot.policy == .overwrite
        self.previewRequested = previewRequested ?? snapshot.previewRequested
    }

    var stateSignature: String {
        let months = selectedMonths
            .sorted { ($0.year, $0.month) < ($1.year, $1.month) }
            .map { "\($0.year)-\($0.month)" }
            .joined(separator: ",")
        return "\(displayedYear)|\(overwrite)|\(months)"
    }

    var encoded: String {
        guard let data = try? JSONEncoder().encode(self) else { return "" }
        return String(decoding: data, as: UTF8.self)
    }

    static func decode(_ value: String) -> Self? {
        guard !value.isEmpty, let data = value.data(using: .utf8) else { return nil }
        guard let draft = try? JSONDecoder().decode(Self.self, from: data),
              (1900...9999).contains(draft.displayedYear),
              draft.selectedMonths.allSatisfy({
                  (1900...9999).contains($0.year) && (1...12).contains($0.month)
              }),
              !draft.previewRequested || !draft.selectedMonths.isEmpty else { return nil }
        return draft
    }
}
