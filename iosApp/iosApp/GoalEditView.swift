import SharedKit
import SwiftUI

@MainActor
final class GoalEditViewModel: ObservableObject {
    @Published private(set) var state: IosGoalEditState
    private let store: IosGoalEditStore
    private var observation: IosObservation?

    init(dependencies: IosDependencies, setup: LedgerSetup, year: Int) {
        let store = dependencies.goalEditStore(setup: setup, year: Int32(year))
        self.store = store
        state = store.currentState
        observation = store.observe { [weak self] state in
            DispatchQueue.main.async { self?.state = state }
        }
    }

    deinit { observation?.cancel(); store.close() }
    func changeTarget(_ value: String) { store.changeTarget(value: value) }
    func save() { store.save() }
}

struct GoalEditView: View {
    @StateObject private var model: GoalEditViewModel
    let onClose: () -> Void
    let onSaved: () -> Void

    init(dependencies: IosDependencies, setup: LedgerSetup, year: Int, onClose: @escaping () -> Void, onSaved: @escaping () -> Void) {
        _model = StateObject(wrappedValue: GoalEditViewModel(dependencies: dependencies, setup: setup, year: year))
        self.onClose = onClose
        self.onSaved = onSaved
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("함께 모을 목표를 정해요")
                                .font(MoaLogFont.bold(23, relativeTo: .title2)).foregroundStyle(MoaLogColor.ink)
                            Text("목표를 바꾸면 홈의 달성률이 바로 다시 계산돼요.")
                                .font(MoaLogFont.regular(14)).foregroundStyle(MoaLogColor.mutedInk)
                        }

                        VStack(alignment: .leading, spacing: 8) {
                            Text("기준 연도").font(MoaLogFont.semibold(13, relativeTo: .caption)).foregroundStyle(MoaLogColor.mutedInk)
                            Text("\(String(model.state.year))년")
                                .font(MoaLogFont.semibold(16)).foregroundStyle(MoaLogColor.ink)
                                .padding(.horizontal, 14).frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
                                .background(MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 12))
                        }

                        VStack(alignment: .leading, spacing: 8) {
                            Text("연 저축 목표").font(MoaLogFont.semibold(13, relativeTo: .caption)).foregroundStyle(MoaLogColor.ink)
                            HStack {
                                TextField("50,000,000", text: Binding(get: { model.state.targetInput }, set: model.changeTarget))
                                    .font(MoaLogFont.semibold(20, relativeTo: .title3))
                                    .keyboardType(.numberPad)
                                    .disabled(model.state.isSaving)
                                    .accessibilityLabel("연 저축 목표 금액")
                                Text("원").font(MoaLogFont.medium(15)).foregroundStyle(MoaLogColor.mutedInk)
                            }
                            .padding(.horizontal, 14).frame(minHeight: 56)
                            .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(model.state.inputError == nil ? MoaLogColor.cardBorder : MoaLogColor.error, lineWidth: 1))

                            if let error = model.state.inputError {
                                Label(error, systemImage: "exclamationmark.circle.fill")
                                    .font(MoaLogFont.regular(12, relativeTo: .caption)).foregroundStyle(MoaLogColor.error)
                                    .accessibilityLabel("입력 오류, \(error)")
                            } else {
                                Text("비워 두면 목표를 제거할 수 있어요.")
                                    .font(MoaLogFont.regular(12, relativeTo: .caption)).foregroundStyle(MoaLogColor.mutedInk)
                            }
                        }

                        if let error = model.state.persistenceError {
                            Label(error, systemImage: "wifi.exclamationmark")
                                .font(MoaLogFont.medium(13, relativeTo: .caption)).foregroundStyle(MoaLogColor.error)
                                .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                                .background(MoaLogColor.errorSurface.opacity(0.55), in: RoundedRectangle(cornerRadius: 10))
                        }
                    }
                    .padding(20).frame(maxWidth: 480).frame(maxWidth: .infinity)
                }

                Button(action: model.save) {
                    Group {
                        if model.state.isSaving { ProgressView().tint(.white).accessibilityLabel("목표 저장 중") }
                        else { Text("저장") }
                    }
                    .font(MoaLogFont.semibold(16)).foregroundStyle(.white).frame(maxWidth: .infinity, minHeight: 52)
                    .background(MoaLogColor.teal, in: RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain).disabled(model.state.isSaving)
                .padding(.horizontal, 20).padding(.vertical, 12)
                .background(MoaLogColor.homeCanvas)
            }
            .background(MoaLogColor.homeCanvas.ignoresSafeArea())
            .navigationTitle("저축 목표 수정")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("취소", action: onClose).font(MoaLogFont.medium(15)).foregroundStyle(MoaLogColor.mutedInk)
                }
            }
            .onChange(of: model.state.didSave) { _, saved in if saved { onSaved() } }
        }
    }
}
