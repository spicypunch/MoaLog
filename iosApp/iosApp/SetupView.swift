import SharedKit
import SwiftUI

@MainActor
final class SetupViewModel: ObservableObject {
    @Published private(set) var state: SetupUiState

    private let store: IosSetupStore
    private var observation: IosObservation?

    init(dependencies: IosDependencies) {
        store = dependencies.setupStore()
        state = store.currentState
        observation = store.observe(observer: { [weak self] state in
            DispatchQueue.main.async {
                self?.state = state
            }
        })

        if state.baseYear.isEmpty {
            store.changeBaseYear(value: String(Calendar.current.component(.year, from: Date())))
        }
    }

    deinit {
        observation?.cancel()
        store.close()
    }

    func changeLedgerName(_ value: String) { store.changeLedgerName(value: value) }
    func changeFirstMemberName(_ value: String) { store.changeFirstMemberName(value: value) }
    func changeSecondMemberName(_ value: String) { store.changeSecondMemberName(value: value) }
    func changeBaseYear(_ value: String) { store.changeBaseYear(value: value) }

    func changeAnnualSavingsTarget(_ value: String) {
        let digits = value.filter(\.isNumber)
        guard let amount = Int64(digits), !digits.isEmpty else {
            store.changeAnnualSavingsTarget(value: "")
            return
        }
        store.changeAnnualSavingsTarget(value: amount.formatted(.number.grouping(.automatic)))
    }

    func save() { store.save() }
}

struct SetupView: View {
    @StateObject private var model: SetupViewModel
    @FocusState private var focusedField: Field?

    private enum Field: Hashable {
        case ledgerName
        case firstMember
        case secondMember
        case savings
    }

    init(dependencies: IosDependencies) {
        _model = StateObject(wrappedValue: SetupViewModel(dependencies: dependencies))
    }

