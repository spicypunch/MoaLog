import AuthenticationServices
import Network
import SharedKit
import SwiftUI

@MainActor
final class CloudSessionViewModel: ObservableObject {
    @Published private(set) var authState: AuthSessionState
    @Published private(set) var householdState: HouseholdSessionState
    @Published private(set) var syncStatus: any SyncStatus
    @Published private(set) var appleChallenge: ContractsAuthChallengeResponse?
    @Published private(set) var isPreparingAppleSignIn = false
    @Published private(set) var isCompletingAppleSignIn = false
    @Published var operationMessage: String?

    private let authStore: IosAuthStore
    private let householdStore: IosHouseholdStore
    private let syncStore: IosSyncStore
    private let networkMonitor = NWPathMonitor()
    private let networkQueue = DispatchQueue(label: "kr.jm.moalog.network-monitor")
    private var authObservation: IosObservation?
    private var householdObservation: IosObservation?
    private var syncObservation: IosObservation?
    private var wasSignedIn = false
    private var synchronizedHouseholdVersion: String?
    private var networkWasSatisfied = false

    init(dependencies: IosDependencies) {
        authStore = dependencies.authStore()
        householdStore = dependencies.householdStore()
        syncStore = dependencies.syncStore()
        authState = authStore.currentState
        householdState = householdStore.currentState
        syncStatus = syncStore.currentStatus

        authObservation = authStore.observe { [weak self] state in
            DispatchQueue.main.async { self?.receive(authState: state) }
        }
        householdObservation = householdStore.observe { [weak self] state in
            DispatchQueue.main.async { self?.receive(householdState: state) }
        }
        syncObservation = syncStore.observe { [weak self] status in
            DispatchQueue.main.async { self?.syncStatus = status }
        }

        networkMonitor.pathUpdateHandler = { [weak self] path in
            DispatchQueue.main.async {
                guard let self else { return }
                let isSatisfied = path.status == .satisfied
                if isSatisfied && !self.networkWasSatisfied && self.isSignedIn {
                    self.refreshHouseholds()
                    self.synchronizeIfReady()
                }
                self.networkWasSatisfied = isSatisfied
            }
        }
        networkMonitor.start(queue: networkQueue)

        authStore.restoreSafely { [weak self] message in
            DispatchQueue.main.async {
                if let message { self?.operationMessage = message }
            }
        }
    }

    deinit {
        networkMonitor.cancel()
        authObservation?.cancel()
        householdObservation?.cancel()
        syncObservation?.cancel()
        authStore.close()
        householdStore.close()
        syncStore.close()
    }

    var isSignedIn: Bool { authState.status == .signedIn }
    var isHouseholdReady: Bool { householdState.status == .ready }
    var canRequestAppleAuthorization: Bool {
        appleChallenge != nil && !isPreparingAppleSignIn && !isCompletingAppleSignIn
    }
    var isSyncing: Bool { syncStatus is SyncStatusRunning }

    func prepareAppleSignIn() {
        if let challenge = appleChallenge,
           challenge.expiresAtEpochSeconds > Int64(Date().timeIntervalSince1970) + 30 {
            return
        }
        guard !isPreparingAppleSignIn, !isCompletingAppleSignIn else { return }

        isPreparingAppleSignIn = true
        operationMessage = nil
        authStore.beginSignInSafely { [weak self] challenge, message in
            DispatchQueue.main.async {
                guard let self else { return }
                self.isPreparingAppleSignIn = false
                self.appleChallenge = challenge
                self.operationMessage = message
            }
        }
    }

    func configureAppleRequest(_ request: ASAuthorizationAppleIDRequest) {
        request.requestedScopes = [.fullName, .email]
        request.nonce = appleChallenge?.nonce
    }

    func completeAppleAuthorization(_ result: Result<ASAuthorization, Error>) {
        switch result {
        case let .success(authorization):
            guard
                let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
                let tokenData = credential.identityToken,
                let idToken = String(data: tokenData, encoding: .utf8),
                let challenge = appleChallenge
            else {
                failAppleAuthorization("Apple 로그인 정보를 확인하지 못했어요")
                return
            }

            isCompletingAppleSignIn = true
            operationMessage = nil
            authStore.completeAppleSignInSafely(challenge: challenge, idToken: idToken) { [weak self] message in
                DispatchQueue.main.async {
                    guard let self else { return }
                    self.isCompletingAppleSignIn = false
                    self.appleChallenge = nil
                    self.operationMessage = message
                    if message != nil { self.prepareAppleSignIn() }
                }
            }

        case let .failure(error):
            let authorizationError = error as? ASAuthorizationError
            if authorizationError?.code == .canceled {
                failAppleAuthorization(nil)
            } else {
                failAppleAuthorization(error.localizedDescription)
            }
        }
    }

