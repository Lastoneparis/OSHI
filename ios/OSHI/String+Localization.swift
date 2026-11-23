//
//  String+Localization.swift
//  Easy localization extension - No code changes needed!
//

import Foundation

extension String {
    /// Resolve copy in the active language and then in the bundled English
    /// catalogue. `NSLocalizedString` otherwise returns the key itself, which
    /// turns a missing translation into an internal identifier in the UI.
    private var localizedValue: String {
        let current = Bundle.main.localizedString(forKey: self, value: nil, table: nil)
        guard current == self,
              let englishPath = Bundle.main.path(forResource: "en", ofType: "lproj"),
              let english = Bundle(path: englishPath) else { return current }
        return english.localizedString(forKey: self, value: self, table: nil)
    }

    /// Automatically localize any string. Missing translations fall back to
    /// English; the localisation audit still reports them so this is a safe UX
    /// fallback rather than a substitute for translating the catalogue.
    var localized: String {
        MacVerbs.adapt(localizedValue)
    }

    /// Localize with parameters
    func localized(with arguments: CVarArg...) -> String {
        MacVerbs.adapt(String(format: localizedValue, arguments: arguments))
    }
}

/// Turns touch verbs into pointer verbs on the Mac.
///
/// 57 English strings say "tap", and they are RIGHT on iOS — this is not copy to
/// fix, it is copy that is correct on one platform and wrong on another. Editing
/// all of them across 34 locales would be ~1900 edits and would regress the
/// phone, so the swap happens at the localisation boundary instead: one pair per
/// language, applied only under Catalyst.
///
/// Deliberately narrow:
///   * it runs only on Catalyst; on iOS `adapt` is the identity function and the
///     compiler removes it;
///   * it matches whole words with case preserved, so "Tap" -> "Click" and
///     "tap" -> "click", but "tape" and "tapis" are untouched (see `adapt` —
///     plain substring replacement would ship "clicke" to users);
///   * a language with no entry is returned unchanged rather than guessed at.
///
/// This is a presentation-layer substitution. It does not touch the .strings
/// files, so an actual translation fix still belongs in the .strings file.
enum MacVerbs {

    #if targetEnvironment(macCatalyst)

