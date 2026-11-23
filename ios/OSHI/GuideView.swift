//
//  GuideView.swift
//  OSHI — the two things people keep asking how to do.
//
//  __IN_APP_GUIDES_2026_11_02__ Asked for after "a lot of people are asking how
//  to add a contact", and for a Cashu walkthrough "like in bitchat".
//
//  Written as steps somebody can follow while holding the phone, not as a
//  description of a feature. Shared string keys with Android, same numbering,
//  so the two apps teach the same thing in the same order.
//
//  ⚠️ THE CASHU GUIDE STATES THE BEARER RISK AS A STEP, NOT A FOOTNOTE.
//  OSHI has no wallet and never holds anyone's money: it recognises a token,
//  previews the amount and hands off to a wallet. A Cashu token is cash —
//  whoever redeems it first keeps it, and OSHI cannot ask the mint whether it
//  has already been spent. The wording follows what the chip already tells the
//  user (`cashu_bearer_note`, `cashu_not_verified`) rather than a smoother
//  version that would read better and cost somebody their money.
//

import SwiftUI

struct GuideView: View {
    enum Guide {
        case addContact, cashu

        var titleKey: String {
            switch self {
            case .addContact: return "guide.add_contact.title"
            case .cashu: return "guide.cashu.title"
            }
        }
        var stepKeys: [String] {
            switch self {
            case .addContact:
                return (1...4).map { "guide.add_contact.s\($0)" }
            case .cashu:
                return (1...6).map { "guide.cashu.s\($0)" }   // __CASHU_GUIDE_SIX_STEPS_2026_11_02__ wallet + funding first
            }
        }
    }

    let guide: Guide

    var body: some View {
        List {
            Section {
                ForEach(Array(guide.stepKeys.enumerated()), id: \.offset) { index, key in
                    HStack(alignment: .top, spacing: 14) {
                        Text("\(index + 1)")
                            .font(.caption).bold()
                            .frame(width: 26, height: 26)
                            .background(Color.accentColor.opacity(0.15))
                            .clipShape(Circle())
                        Text(key.localized)
                            .font(.subheadline)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(.vertical, 2)
                }
            }

            if guide == .cashu {
                Section {
                    Label {
                        Text("guide.cashu.warning".localized)
                            .font(.footnote)
                            .fixedSize(horizontal: false, vertical: true)
                    } icon: {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundColor(.orange)
                    }
                    Link("guide.cashu.learn_more".localized,
                         destination: URL(string: "https://cashu.space")!)
                }
            }
        }
        .navigationTitle(guide.titleKey.localized)
        .navigationBarTitleDisplayMode(.inline)
    }
}