    func connect(_ household: ContractsHouseholdSummaryDto) {
        operationMessage = nil
        householdStore.connectExistingSafely(household: household) { [weak self] message in
            DispatchQueue.main.async { self?.operationMessage = message }
        }
    }

    func createHousehold(from setup: LedgerSetup, memberOrder: Int32) {
        operationMessage = nil
        householdStore.createFromLocalSafely(
            setup: setup,
            creatorMemberOrder: memberOrder
        ) { [weak self] message in
            DispatchQueue.main.async { self?.operationMessage = message }
        }
    }

    func acceptInvitation(token: String) {
        operationMessage = nil
        householdStore.acceptInvitationSafely(token: token) { [weak self] message in
            DispatchQueue.main.async { self?.operationMessage = message }
        }
    }

    func refreshHouseholds() {
        operationMessage = nil
        householdStore.refreshSafely { [weak self] message in
            DispatchQueue.main.async { self?.operationMessage = message }
        }
    }

    func resetLocalCloudConnection() {
        operationMessage = nil
        synchronizedHouseholdVersion = nil
        householdStore.resetLocalCloudConnectionSafely { [weak self] message in
            DispatchQueue.main.async { self?.operationMessage = message }
        }
    }

    func updateHouseholdIfReady(from setup: LedgerSetup) {
        guard isHouseholdReady else { return }
        householdStore.updateFromLocalSafely(setup: setup) { [weak self] message in
            DispatchQueue.main.async {
                if let message { self?.operationMessage = message }
            }
        }
    }

    func createInvitation(for memberOrder: Int32) {
        operationMessage = nil
        householdStore.createInvitationSafely(targetMemberOrder: memberOrder) { [weak self] message in
            DispatchQueue.main.async { self?.operationMessage = message }
        }
    }

    func revokeInvitation() {
        operationMessage = nil
        householdStore.revokeInvitationSafely { [weak self] message in
            DispatchQueue.main.async { self?.operationMessage = message }
        }
    }

    func synchronizeIfReady() {
        guard isSignedIn, isHouseholdReady, !isSyncing else { return }
        syncStore.synchronizeSafely { [weak self] _, message in
            DispatchQueue.main.async {
                if let message { self?.operationMessage = message }
            }
        }
    }

    func signOut() {
        operationMessage = nil
        authStore.signOutSafely { [weak self] message in
            DispatchQueue.main.async { self?.operationMessage = message }
        }
    }

    func deleteAccount() {
        operationMessage = nil
        authStore.deleteAccountSafely { [weak self] message in
            DispatchQueue.main.async { self?.operationMessage = message }
        }
    }

    func clearOperationMessage() {
        operationMessage = nil
    }

    private func receive(authState state: AuthSessionState) {
        authState = state
        let signedIn = state.status == .signedIn
        guard signedIn != wasSignedIn else { return }
        wasSignedIn = signedIn
        synchronizedHouseholdVersion = nil

        if signedIn {
            appleChallenge = nil
            isPreparingAppleSignIn = false
            isCompletingAppleSignIn = false
            householdStore.restoreAuthenticatedSafely { [weak self] message in
                DispatchQueue.main.async {
                    if let message { self?.operationMessage = message }
                }
            }
        } else {
            householdStore.resetSignedOutSafely()
        }
    }

    private func receive(householdState state: HouseholdSessionState) {
        householdState = state
        guard state.status == .ready, let household = state.activeHousehold else {
            synchronizedHouseholdVersion = nil
            return
        }
        let key = "\(String(describing: household.householdId))|\(household.version)"
        guard synchronizedHouseholdVersion != key else { return }
        synchronizedHouseholdVersion = key
        synchronizeIfReady()
    }

    private func failAppleAuthorization(_ message: String?) {
        appleChallenge = nil
        isPreparingAppleSignIn = false
        isCompletingAppleSignIn = false
        operationMessage = message
        authStore.cancelSignInSafely()
    }
}

struct MoaLogLoginView: View {
    @ObservedObject var model: CloudSessionViewModel

