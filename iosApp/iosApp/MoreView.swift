import SharedKit
import SwiftUI

enum GuideDestination {
    case salaryAllocation
    case fixedCostCheck
    case monthlyPlan
    case records
    case assets
}

private enum MoreRoute: String, Identifiable {
    case maintenance, items, settings, guide
    var id: String { rawValue }
}

struct MoreTabView: View {
    let dependencies: IosDependencies
    let setup: LedgerSetup
    let onGuideDestination: (GuideDestination) -> Void

    @EnvironmentObject private var cloud: CloudSessionViewModel
    @State private var route: MoreRoute?
    @State private var pendingGuideDestination: GuideDestination?
    @State private var showsDeleteAccountConfirmation = false

    var body: some View {
        VStack(spacing: 0) {
            header
            ScrollView {
                VStack(spacing: 18) {
                    ledgerCard
                    cloudSection
                    section("생활 관리", trailing: "정기 지출 & 지출") {
                        actionRow("관리비 현황", symbol: "building.2", detail: "매월 고지서 내역을 직접 입력하고 증감률을 비교합니다") { route = .maintenance }
                        actionRow("항목 관리", symbol: "slider.horizontal.3", detail: "수입·지출·저축 카테고리를 직접 구성하고 관리합니다") { route = .items }
                    }
                    section("가이드 및 지원", trailing: "규칙 및 도움말") {
                        actionRow("사용 가이드", symbol: "book.closed", detail: "월급 배분부터 자산 점검까지 5단계 사용법") { route = .guide }
                        actionRow("가계부 기본 설정", symbol: "gearshape", detail: "가계부 명칭과 두 사람의 이름을 수정합니다") { route = .settings }
                    }
                    footer
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
                .frame(maxWidth: 480)
                .frame(maxWidth: .infinity)
            }
        }
        .background(MoaLogColor.homeCanvas)
        .fullScreenCover(item: $route, onDismiss: openPendingGuideDestination) { selected in
            switch selected {
            case .maintenance:
                MaintenanceView(dependencies: dependencies, initialMonth: currentMonth, onClose: { route = nil })
            case .items:
                ItemManagementView(dependencies: dependencies, setup: setup, onClose: { route = nil })
            case .settings:
                LedgerSettingsView(dependencies: dependencies, setup: setup, onClose: { route = nil })
            case .guide:
                GuideView(onClose: { route = nil }) { destination in
                    pendingGuideDestination = destination
                    route = nil
                }
            }
        }
        .alert("모아로그 계정을 삭제할까요?", isPresented: $showsDeleteAccountConfirmation) {
            Button("취소", role: .cancel) {}
            Button("계정 삭제", role: .destructive, action: cloud.deleteAccount)
        } message: {
            Text("서버에 저장된 계정과 공동 가계부 접근 권한이 삭제됩니다. 이 기기에 저장된 기록은 남으며, 서버 계정 삭제는 되돌릴 수 없어요.")
        }
        .onAppear(perform: cloud.refreshHouseholds)
    }

    private var header: some View {
        HStack {
            Text("모아로그").font(MoaLogFont.bold(17, relativeTo: .headline)).foregroundStyle(MoaLogColor.teal)
            Circle().fill(MoaLogColor.teal.opacity(0.35)).frame(width: 6, height: 6)
            Spacer()
            Text(memberNames).font(MoaLogFont.semibold(11, relativeTo: .caption))
                .padding(.horizontal, 10).frame(minHeight: 32)
                .background(MoaLogColor.secondaryContainer, in: Capsule())
        }
        .padding(.horizontal, 18).frame(minHeight: 48)
        .overlay(alignment: .bottom) { Rectangle().fill(MoaLogColor.homeBorder).frame(height: 1) }
    }

    private var ledgerCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: "heart.fill").foregroundStyle(MoaLogColor.teal).frame(width: 48, height: 48)
                    .background(MoaLogColor.secondaryContainer, in: Circle())
                VStack(alignment: .leading, spacing: 3) {
                    Text(setup.ledgerName).font(MoaLogFont.bold(17, relativeTo: .headline)).foregroundStyle(MoaLogColor.teal)
                    Text("\(String(setup.baseYear))년 기준 · \(memberNames)").font(MoaLogFont.regular(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
                }
                Spacer()
            }
            Button { route = .settings } label: {
                HStack { Label("가계부 기본 정보 수정", systemImage: "tune"); Spacer(); Image(systemName: "chevron.right") }
                    .font(MoaLogFont.semibold(11, relativeTo: .caption)).foregroundStyle(MoaLogColor.teal)
                    .padding(.horizontal, 12).frame(minHeight: 44).background(MoaLogColor.surfaceLow, in: RoundedRectangle(cornerRadius: 10))
            }.buttonStyle(.plain)
        }
        .padding(16).moreCard().accessibilityElement(children: .contain)
    }

    private var cloudSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("계정 및 동기화").font(MoaLogFont.bold(14, relativeTo: .headline))
                Spacer()
                Text(syncLabel)
                    .font(MoaLogFont.medium(9, relativeTo: .caption2))
                    .foregroundStyle(cloud.isSyncing ? MoaLogColor.teal : MoaLogColor.mutedInk)
            }

            VStack(spacing: 0) {
                HStack(spacing: 12) {
                    Image(systemName: "person.crop.circle.fill")
                        .font(.system(size: 22))
                        .foregroundStyle(MoaLogColor.teal)
                        .frame(width: 40, height: 40)
                        .background(MoaLogColor.secondaryContainer, in: Circle())
                    VStack(alignment: .leading, spacing: 2) {
                        Text(cloud.authState.user?.displayName ?? "로그인한 계정")
                            .font(MoaLogFont.semibold(13))
                            .foregroundStyle(MoaLogColor.ink)
                        if let email = cloud.authState.user?.email {
                            Text(email)
                                .font(MoaLogFont.regular(10, relativeTo: .caption2))
                                .foregroundStyle(MoaLogColor.mutedInk)
                        }
                    }
                    Spacer()
                }
                .padding(12)

                Divider().padding(.leading, 64)

                Button(action: cloud.synchronizeIfReady) {
                    HStack(spacing: 12) {
                        Image(systemName: "arrow.triangle.2.circlepath")
                            .foregroundStyle(MoaLogColor.teal)
                            .frame(width: 38, height: 38)
                            .background(MoaLogColor.secondaryContainer, in: Circle())
                        VStack(alignment: .leading, spacing: 3) {
                            Text("지금 동기화").font(MoaLogFont.semibold(13))
                            Text(syncDetail)
                                .font(MoaLogFont.regular(9, relativeTo: .caption2))
                                .foregroundStyle(MoaLogColor.mutedInk)
                        }
                        Spacer()
                        if cloud.isSyncing {
                            ProgressView().tint(MoaLogColor.teal)
                        } else {
                            Image(systemName: "chevron.right").foregroundStyle(MoaLogColor.mutedInk)
                        }
                    }
                    .padding(.horizontal, 12)
                    .frame(minHeight: 68)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(cloud.isSyncing)

                Divider().padding(.leading, 64)
                invitationRow

                Divider().padding(.leading, 64)

                Button(action: cloud.signOut) {
                    moreActionLabel("로그아웃", symbol: "rectangle.portrait.and.arrow.right", color: MoaLogColor.mutedInk)
                }
                .buttonStyle(.plain)

                Divider().padding(.leading, 64)

                Button(role: .destructive) { showsDeleteAccountConfirmation = true } label: {
                    moreActionLabel("계정 삭제", symbol: "person.crop.circle.badge.minus", color: MoaLogColor.error)
                }
                .buttonStyle(.plain)
            }
            .moreCard()

            if let message = cloud.operationMessage ?? cloud.householdState.errorMessage {
                Label(message, systemImage: "exclamationmark.circle")
                    .font(MoaLogFont.medium(10, relativeTo: .caption2))
                    .foregroundStyle(MoaLogColor.error)
            }
        }
    }

    @ViewBuilder
    private var invitationRow: some View {
        if let invitation = cloud.householdState.invitation {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 12) {
                    Image(systemName: "link.badge.plus")
                        .foregroundStyle(MoaLogColor.teal)
                        .frame(width: 38, height: 38)
                        .background(MoaLogColor.secondaryContainer, in: Circle())
                    VStack(alignment: .leading, spacing: 3) {
                        Text("초대 코드가 준비됐어요").font(MoaLogFont.semibold(13))
                        Text(invitation.invitationToken)
                            .font(MoaLogFont.medium(9, relativeTo: .caption2))
                            .foregroundStyle(MoaLogColor.mutedInk)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                    Spacer()
                }

                HStack(spacing: 10) {
                    ShareLink(item: invitation.invitationToken) {
                        Label("초대 코드 공유", systemImage: "square.and.arrow.up")
                            .font(MoaLogFont.semibold(12, relativeTo: .caption))
                            .foregroundStyle(.white)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .background(MoaLogColor.teal, in: RoundedRectangle(cornerRadius: 10))
                    }
                    Button("초대 취소", action: cloud.revokeInvitation)
                        .font(MoaLogFont.semibold(12, relativeTo: .caption))
                        .foregroundStyle(MoaLogColor.error)
                        .frame(minHeight: 44)
                        .padding(.horizontal, 12)
                        .background(MoaLogColor.errorSurface.opacity(0.55), in: RoundedRectangle(cornerRadius: 10))
                }
            }
            .padding(12)
        } else if cloud.householdState.activeHousehold == nil {
            HStack(spacing: 12) {
                Image(systemName: "icloud.slash")
                    .foregroundStyle(MoaLogColor.mutedInk)
                    .frame(width: 38, height: 38)
                    .background(MoaLogColor.surfaceLow, in: Circle())
                VStack(alignment: .leading, spacing: 3) {
                    Text("배우자 연결 상태 확인 필요").font(MoaLogFont.semibold(13))
                    Text("인터넷에 연결되면 구성원 상태를 확인할 수 있어요")
                        .font(MoaLogFont.regular(9, relativeTo: .caption2))
                        .foregroundStyle(MoaLogColor.mutedInk)
                }
                Spacer()
            }
            .padding(.horizontal, 12)
            .frame(minHeight: 68)
        } else if let member = unlinkedMember,
                  cloud.householdState.activeHousehold?.currentUserRole == .owner {
            Button { cloud.createInvitation(for: member.order) } label: {
                HStack(spacing: 12) {
                    Image(systemName: "person.badge.plus")
                        .foregroundStyle(MoaLogColor.teal)
                        .frame(width: 38, height: 38)
                        .background(MoaLogColor.secondaryContainer, in: Circle())
                    VStack(alignment: .leading, spacing: 3) {
                        Text("\(member.displayName) 초대하기").font(MoaLogFont.semibold(13))
                        Text("함께 기록할 수 있는 초대 코드를 만들어요")
                            .font(MoaLogFont.regular(9, relativeTo: .caption2))
                            .foregroundStyle(MoaLogColor.mutedInk)
                    }
                    Spacer()
                    Image(systemName: "chevron.right").foregroundStyle(MoaLogColor.mutedInk)
                }
                .padding(.horizontal, 12)
                .frame(minHeight: 68)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        } else {
            HStack(spacing: 12) {
                Image(systemName: "person.2.fill")
                    .foregroundStyle(MoaLogColor.teal)
                    .frame(width: 38, height: 38)
                    .background(MoaLogColor.secondaryContainer, in: Circle())
                VStack(alignment: .leading, spacing: 3) {
                    Text("두 사람 연결 완료").font(MoaLogFont.semibold(13))
                    Text("공동 가계부 변경 사항을 함께 동기화해요")
                        .font(MoaLogFont.regular(9, relativeTo: .caption2))
                        .foregroundStyle(MoaLogColor.mutedInk)
                }
                Spacer()
            }
            .padding(.horizontal, 12)
            .frame(minHeight: 68)
        }
    }

    private func moreActionLabel(_ title: String, symbol: String, color: Color) -> some View {
        HStack(spacing: 12) {
            Image(systemName: symbol).foregroundStyle(color).frame(width: 38, height: 38)
            Text(title).font(MoaLogFont.semibold(13)).foregroundStyle(color)
            Spacer()
            Image(systemName: "chevron.right").foregroundStyle(MoaLogColor.mutedInk)
        }
        .padding(.horizontal, 12)
        .frame(minHeight: 58)
        .contentShape(Rectangle())
    }

    private var unlinkedMember: ContractsLedgerMemberDto? {
        cloud.householdState.activeHousehold?.members.first { $0.linkedUserId == nil }
    }

    private var syncLabel: String {
        cloud.isSyncing ? "동기화 중" : "클라우드 연결"
    }

    private var syncDetail: String {
        guard let finished = cloud.syncStatus as? SyncStatusFinished else {
            return cloud.isSyncing ? "변경 사항을 안전하게 반영하고 있어요" : "기기의 변경 사항을 바로 반영합니다"
        }
        if let completed = finished.result as? SyncRunResultCompleted {
            return "올림 \(completed.pushedMutations)건 · 내려받음 \(completed.pulledChanges)건"
        }
        if let retry = finished.result as? SyncRunResultRetryScheduled {
            return "연결되면 다시 시도해요 · \(retry.reason)"
        }
        if let failed = finished.result as? SyncRunResultFailed {
            return "동기화 중단 · \(failed.reason)"
        }
        return "로그인과 가계부 연결을 확인해 주세요"
    }

    private func section<Content: View>(_ title: String, trailing: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack { Text(title).font(MoaLogFont.bold(14, relativeTo: .headline)); Spacer(); Text(trailing).font(MoaLogFont.regular(9, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk) }
            VStack(spacing: 0) { content() }.moreCard()
        }
    }

    private func actionRow(_ title: String, symbol: String, detail: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: symbol).foregroundStyle(MoaLogColor.teal).frame(width: 38, height: 38).background(MoaLogColor.secondaryContainer, in: Circle())
                VStack(alignment: .leading, spacing: 3) { Text(title).font(MoaLogFont.semibold(13)); Text(detail).font(MoaLogFont.regular(9, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk).lineLimit(2) }
                Spacer(); Image(systemName: "chevron.right").foregroundStyle(MoaLogColor.mutedInk)
            }.padding(.horizontal, 12).frame(minHeight: 72).contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityHint("화면을 엽니다")
    }

    private var footer: some View {
        VStack(spacing: 5) {
            Text("모아로그 부부가계부").font(MoaLogFont.semibold(10, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
            Text("두 사람이 같은 기준으로 계획하고 기록하는 가계부").font(MoaLogFont.regular(9, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
            Text("버전 \(appVersion)").font(MoaLogFont.regular(8, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk)
        }.padding(.vertical, 12).frame(maxWidth: .infinity)
    }

    private var memberNames: String { setup.members.sorted { $0.order < $1.order }.map(\.displayName).joined(separator: " · ") }
    private var currentMonth: YearMonthKey {
        let calendar = Calendar.current
        let now = Date()
        return YearMonthKey(
            year: Int32(calendar.component(.year, from: now)),
            month: Int32(calendar.component(.month, from: now))
        )
    }
    private var appVersion: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0" }

    private func openPendingGuideDestination() {
        guard let destination = pendingGuideDestination else { return }
        pendingGuideDestination = nil
        onGuideDestination(destination)
    }
}

private extension View {
    func moreCard() -> some View { background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 14)).overlay(RoundedRectangle(cornerRadius: 14).stroke(MoaLogColor.cardBorder)) }
}