    var body: some View {
        ZStack {
            MoaLogColor.canvas.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    formCard
                        .padding(.top, 10)
                    adviceCard
                        .padding(.top, 24)
                    submitArea
                        .padding(.top, 32)
                        .padding(.bottom, 40)
                }
                .padding(.horizontal, 20)
                .frame(maxWidth: 480)
                .frame(maxWidth: .infinity)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            GeometryReader { proxy in
                ZStack(alignment: .leading) {
                    MoaLogColor.divider
                    MoaLogColor.teal.frame(width: proxy.size.width * 2 / 3)
                }
            }
            .frame(height: 4)
        }
        .tint(MoaLogColor.teal)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Image(systemName: "leaf.fill")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 32, height: 32)
                    .background(MoaLogColor.teal, in: RoundedRectangle(cornerRadius: 10))
                    .accessibilityHidden(true)

                Text("모아로그")
                    .font(MoaLogFont.bold(18, relativeTo: .headline))
                    .foregroundStyle(MoaLogColor.teal)

                Text("시작하기")
                    .font(MoaLogFont.medium(12, relativeTo: .caption))
                    .foregroundStyle(MoaLogColor.mutedInk)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(MoaLogColor.sage, in: Capsule())
            }
            .padding(.bottom, 8)

            Text("우리의 돈 관리를 시작해요")
                .font(MoaLogFont.bold(24, relativeTo: .title))
                .foregroundStyle(MoaLogColor.ink)

            Text("수입부터 저축까지, 두 사람이 함께 투명하게 정리해요")
                .font(MoaLogFont.regular(15))
                .foregroundStyle(MoaLogColor.mutedInk)
                .padding(.top, 2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, 28)
        .padding(.bottom, 16)
    }

    private var formCard: some View {
        VStack(spacing: 20) {
            setupField(
                label: "가계부 이름",
                icon: "book.closed",
                placeholder: "우리 부부 가계부",
                text: Binding(
                    get: { model.state.ledgerName },
                    set: model.changeLedgerName
                ),
                error: model.state.errors.ledgerName,
                field: .ledgerName
            )

            divider

            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Label {
                        HStack(spacing: 3) {
                            Text("두 사람의 이름")
                            Text("*").foregroundStyle(MoaLogColor.error)
                        }
                    } icon: {
                        Image(systemName: "person.2")
                    }
                    .font(MoaLogFont.semibold(13, relativeTo: .caption))
                    .foregroundStyle(MoaLogColor.ink)

                    Spacer(minLength: 8)

                    Text("동등한 공동 기록")
                        .font(MoaLogFont.bold(11, relativeTo: .caption2))
                        .foregroundStyle(MoaLogColor.mutedInk)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(MoaLogColor.divider, in: RoundedRectangle(cornerRadius: 4))
                }

                HStack(alignment: .top, spacing: 12) {
                    memberField(
                        order: 1,
                        placeholder: "첫 번째 이름",
                        text: Binding(
                            get: { model.state.firstMemberName },
                            set: model.changeFirstMemberName
                        ),
                        error: model.state.errors.firstMemberName,
                        field: .firstMember
                    )
                    memberField(
                        order: 2,
                        placeholder: "두 번째 이름",
                        text: Binding(
                            get: { model.state.secondMemberName },
                            set: model.changeSecondMemberName
                        ),
                        error: model.state.errors.secondMemberName,
                        field: .secondMember
                    )
                }

                Label("각 내역마다 작성자와 부담자를 명확히 분류하기 위해 필수 입력이에요.", systemImage: "info.circle")
                    .font(MoaLogFont.medium(12, relativeTo: .caption))
                    .foregroundStyle(MoaLogColor.mutedInk)
            }

            divider

            VStack(alignment: .leading, spacing: 6) {
                fieldLabel("기준 연도", icon: "calendar")
                Menu {
                    ForEach(yearOptions, id: \.self) { year in
                        Button("\(year)년") { model.changeBaseYear(String(year)) }
                    }
                } label: {
                    HStack {
                        Text(model.state.baseYear.isEmpty ? "연도 선택" : "\(model.state.baseYear)년")
                            .font(MoaLogFont.medium(16))
                            .foregroundStyle(MoaLogColor.ink)
                        Spacer()
                        Image(systemName: "chevron.down")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(MoaLogColor.mutedInk)
                    }
                    .padding(.horizontal, 16)
                    .frame(maxWidth: .infinity, minHeight: 52)
                    .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
                    .overlay(fieldBorder(error: model.state.errors.baseYear))
                }
                .accessibilityLabel("기준 연도")
                errorText(model.state.errors.baseYear)
            }

            divider

            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    fieldLabel("연 저축 목표", icon: "banknote")
                    Spacer()
                    Text("선택 항목")
                        .font(MoaLogFont.medium(12, relativeTo: .caption))
                        .foregroundStyle(MoaLogColor.outline)
                }

                HStack(spacing: 6) {
                    TextField(
                        "0",
                        text: Binding(
                            get: { model.state.annualSavingsTarget },
                            set: model.changeAnnualSavingsTarget
                        )
                    )
                    .keyboardType(.numberPad)
                    .multilineTextAlignment(.trailing)
                    .font(MoaLogFont.semibold(16))
                    .focused($focusedField, equals: .savings)

                    Text("원")
                        .font(MoaLogFont.regular(15))
                        .foregroundStyle(MoaLogColor.mutedInk)
                }
                .padding(.horizontal, 16)
                .frame(minHeight: 52)
                .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
                .overlay(fieldBorder(error: model.state.errors.annualSavingsTarget))
                .accessibilityElement(children: .combine)
                .accessibilityLabel("연 저축 목표, 원")

                errorText(model.state.errors.annualSavingsTarget)

                Label("나중에 홈 화면에서 언제든지 설정하거나 수정할 수 있어요", systemImage: "calendar.badge.clock")
                    .font(MoaLogFont.medium(12, relativeTo: .caption))
                    .foregroundStyle(MoaLogColor.mutedInk)
            }
        }
        .padding(16)
        .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 16))
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(MoaLogColor.cardBorder, lineWidth: 1)
        )
        .shadow(color: MoaLogColor.teal.opacity(0.04), radius: 6, y: 2)
    }

    private var adviceCard: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "heart.fill")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 24, height: 24)
                .background(MoaLogColor.teal, in: Circle())
                .accessibilityHidden(true)

            Text("매달 정기 점검과 목표 공유만으로도 두 사람이 함께하는 완벽한 가계부를 만들 수 있어요.")
                .font(MoaLogFont.regular(14))
                .foregroundStyle(MoaLogColor.ink)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(16)
        .background(MoaLogColor.sage.opacity(0.65), in: RoundedRectangle(cornerRadius: 12))
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(MoaLogColor.outline.opacity(0.25), lineWidth: 1)
        )
    }

    private var submitArea: some View {
        VStack(spacing: 12) {
            if let persistenceError = model.state.persistenceError {
                Text(persistenceError)
                    .font(MoaLogFont.medium(13, relativeTo: .caption))
                    .foregroundStyle(MoaLogColor.error)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityLabel("저장 오류. \(persistenceError)")
            }

            Button(action: model.save) {
                HStack(spacing: 8) {
                    if model.state.isSaving {
                        ProgressView().tint(.white)
                    }
                    Text(model.state.isSaving ? "가계부 공간 생성 중..." : "가계부 시작하기")
                        .font(MoaLogFont.semibold(16))
                    if !model.state.isSaving {
                        Image(systemName: "arrow.right")
                            .font(.system(size: 15, weight: .semibold))
                    }
                }
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, minHeight: 54)
                .background(MoaLogColor.teal, in: RoundedRectangle(cornerRadius: 12))
                .shadow(color: MoaLogColor.teal.opacity(0.18), radius: 8, y: 4)
            }
            .disabled(model.state.isSaving)
            .accessibilityLabel(model.state.isSaving ? "가계부 공간 생성 중" : "가계부 시작하기")

            Text("시작 후 모든 설정은 '더보기' 탭에서 다시 변경 가능합니다")
                .font(MoaLogFont.medium(12, relativeTo: .caption))
                .foregroundStyle(MoaLogColor.outline)
                .multilineTextAlignment(.center)
        }
    }

    private func setupField(
        label: String,
        icon: String,
        placeholder: String,
        text: Binding<String>,
        error: String?,
        field: Field
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            fieldLabel(label, icon: icon)
            TextField(placeholder, text: text)
                .font(MoaLogFont.medium(16))
                .padding(.horizontal, 16)
                .frame(minHeight: 52)
                .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
                .overlay(fieldBorder(error: error))
                .focused($focusedField, equals: field)
                .accessibilityLabel(label)
            errorText(error)
        }
    }

    private func memberField(
        order: Int,
        placeholder: String,
        text: Binding<String>,
        error: String?,
        field: Field
    ) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Text(String(order))
                    .font(MoaLogFont.bold(11, relativeTo: .caption2))
                    .foregroundStyle(MoaLogColor.teal)
                    .frame(width: 20, height: 20)
                    .background(MoaLogColor.sage, in: Circle())
                    .accessibilityHidden(true)
                TextField(placeholder, text: text)
                    .font(MoaLogFont.semibold(15))
                    .focused($focusedField, equals: field)
            }
            .padding(.horizontal, 12)
            .frame(minHeight: 52)
            .background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 12))
            .overlay(fieldBorder(error: error))
            .accessibilityElement(children: .combine)
            .accessibilityLabel(order == 1 ? "첫 번째 사람 이름" : "두 번째 사람 이름")
            errorText(error)
        }
        .frame(maxWidth: .infinity)
    }

    private func fieldLabel(_ title: String, icon: String) -> some View {
        Label(title, systemImage: icon)
            .font(MoaLogFont.semibold(13, relativeTo: .caption))
            .foregroundStyle(MoaLogColor.ink)
    }

    private func fieldBorder(error: String?) -> some View {
        RoundedRectangle(cornerRadius: 12)
            .stroke(error == nil ? MoaLogColor.cardBorder : MoaLogColor.error, lineWidth: error == nil ? 1 : 1.5)
    }

    @ViewBuilder
    private func errorText(_ error: String?) -> some View {
        if let error {
            Text(error)
                .font(MoaLogFont.medium(12, relativeTo: .caption))
                .foregroundStyle(MoaLogColor.error)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityLabel("입력 오류. \(error)")
        }
    }

    private var divider: some View {
        Rectangle()
            .fill(MoaLogColor.divider)
            .frame(height: 1)
            .accessibilityHidden(true)
    }

    private var yearOptions: [Int] {
        let current = Calendar.current.component(.year, from: Date())
        return Array((current - 5)...(current + 5))
    }
}