    var body: some View {
        ZStack {
            MoaLogColor.canvas.ignoresSafeArea()
            VStack(spacing: 20) {
                Image(systemName: "wallet.bifold.fill")
                    .font(.system(size: 34, weight: .semibold))
                    .foregroundStyle(MoaLogColor.teal)
                    .frame(width: 76, height: 76)
                    .background(MoaLogColor.secondaryContainer, in: Circle())
                    .accessibilityHidden(true)

                VStack(spacing: 7) {
                    Text("모아로그")
                        .font(MoaLogFont.bold(28, relativeTo: .title))
                        .foregroundStyle(MoaLogColor.teal)
                    Text("둘이 함께 기록하는 부부가계부")
                        .font(MoaLogFont.regular(16))
                        .foregroundStyle(MoaLogColor.mutedInk)
                }

                Spacer().frame(height: 12)

                ZStack {
                    SignInWithAppleButton(
                        .continue,
                        onRequest: model.configureAppleRequest,
                        onCompletion: model.completeAppleAuthorization
                    )
                    .signInWithAppleButtonStyle(.black)
                    .frame(height: 52)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                    .allowsHitTesting(model.canRequestAppleAuthorization)
                    .opacity(model.canRequestAppleAuthorization ? 1 : 0.55)
                    .accessibilityLabel("Apple 계정으로 로그인")

                    if model.isPreparingAppleSignIn || model.isCompletingAppleSignIn {
                        ProgressView()
                            .tint(.white)
                            .accessibilityLabel("로그인 준비 중")
                    }
                }

                cloudMessage(model.operationMessage ?? model.authState.errorMessage)

                if !model.isPreparingAppleSignIn && !model.isCompletingAppleSignIn && model.appleChallenge == nil {
                    Button("로그인 다시 준비", action: model.prepareAppleSignIn)
                        .font(MoaLogFont.semibold(14))
                        .foregroundStyle(MoaLogColor.teal)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(MoaLogColor.cardBorder))
                }

                Text("로그인하면 두 사람의 기록을 안전하게 동기화할 수 있어요.")
                    .font(MoaLogFont.regular(12, relativeTo: .caption))
                    .foregroundStyle(MoaLogColor.mutedInk)
                    .multilineTextAlignment(.center)
            }
            .padding(24)
            .frame(maxWidth: 480)
        }
        .onAppear(perform: model.prepareAppleSignIn)
    }
}

struct HouseholdConnectionView: View {
    @ObservedObject var model: CloudSessionViewModel
    let localSetup: LedgerSetup?
    @State private var invitationToken = ""
    @State private var showsReconnectConfirmation = false

    private var isBusy: Bool { model.householdState.status == .loading }

    var body: some View {
        ZStack {
            MoaLogColor.canvas.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("함께 쓸 가계부 연결")
                            .font(MoaLogFont.bold(24, relativeTo: .title2))
                            .foregroundStyle(MoaLogColor.teal)
                        Text("새 가계부를 만들거나 상대방이 보낸 초대 코드를 입력해 주세요.")
                            .font(MoaLogFont.regular(14))
                            .foregroundStyle(MoaLogColor.mutedInk)
                    }

                    if model.householdState.requiresLocalReconnection {
                        cloudCard(title: "이전 연결 확인", symbol: "exclamationmark.triangle") {
                            Text("현재 계정은 이 기기에 연결돼 있던 가계부에 접근할 수 없어요. 다른 가계부를 연결하려면 이 기기의 현재 기록을 삭제해야 합니다.")
                                .font(MoaLogFont.regular(12, relativeTo: .caption))
                                .foregroundStyle(MoaLogColor.mutedInk)
                            Button("기기 기록 삭제 후 다시 설정") {
                                showsReconnectConfirmation = true
                            }
                            .font(MoaLogFont.semibold(14))
                            .foregroundStyle(.white)
                            .frame(maxWidth: .infinity, minHeight: 50)
                            .background(MoaLogColor.teal, in: RoundedRectangle(cornerRadius: 12))
                        }
                    }

                    if !model.householdState.availableHouseholds.isEmpty {
                        cloudCard(title: "내 가계부", symbol: "person.2.fill") {
                            ForEach(Array(model.householdState.availableHouseholds.enumerated()), id: \.offset) { _, household in
                                Button { model.connect(household) } label: {
                                    HStack {
                                        VStack(alignment: .leading, spacing: 3) {
                                            Text(household.name).font(MoaLogFont.semibold(14))
                                            Text("\(household.baseYear)년 기준")
                                                .font(MoaLogFont.regular(11, relativeTo: .caption))
                                                .foregroundStyle(MoaLogColor.mutedInk)
                                        }
                                        Spacer()
                                        Text("연결").font(MoaLogFont.semibold(13))
                                    }
                                    .foregroundStyle(MoaLogColor.teal)
                                    .padding(.horizontal, 14)
                                    .frame(minHeight: 54)
                                    .background(MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 12))
                                }
                                .buttonStyle(.plain)
                                .disabled(isBusy || model.householdState.requiresLocalReconnection)
                            }
                        }
                    }