    /// touch verb -> pointer verb, per language. Ordered longest-first inside a
    /// language so "tap and hold" is rewritten before "tap".
    private static let table: [String: [(String, String)]] = [
        "en": [("tap and hold", "click and hold"), ("Tap and hold", "Click and hold"),
               ("tap", "click"), ("Tap", "Click"), ("TAP", "CLICK")],
        "fr": [("Touchez et maintenez", "Cliquez et maintenez"),
               ("touchez et maintenez", "cliquez et maintenez"),
               ("Appuyez longuement", "Cliquez et maintenez"),
               ("appuyez longuement", "cliquez et maintenez"),
               ("Touchez", "Cliquez"), ("touchez", "cliquez"),
               ("Appuyez", "Cliquez"), ("appuyez", "cliquez")],
        "es": [("Mantén pulsado", "Haz clic y mantén"), ("mantén pulsado", "haz clic y mantén"),
               ("Toca", "Haz clic en"), ("toca", "haz clic en"),
               ("Pulsa", "Haz clic en"), ("pulsa", "haz clic en")],
        "de": [("Tippen und halten", "Klicken und halten"),
               ("tippen und halten", "klicken und halten"),
               ("Tippe", "Klicke"), ("tippe", "klicke"),
               ("Tippen", "Klicken"), ("tippen", "klicken")],
        "it": [("Tieni premuto", "Fai clic e tieni premuto"),
               ("tieni premuto", "fai clic e tieni premuto"),
               ("Tocca", "Fai clic su"), ("tocca", "fai clic su")],
        "pt-BR": [("Toque e mantenha", "Clique e mantenha"),
                  ("toque e mantenha", "clique e mantenha"),
                  ("Toque", "Clique"), ("toque", "clique")],
        "pt-PT": [("Toque e mantenha", "Clique e mantenha"),
                  ("toque e mantenha", "clique e mantenha"),
                  ("Toque", "Clique"), ("toque", "clique")],
        "nl": [("Tik en houd vast", "Klik en houd vast"),
               ("tik en houd vast", "klik en houd vast"),
               ("Tik", "Klik"), ("tik", "klik")],
        "ca": [("Toca", "Fes clic a"), ("toca", "fes clic a")],
        "da": [("Tryk og hold", "Klik og hold"), ("tryk og hold", "klik og hold"),
               ("Tryk", "Klik"), ("tryk", "klik")],
        "nb": [("Trykk og hold", "Klikk og hold"), ("trykk og hold", "klikk og hold"),
               ("Trykk", "Klikk"), ("trykk", "klikk")],
        "sv": [("Tryck och håll", "Klicka och håll"), ("tryck och håll", "klicka och håll"),
               ("Tryck", "Klicka"), ("tryck", "klicka")],
        "fi": [("Napauta ja pidä", "Napsauta ja pidä"),
               ("napauta ja pidä", "napsauta ja pidä"),
               ("Napauta", "Napsauta"), ("napauta", "napsauta")],
        "pl": [("Dotknij i przytrzymaj", "Kliknij i przytrzymaj"),
               ("dotknij i przytrzymaj", "kliknij i przytrzymaj"),
               ("Dotknij", "Kliknij"), ("dotknij", "kliknij"),
               ("Stuknij", "Kliknij"), ("stuknij", "kliknij")],
        "cs": [("Klepněte a držte", "Klikněte a držte"),
               ("klepněte a držte", "klikněte a držte"),
               ("Klepněte", "Klikněte"), ("klepněte", "klikněte")],
        // The "tap and hold" entry here used to map "Kliknite a držte" onto
        // itself — a pair that could never fire. Slovak has no "and hold"
        // string, so the two imperatives are the whole job.
        "sk": [("Ťuknite", "Kliknite"), ("ťuknite", "kliknite")],
        "hu": [("Koppints és tartsd", "Kattints és tartsd"),
               ("koppints és tartsd", "kattints és tartsd"),
               ("Koppints", "Kattints"), ("koppints", "kattints"),
               ("Koppintson", "Kattintson"), ("koppintson", "kattintson")],
        "ro": [("Atinge și ține apăsat", "Fă clic și ține apăsat"),
               ("atinge și ține apăsat", "fă clic și ține apăsat"),
               ("Atinge", "Fă clic pe"), ("atinge", "fă clic pe"),
               ("Apasă", "Fă clic pe"), ("apasă", "fă clic pe")],
        "el": [("Πατήστε παρατεταμένα", "Κάντε κλικ και κρατήστε"),
               ("Πατήστε", "Κάντε κλικ"), ("πατήστε", "κάντε κλικ")],
        // Turkish is agglutinative: the imperative is only one of the forms that
        // occur, so "Dokunun" alone left 22 strings still saying "touch".
        "tr": [("Dokunup basılı tutun", "Tıklayıp basılı tutun"),
               ("dokunup basılı tutun", "tıklayıp basılı tutun"),
               ("Dokunduğunuzda", "Tıkladığınızda"), ("dokunduğunuzda", "tıkladığınızda"),
               ("Dokunarak", "Tıklayarak"), ("dokunarak", "tıklayarak"),
               ("Dokunana", "Tıklayana"), ("dokunana", "tıklayana"),
               ("Dokunuşla", "Tıklamayla"), ("dokunuşla", "tıklamayla"),
               ("Dokunun", "Tıklayın"), ("dokunun", "tıklayın"),
               ("Dokun", "Tıkla"), ("dokun", "tıkla")],
        "ru": [("Нажмите и удерживайте", "Щёлкните и удерживайте"),
               ("нажмите и удерживайте", "щёлкните и удерживайте"),
               ("Коснитесь", "Щёлкните"), ("коснитесь", "щёлкните"),
               ("Нажмите", "Щёлкните"), ("нажмите", "щёлкните")],
        "uk": [("Натисніть і утримуйте", "Клацніть і утримуйте"),
               ("натисніть і утримуйте", "клацніть і утримуйте"),
               ("Торкніться", "Клацніть"), ("торкніться", "клацніть"),
               ("Натисніть", "Клацніть"), ("натисніть", "клацніть")],
        "he": [("הקישו והחזיקו", "לחצו והחזיקו"),
               ("הקישו", "לחצו"), ("הקש", "לחץ")],
        "ar": [("اضغط مع الاستمرار", "انقر مع الاستمرار"),
               ("المس", "انقر"), ("اضغط", "انقر")],
        "fa": [("لمس کنید و نگه دارید", "کلیک کنید و نگه دارید"),
               ("ضربه بزنید", "کلیک کنید"), ("لمس کنید", "کلیک کنید"),
               ("ضربه زدن", "کلیک کردن"), ("یک ضربه", "یک کلیک"),
               ("لمس", "کلیک")],
        "hi": [("टैप करके रखें", "क्लिक करके रखें"),
               ("टैप करें", "क्लिक करें"), ("टैप", "क्लिक")],
        "th": [("แตะแล้วกดไว้", "คลิกแล้วกดไว้"), ("แตะ", "คลิก")],
        "vi": [("Nhấn giữ", "Nhấp giữ"), ("nhấn giữ", "nhấp giữ"),
               ("Nhấn", "Nhấp"), ("nhấn", "nhấp"), ("Chạm", "Nhấp"), ("chạm", "nhấp")],
        "id": [("Ketuk dan tahan", "Klik dan tahan"), ("ketuk dan tahan", "klik dan tahan"),
               ("Ketuk", "Klik"), ("ketuk", "klik")],
        "ms": [("Ketik dan tahan", "Klik dan tahan"), ("ketik dan tahan", "klik dan tahan"),
               ("Ketik", "Klik"), ("ketik", "klik")],
        "ja": [("長押し", "クリックして長押し"), ("タップして", "クリックして"), ("タップ", "クリック")],
        // NO bare ("탭", "클릭"): Korean 탭 is both "tap" and "tab", and these
        // use plain substring replacement. That pair rewrote "코드 탭에서"
        // ("from the My Code TAB", new.paste.step2) into "코드 클릭에서" — the
        // "tape" -> "clicke" failure this file exists to avoid. Only the verb
        // forms are listed.
        "ko": [("길게 누르", "길게 클릭하"), ("탭하", "클릭하"),
               ("탭할", "클릭할"), ("누르면", "클릭하면")],
        "zh-Hans": [("轻点并按住", "点按并按住"), ("长按", "点按并按住"),
                    ("点一下", "点按"), ("轻点", "点按"), ("点击", "点按")],
        "zh-Hant": [("輕點並按住", "點按並按住"), ("長按", "點按並按住"),
                    ("點一下", "點按"), ("輕點", "點按"), ("點擊", "點按")],
    ]

