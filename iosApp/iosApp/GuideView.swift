import SwiftUI

private struct GuideStep: Identifiable {
    let id: Int
    let title: String
    let subtitle: String
    let detail: String
    let symbol: String
    let destination: GuideDestination
}

private struct GuideFaq: Identifiable {
    let id: Int
    let question: String
    let answer: String
}

struct GuideView: View {
    let onClose: () -> Void
    let onOpen: (GuideDestination) -> Void
    @State private var expanded: Set<Int> = [1]
    @State private var expandedFaq: Int? = 1

    private let steps = [
        GuideStep(id: 1, title: "월급 배분", subtitle: "수입이 확정되면 먼저", detail: "두 사람의 월급을 입력하고 생활비, 고정비, 저축 등 큰 항목에 사용할 금액을 배분해요.", symbol: "wonsign.circle", destination: .salaryAllocation),
        GuideStep(id: 2, title: "고정비 점검", subtitle: "매달 반복되는 지출 확인", detail: "주거비, 공과금, 구독료처럼 반복되는 항목을 확인하고 선택한 항목을 이번 달 계획에 적용해요.", symbol: "checklist", destination: .fixedCostCheck),
        GuideStep(id: 3, title: "월별 계획", subtitle: "수입·고정·저축 구성", detail: "수입, 고정지출, 저축 탭에서 이번 달에 사용할 금액과 상태를 입력해 한 달 계획을 완성해요.", symbol: "calendar", destination: .monthlyPlan),
        GuideStep(id: 4, title: "지출 기록", subtitle: "발생한 변동지출 기록", detail: "실제 지출을 카테고리와 날짜별로 기록해요. 과소비 표시는 예산으로 자동 판정되지 않으며, 지출을 입력하거나 수정할 때 사용자가 직접 선택합니다.", symbol: "square.and.pencil", destination: .records),
        GuideStep(id: 5, title: "저축 · 자산 확인", subtitle: "월말에 잔액 점검", detail: "저축 계획과 실제 자산 잔액을 함께 확인하고, 직접 입력한 확정값과 규칙으로 계산된 예상값을 구분해 점검해요.", symbol: "chart.line.uptrend.xyaxis", destination: .assets),
    ]