                    if let setup = localSetup {
                        cloudCard(title: "새 가계부 만들기", symbol: "wallet.bifold.fill") {
                            Text("이 계정이 누구인지 선택하면 현재 기기의 기록을 서버에 연결해요.")
                                .font(MoaLogFont.regular(12, relativeTo: .caption))
                                .foregroundStyle(MoaLogColor.mutedInk)

                            HStack(spacing: 10) {
                                ForEach(Array(setup.members.sorted { $0.order < $1.order }.enumerated()), id: \.offset) { _, member in
                                    Button("내가 \(member.displayName)") {
                                        model.createHousehold(from: setup, memberOrder: member.order)
                                    }
                                    .font(MoaLogFont.semibold(13))
                                    .foregroundStyle(.white)
                                    .frame(maxWidth: .infinity, minHeight: 50)
                                    .background(MoaLogColor.teal, in: RoundedRectangle(cornerRadius: 12))
                                    .disabled(isBusy || model.householdState.requiresLocalReconnection)
                                }
                            }
                        }
                    }

                    cloudCard(title: "초대 코드로 참여", symbol: "link") {
                        TextField("초대 코드", text: $invitationToken)
                            .font(MoaLogFont.regular(15))
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .padding(.horizontal, 14)
                            .frame(minHeight: 52)
                            .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(MoaLogColor.cardBorder))
                            .disabled(isBusy || model.householdState.requiresLocalReconnection)
                            .onChange(of: invitationToken) { _, value in
                                invitationToken = String(value.trimmingCharacters(in: .whitespacesAndNewlines).prefix(512))
                            }

                        Button("초대받은 가계부 연결") {
                            model.acceptInvitation(token: invitationToken)
                        }
                        .font(MoaLogFont.semibold(14))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity, minHeight: 50)
                        .background(MoaLogColor.teal, in: RoundedRectangle(cornerRadius: 12))
                        .disabled(isBusy || model.householdState.requiresLocalReconnection || invitationToken.isEmpty)
                    }

                    cloudMessage(model.operationMessage ?? model.householdState.errorMessage)

                    if model.householdState.status == .failed {
                        Button("다시 불러오기", action: model.refreshHouseholds)
                            .font(MoaLogFont.semibold(14))
                            .foregroundStyle(MoaLogColor.teal)
                            .frame(maxWidth: .infinity, minHeight: 48)
                            .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(MoaLogColor.cardBorder))
                    }

                    if isBusy {
                        ProgressView("가계부를 연결하는 중이에요")
                            .font(MoaLogFont.medium(12, relativeTo: .caption))
                            .tint(MoaLogColor.teal)
                            .frame(maxWidth: .infinity)
                    }

                    Divider()
                    Button("다른 계정으로 로그인", action: model.signOut)
                        .font(MoaLogFont.semibold(14))
                        .foregroundStyle(MoaLogColor.mutedInk)
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 28)
                .frame(maxWidth: 480)
                .frame(maxWidth: .infinity)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .alert("이 기기의 기록을 삭제할까요?", isPresented: $showsReconnectConfirmation) {
            Button("취소", role: .cancel) {}
            Button("기기 기록 삭제", role: .destructive, action: model.resetLocalCloudConnection)
        } message: {
            Text("다른 가계부의 기록이 섞이지 않도록 이 기기의 현재 가계부 기록과 클라우드 연결을 모두 삭제합니다.")
        }
    }
}

@ViewBuilder
private func cloudMessage(_ message: String?) -> some View {
    if let message, !message.isEmpty {
        Label(message, systemImage: "exclamationmark.circle")
            .font(MoaLogFont.medium(12, relativeTo: .caption))
            .foregroundStyle(MoaLogColor.error)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityLabel("오류: \(message)")
    }
}

private func cloudCard<Content: View>(
    title: String,
    symbol: String,
    @ViewBuilder content: () -> Content
) -> some View {
    VStack(alignment: .leading, spacing: 14) {
        Label(title, systemImage: symbol)
            .font(MoaLogFont.bold(16, relativeTo: .headline))
            .foregroundStyle(MoaLogColor.ink)
        content()
    }
    .padding(16)
    .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 16))
    .overlay(RoundedRectangle(cornerRadius: 16).stroke(MoaLogColor.cardBorder))
}