    /// Resolved once. `Bundle.main.preferredLocalizations.first` is the language
    /// the .strings actually came from — NOT `Locale.current.identifier`, which
    /// can name a language the app does not ship and would silently disable the
    /// swap for exactly the users who see a translated string.
    private static let pairs: [(String, String)] = {
        let preferred = Bundle.main.preferredLocalizations.first ?? "en"
        if let exact = table[preferred] { return exact }
        // "pt-BR" style tags fall back to the base language, and vice versa.
        let base = String(preferred.split(separator: "-").first ?? "")
        if let byBase = table[base] { return byBase }
        if let byPrefix = table.first(where: { $0.key.hasPrefix(base + "-") })?.value {
            return byPrefix
        }
        return []
    }()

    /// Scripts with no spaces between words, where `\b` does not mean anything
    /// useful and a plain substring replacement is the correct tool.
    private static let scriptsWithoutWordBoundaries: Set<String> =
        ["ja", "ko", "zh-Hans", "zh-Hant", "th"]

    private static let usePlainReplacement: Bool = {
        let preferred = Bundle.main.preferredLocalizations.first ?? "en"
        return scriptsWithoutWordBoundaries.contains(preferred)
    }()

    static func adapt(_ s: String) -> String {
        guard !pairs.isEmpty, !s.isEmpty else { return s }
        var out = s
        for (from, to) in pairs {
            guard out.contains(from) else { continue }
            if usePlainReplacement {
                out = out.replacingOccurrences(of: from, with: to)
            } else {
                // WORD BOUNDARIES MATTER: a plain replacement of "tap" turns
                // "tape" into "clicke" and "Tap water" into "Click water" —
                // the first is a corrupted string shipped to users, and it is
                // exactly the sort of thing that only shows up in one language.
                out = out.replacingOccurrences(
                    of: "\\b" + NSRegularExpression.escapedPattern(for: from) + "\\b",
                    with: NSRegularExpression.escapedTemplate(for: to),
                    options: [.regularExpression]
                )
            }
        }
        return out
    }

    #else

    /// iOS keeps the touch verbs, which are the correct ones there.
    @inline(__always)
    static func adapt(_ s: String) -> String { s }

    #endif
}