    private let faqs = [
        GuideFaq(id: 1, question: "귀속 월과 실제 지출일은 어떻게 다른가요?", answer: "귀속 월은 월별 합계에 반영할 달이고, 실제 지출일은 돈을 쓴 날짜예요. 카드 결제처럼 두 시점이 다르면 각각 입력할 수 있어요."),
        GuideFaq(id: 2, question: "예상과 확정은 무엇인가요?", answer: "예상은 아직 확정되지 않은 계획 또는 계산값이고, 확정은 사용자가 직접 입력해 확정한 금액이에요. 화면에서는 두 상태를 구분해 표시해요."),
        GuideFaq(id: 3, question: "과소비는 어떻게 표시하나요?", answer: "지출 기록에서 사용자가 과소비로 직접 표시한 거래만 과소비 합계와 필터에 포함돼요. 금액만으로 자동 판정하지 않아요."),
        GuideFaq(id: 4, question: "목적통장 포함 저축과 순저축의 차이는 무엇인가요?", answer: "목적통장 포함은 특정 지출 목적을 위해 모으는 금액까지 저축률에 포함해요. 순저축은 항목에서 순저축 포함을 켠 금액만 따로 합산해요."),
        GuideFaq(id: 5, question: "저축액과 자산 잔액은 어떻게 다른가요?", answer: "저축액은 해당 월의 계획 흐름이고, 자산 잔액은 사용자가 월별로 직접 입력하거나 증가 규칙으로 계산한 평가액이에요."),
    ]

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(spacing: 16) {
                        intro
                        tableOfContents(proxy)
                        VStack(spacing: 10) { ForEach(steps) { stepCard($0) } }
                        Text("자주 묻는 질문과 계산 기준")
                            .font(MoaLogFont.bold(17, relativeTo: .headline))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.top, 4)
                        VStack(spacing: 10) { ForEach(faqs) { faqCard($0) } }
                    }
                    .padding(16).padding(.bottom, 20).frame(maxWidth: 480).frame(maxWidth: .infinity)
                }
            }
            .background(MoaLogColor.homeCanvas.ignoresSafeArea())
            .navigationTitle("사용 가이드").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarLeading) { Button(action: onClose) { Image(systemName: "chevron.left").frame(width: 44, height: 44) }.accessibilityLabel("더보기로") } }
        }
    }

    private var intro: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "tablecells").foregroundStyle(MoaLogColor.teal).frame(width: 42, height: 42).background(MoaLogColor.secondaryContainer, in: Circle())
            VStack(alignment: .leading, spacing: 5) {
                Text("엑셀에서 모아로그로").font(MoaLogFont.bold(9, relativeTo: .caption2)).foregroundStyle(MoaLogColor.teal).padding(.horizontal, 7).padding(.vertical, 3).background(MoaLogColor.secondaryContainer, in: Capsule())
                Text("복잡한 시트 없이, 부부의 템포로 시작해요").font(MoaLogFont.bold(17, relativeTo: .headline))
                Text("매월 같은 순서로 계획하고 기록할 수 있도록 핵심 흐름을 다섯 단계로 정리했어요.").font(MoaLogFont.regular(11, relativeTo: .caption)).foregroundStyle(MoaLogColor.mutedInk)
            }
        }.padding(16).guideCard().accessibilityElement(children: .combine)
    }

    private func tableOfContents(_ proxy: ScrollViewProxy) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("이 순서로 시작해보세요").font(MoaLogFont.bold(17, relativeTo: .headline))
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) { ForEach(steps) { step in Button("\(step.id). \(step.title)") { withAnimation { proxy.scrollTo(step.id, anchor: .top) }; expanded.insert(step.id) }.font(MoaLogFont.semibold(10, relativeTo: .caption2)).padding(.horizontal, 10).frame(minHeight: 44).background(MoaLogColor.surface, in: Capsule()).overlay(Capsule().stroke(MoaLogColor.cardBorder)) } }
            }
        }
    }

    private func stepCard(_ step: GuideStep) -> some View {
        VStack(spacing: 0) {
            Button { withAnimation { if expanded.contains(step.id) { expanded.remove(step.id) } else { expanded.insert(step.id) } } } label: {
                HStack(spacing: 12) {
                    Text("\(step.id)").font(MoaLogFont.bold(12)).foregroundStyle(.white).frame(width: 32, height: 32).background(MoaLogColor.teal, in: Circle())
                    Image(systemName: step.symbol).foregroundStyle(MoaLogColor.teal).frame(width: 24)
                    VStack(alignment: .leading, spacing: 2) { Text(step.title).font(MoaLogFont.bold(14)); Text(step.subtitle).font(MoaLogFont.regular(9, relativeTo: .caption2)).foregroundStyle(MoaLogColor.mutedInk) }
                    Spacer(); Image(systemName: expanded.contains(step.id) ? "chevron.up" : "chevron.down").foregroundStyle(MoaLogColor.mutedInk)
                }.padding(14).frame(minHeight: 64).contentShape(Rectangle())
            }.buttonStyle(.plain).accessibilityValue(expanded.contains(step.id) ? "펼쳐짐" : "접힘")
            if expanded.contains(step.id) {
                VStack(alignment: .leading, spacing: 10) {
                    Text(step.detail).font(MoaLogFont.regular(11, relativeTo: .caption)).foregroundStyle(MoaLogColor.mutedInk).fixedSize(horizontal: false, vertical: true)
                    Button { onOpen(step.destination) } label: { HStack { Text("화면 바로가기"); Spacer(); Image(systemName: "arrow.right") }.font(MoaLogFont.semibold(11, relativeTo: .caption)).foregroundStyle(MoaLogColor.teal).frame(minHeight: 44) }
                        .buttonStyle(.plain)
                }.padding(.horizontal, 16).padding(.bottom, 14)
            }
        }.guideCard().id(step.id)
    }

    private func faqCard(_ faq: GuideFaq) -> some View {
        let isExpanded = expandedFaq == faq.id
        return VStack(spacing: 0) {
            Button {
                withAnimation { expandedFaq = isExpanded ? nil : faq.id }
            } label: {
                HStack(spacing: 10) {
                    Text("Q\(faq.id).")
                        .font(MoaLogFont.bold(13, relativeTo: .subheadline))
                        .foregroundStyle(MoaLogColor.teal)
                    Text(faq.question)
                        .font(MoaLogFont.semibold(13, relativeTo: .subheadline))
                        .multilineTextAlignment(.leading)
                    Spacer()
                    Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                        .foregroundStyle(MoaLogColor.mutedInk)
                        .accessibilityHidden(true)
                }
                .padding(14)
                .frame(minHeight: 56)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityValue(isExpanded ? "펼쳐짐" : "접힘")

            if isExpanded {
                Text(faq.answer)
                    .font(MoaLogFont.regular(11, relativeTo: .caption))
                    .foregroundStyle(MoaLogColor.mutedInk)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 14)
            }
        }
        .guideCard()
    }
}

private extension View { func guideCard() -> some View { background(MoaLogColor.surface, in: RoundedRectangle(cornerRadius: 14)).overlay(RoundedRectangle(cornerRadius: 14).stroke(MoaLogColor.cardBorder)) } }
