import SharedKit
import SwiftUI

@MainActor
final class RootViewModel: ObservableObject {
    @Published private(set) var state: IosRootState

    private let store: IosRootStore
    private var observation: IosObservation?

    init(dependencies: IosDependencies) {
        let store = dependencies.rootStore()
        self.store = store
        state = store.currentState
        observation = store.observe(observer: { [weak self] state in
            DispatchQueue.main.async {
                self?.state = state
            }
        })
    }

    deinit {
        observation?.cancel()
        store.close()
    }

    func retry() {
        store.retry()
    }
}

struct ContentView: View {
    private let dependencies: IosDependencies
    @StateObject private var model: RootViewModel
    @StateObject private var cloud: CloudSessionViewModel
    @Environment(\.scenePhase) private var scenePhase

    init(dependencies: IosDependencies) {
        self.dependencies = dependencies
        _model = StateObject(wrappedValue: RootViewModel(dependencies: dependencies))
        _cloud = StateObject(wrappedValue: CloudSessionViewModel(dependencies: dependencies))
    }

    @ViewBuilder
    var body: some View {
        Group {
            if cloud.authState.status == .restoring {
                loadingView
            } else if !cloud.isSignedIn {
                MoaLogLoginView(model: cloud)
            } else if cloud.householdState.status == .idle || cloud.householdState.status == .loading {
                loadingView
            } else if cloud.householdState.status == .needsConnection || cloud.householdState.status == .failed {
                if model.state.destination == .setup,
                   cloud.householdState.availableHouseholds.isEmpty,
                   cloud.householdState.status != .failed {
                    SetupView(dependencies: dependencies)
                } else {
                    HouseholdConnectionView(model: cloud, localSetup: model.state.setup)
                }
            } else if model.state.destination == IosRootDestination.loading {
                loadingView
            } else if model.state.destination == IosRootDestination.setup {
                SetupView(dependencies: dependencies)
            } else if model.state.destination == IosRootDestination.main {
                if let setup = model.state.setup {
                    MainTabView(dependencies: dependencies, setup: setup)
                        .environmentObject(cloud)
                } else {
                    loadingView
                }
            } else {
                failureView
            }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { cloud.synchronizeIfReady() }
        }
        .onReceive(model.$state) { state in
            if let setup = state.setup { cloud.updateHouseholdIfReady(from: setup) }
        }
    }

    private var loadingView: some View {
        ZStack {
            MoaLogColor.canvas.ignoresSafeArea()
            ProgressView("가계부를 불러오는 중이에요")
                .font(MoaLogFont.medium(14))
                .tint(MoaLogColor.teal)
                .foregroundStyle(MoaLogColor.mutedInk)
        }
    }

    private var failureView: some View {
        ZStack {
            MoaLogColor.canvas.ignoresSafeArea()
            VStack(spacing: 20) {
                Image(systemName: "exclamationmark.circle")
                    .font(.system(size: 36, weight: .regular))
                    .foregroundStyle(MoaLogColor.error)

                VStack(spacing: 6) {
                    Text("가계부를 불러오지 못했어요")
                        .font(MoaLogFont.bold(20, relativeTo: .title3))
                        .foregroundStyle(MoaLogColor.ink)
                    Text("잠시 후 다시 시도해 주세요")
                        .font(MoaLogFont.regular(14))
                        .foregroundStyle(MoaLogColor.mutedInk)
                }

                Button("다시 시도", action: model.retry)
                    .font(MoaLogFont.semibold(15))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 24)
                    .frame(minHeight: 48)
                    .background(MoaLogColor.teal, in: RoundedRectangle(cornerRadius: 12))
            }
            .padding(24)
        }
    }
}
