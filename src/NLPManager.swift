//
//  NLPManager.swift
//  OSHI - Offline AI Language Processing
//
//  Spell check, language detection, translation, and message improvement.
//  iOS 26+: Apple Foundation Models (on-device 3B LLM, 100% offline).
//  iOS < 26: Rule-based fallback using NaturalLanguage + UITextChecker.
//  Size impact: 0 MB (system frameworks, models managed by iOS).
//

import Foundation
import NaturalLanguage
import UIKit
import SwiftUI
@preconcurrency import Translation

#if canImport(FoundationModels)
import FoundationModels
#endif

// MARK: - NLP Manager

final class NLPManager: ObservableObject {
    static let shared = NLPManager()

    @Published var detectedLanguage: String = "en"
    @Published var spellSuggestions: [SpellSuggestion] = []
    @Published var isProcessing: Bool = false

    private let textChecker = UITextChecker()

    private init() {}

    // MARK: - Apple Foundation Models Availability (iOS 26+)

    /// Whether the on-device LLM is available (iOS 26+ with Apple Intelligence enabled)
    var isFoundationModelAvailable: Bool {
        #if canImport(FoundationModels)
        if #available(iOS 26, *) {
            return SystemLanguageModel.default.isAvailable
        }
        #endif
        return false
    }

    // MARK: - Foundation Models: AI-Powered Processing (iOS 26+)

    #if canImport(FoundationModels)

    /// Correct spelling and grammar using on-device LLM
    @available(iOS 26, *)
    func aiCorrect(_ text: String) async -> String {
        let lang = detectLanguage(text)
        let langName = lang.map { languageName(for: $0.rawValue) } ?? "the detected language"

        let session = LanguageModelSession(instructions: """
            You are a spelling and grammar correction assistant. \
            Fix all spelling mistakes, grammar errors, punctuation issues, and typos in the user's text. \
            Preserve the original meaning, tone, and language. \
            The text is in \(langName). \
            Return ONLY the corrected text with no explanation, no quotes, no prefix.
            """)

        do {
            let response = try await session.respond(to: text)
            return response.content.trimmingCharacters(in: .whitespacesAndNewlines)
        } catch {
            // Fallback to rule-based correction
            return autoCorrect(text)
        }
    }

    /// Improve message with a specific tone using on-device LLM
    @available(iOS 26, *)
    func aiImprove(_ text: String, style: ImprovementStyle) async -> String {
        let lang = detectLanguage(text)
        let langName = lang.map { languageName(for: $0.rawValue) } ?? "the detected language"

        let styleInstruction: String
        switch style {
        case .formal:
            styleInstruction = "Rewrite in a formal, respectful tone. Use complete sentences, avoid contractions, and maintain a dignified register."
        case .casual:
            styleInstruction = "Rewrite in a casual, relaxed tone. Use contractions, simple words, and a conversational style."
        case .friendly:
            styleInstruction = "Rewrite in a warm, friendly, and kind tone. Soften any demanding language. Be polite and approachable."
        case .professional:
            styleInstruction = "Rewrite in a professional business tone. Be clear, concise, and authoritative while remaining courteous."
        case .playful:
            styleInstruction = "Rewrite in a playful, fun, and energetic tone. Add enthusiasm and lightheartedness while keeping the meaning."
        case .persuasive:
            styleInstruction = "Rewrite in a persuasive, convincing tone. Use strong, confident language. Replace hedging with assertive statements."
        }

        let session = LanguageModelSession(instructions: """
            You are a writing assistant that rewrites messages. \
            \(styleInstruction) \
            The text is in \(langName). Keep the SAME language as the original — do NOT translate. \
            Fix any spelling or grammar errors while rewriting. \
            Return ONLY the rewritten text with no explanation, no quotes, no prefix.
            """)

        do {
            let response = try await session.respond(to: text)
            return response.content.trimmingCharacters(in: .whitespacesAndNewlines)
        } catch {
            return improveMessageRuleBased(text, style: style)
        }
    }

    /// Translate text to a target language using on-device LLM
    @available(iOS 26, *)
    func aiTranslate(_ text: String, to targetLanguage: String) async -> String {
        let targetName = languageName(for: targetLanguage)

        let session = LanguageModelSession(instructions: """
            You are a translator. Translate the user's text into \(targetName). \
            Preserve the original tone and meaning. \
            Return ONLY the translated text with no explanation, no quotes, no prefix.
            """)

        do {
            let response = try await session.respond(to: text)
            return response.content.trimmingCharacters(in: .whitespacesAndNewlines)
        } catch {
            return text
        }
    }

    /// Ask AI a simple question and get an answer using on-device LLM
    @available(iOS 26, *)
    func aiAsk(_ question: String) async -> String {
        let session = LanguageModelSession(instructions: """
            You are a helpful assistant integrated in a messaging app. \
            Answer the user's question concisely and clearly. \
            Reply in the same language as the question. \
            Keep answers short — ideally 1-3 sentences.
            """)

        do {
            let response = try await session.respond(to: question)
            return response.content.trimmingCharacters(in: .whitespacesAndNewlines)
        } catch {
            return "ai.ask_unavailable".localized
        }
    }

    /// Translate a received message to user's device language using on-device LLM
    @available(iOS 26, *)
    func aiTranslateToDeviceLanguage(_ text: String) async -> String {
        let deviceLang = Locale.current.language.languageCode?.identifier ?? "en"
        let deviceLangName = languageName(for: deviceLang)

        let session = LanguageModelSession(instructions: """
            You are a translator in a messaging app. \
            Translate the following message into \(deviceLangName). \
            Preserve the original tone and meaning. \
            Return ONLY the translated text with no explanation, no quotes, no prefix.
            """)

        do {
            let response = try await session.respond(to: text)
            return response.content.trimmingCharacters(in: .whitespacesAndNewlines)
        } catch {
            return text
        }
    }

    #endif

    // MARK: - Language Detection

    /// Detect the dominant language of a text
    @discardableResult
    func detectLanguage(_ text: String) -> NLLanguage? {
        let recognizer = NLLanguageRecognizer()
        recognizer.processString(text)
        if let lang = recognizer.dominantLanguage {
            let code = lang.rawValue
            if Thread.isMainThread {
                self.detectedLanguage = code
            } else {
                DispatchQueue.main.async { self.detectedLanguage = code }
            }
            return lang
        }
        return nil
    }

    /// Get language name from code
    func languageName(for code: String) -> String {
        Locale.current.localizedString(forLanguageCode: code)
            ?? Locale(identifier: code).localizedString(forLanguageCode: code)
            ?? code
    }

    // MARK: - Spell Check (Rule-Based Fallback)

    struct SpellSuggestion: Identifiable {
        let id = UUID()
        let range: NSRange
        let word: String
        let suggestions: [String]
    }

    /// Check spelling of text and return misspelled words with suggestions
    func checkSpelling(_ text: String, language: String? = nil) -> [SpellSuggestion] {
        let lang = language ?? detectedLanguage
        let nsText = text as NSString
        var results: [SpellSuggestion] = []
        var searchRange = NSRange(location: 0, length: nsText.length)

        while searchRange.location < nsText.length {
            let misspelledRange = textChecker.rangeOfMisspelledWord(
                in: text,
                range: NSRange(location: 0, length: nsText.length),
                startingAt: searchRange.location,
                wrap: false,
                language: lang
            )

            if misspelledRange.location == NSNotFound { break }

            let word = nsText.substring(with: misspelledRange)
            let guesses = textChecker.guesses(
                forWordRange: misspelledRange,
                in: text,
                language: lang
            ) ?? []

            results.append(SpellSuggestion(
                range: misspelledRange,
                word: word,
                suggestions: Array(guesses.prefix(5))
            ))

            searchRange.location = misspelledRange.location + misspelledRange.length
        }

        DispatchQueue.main.async { self.spellSuggestions = results }
        return results
    }

    /// Auto-correct text by applying the first suggestion for each misspelled word
    func autoCorrect(_ text: String, language: String? = nil) -> String {
        let errors = checkSpelling(text, language: language)
        guard !errors.isEmpty else { return text }

        var corrected = text
        // Apply corrections in reverse order to preserve indices
        for error in errors.reversed() {
            if let firstSuggestion = error.suggestions.first,
               let range = Range(error.range, in: corrected) {
                corrected.replaceSubrange(range, with: firstSuggestion)
            }
        }
        return corrected
    }

    // MARK: - Sentiment Analysis

    enum Sentiment: String {
        case positive, neutral, negative
    }

    func analyzeSentiment(_ text: String) -> (sentiment: Sentiment, score: Double) {
        let tagger = NLTagger(tagSchemes: [.sentimentScore])
        tagger.string = text
        let (tag, _) = tagger.tag(at: text.startIndex, unit: .paragraph, scheme: .sentimentScore)
        let score = Double(tag?.rawValue ?? "0") ?? 0.0

        let sentiment: Sentiment
        if score > 0.1 {
            sentiment = .positive
        } else if score < -0.1 {
            sentiment = .negative
        } else {
            sentiment = .neutral
        }
        return (sentiment, score)
    }

    // MARK: - Message Improvement Styles

    enum ImprovementStyle: String, CaseIterable {
        case formal = "formal"
        case casual = "casual"
        case friendly = "friendly"
        case professional = "professional"
        case playful = "playful"
        case persuasive = "persuasive"

        var localizedKey: String {
            "ai.style.\(rawValue)"
        }

        var icon: String {
            switch self {
            case .formal: return "briefcase.fill"
            case .casual: return "hand.wave.fill"
            case .friendly: return "face.smiling.fill"
            case .professional: return "building.2.fill"
            case .playful: return "party.popper.fill"
            case .persuasive: return "megaphone.fill"
            }
        }
    }

    // MARK: - Unified Processing Entry Points

    /// Process correction: uses Foundation Models on iOS 26+, rule-based fallback otherwise
    func processCorrection(_ text: String) async -> String {
        #if canImport(FoundationModels)
        if #available(iOS 26, *), SystemLanguageModel.default.isAvailable {
            return await aiCorrect(text)
        }
        #endif
        return autoCorrect(text)
    }

    /// Process improvement: uses Foundation Models on iOS 26+, rule-based fallback otherwise
    func processImprovement(_ text: String, style: ImprovementStyle) async -> String {
        #if canImport(FoundationModels)
        if #available(iOS 26, *), SystemLanguageModel.default.isAvailable {
            return await aiImprove(text, style: style)
        }
        #endif
        return improveMessageRuleBased(text, style: style)
    }

    /// Process translation: uses Foundation Models on iOS 26+
    func processTranslation(_ text: String, to targetLanguage: String) async -> String? {
        #if canImport(FoundationModels)
        if #available(iOS 26, *), SystemLanguageModel.default.isAvailable {
            return await aiTranslate(text, to: targetLanguage)
        }
        #endif
        return nil // Caller should show iOS Translation sheet or unavailable message
    }

    /// Ask AI a question: uses Foundation Models on iOS 26+
    func processAskAI(_ question: String) async -> String? {
        #if canImport(FoundationModels)
        if #available(iOS 26, *), SystemLanguageModel.default.isAvailable {
            return await aiAsk(question)
        }
        #endif
        return nil
    }

    /// Translate received message to device language
    func processTranslateReceived(_ text: String) async -> String? {
        #if canImport(FoundationModels)
        if #available(iOS 26, *), SystemLanguageModel.default.isAvailable {
            return await aiTranslateToDeviceLanguage(text)
        }
        #endif
        return nil // Caller should use iOS Translation sheet fallback
    }

    // MARK: - Rule-Based Improvement (Fallback)

    /// Improve message: fix spelling + basic grammar + punctuation (rule-based)
    func improveMessageRuleBased(_ text: String, style: ImprovementStyle = .casual, language: String? = nil) -> String {
        var improved = autoCorrect(text, language: language)

        // Basic improvements
        improved = capitalizeFirstLetter(improved)
        improved = fixDoubleSpaces(improved)
        improved = ensurePunctuation(improved)

        // Style adjustments
        switch style {
        case .formal:
            improved = formalizeContractions(improved)
        case .casual:
            improved = makeCasual(improved)
        case .friendly:
            improved = makeFriendly(improved)
        case .professional:
            improved = formalizeContractions(improved)
            improved = capitalizeFirstLetter(improved)
        case .playful:
            improved = makePlayful(improved)
        case .persuasive:
            improved = makePersuasive(improved)
        }

        return improved
    }

    // MARK: - Text Utilities (Rule-Based)

    private func capitalizeFirstLetter(_ text: String) -> String {
        guard !text.isEmpty else { return text }
        var result = ""
        var capitalizeNext = true
        for char in text {
            if capitalizeNext && char.isLetter {
                result.append(char.uppercased())
                capitalizeNext = false
            } else {
                result.append(char)
            }
            if char == "." || char == "!" || char == "?" {
                capitalizeNext = true
            }
        }
        return result
    }

    private func fixDoubleSpaces(_ text: String) -> String {
        var result = text
        while result.contains("  ") {
            result = result.replacingOccurrences(of: "  ", with: " ")
        }
        return result
    }

    private func ensurePunctuation(_ text: String) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return text }
        let lastChar = trimmed.last!
        if !lastChar.isPunctuation && trimmed.count > 3 {
            return trimmed + "."
        }
        return trimmed
    }

    private func formalizeContractions(_ text: String) -> String {
        var result = text
        let contractions: [(String, String)] = [
            ("don't", "do not"), ("doesn't", "does not"),
            ("won't", "will not"), ("can't", "cannot"),
            ("shouldn't", "should not"), ("wouldn't", "would not"),
            ("couldn't", "could not"), ("isn't", "is not"),
            ("aren't", "are not"), ("wasn't", "was not"),
            ("weren't", "were not"), ("haven't", "have not"),
            ("hasn't", "has not"), ("hadn't", "had not"),
            ("I'm", "I am"), ("you're", "you are"),
            ("we're", "we are"), ("they're", "they are"),
            ("it's", "it is"), ("that's", "that is"),
            ("there's", "there is"), ("I've", "I have"),
            ("you've", "you have"), ("we've", "we have"),
            ("they've", "they have"), ("I'll", "I will"),
            ("you'll", "you will"), ("we'll", "we will"),
            ("they'll", "they will"), ("I'd", "I would"),
            ("you'd", "you would"), ("we'd", "we would"),
            ("they'd", "they would"), ("let's", "let us"),
        ]
        for (contraction, expanded) in contractions {
            result = result.replacingOccurrences(of: contraction, with: expanded, options: .caseInsensitive)
        }
        return result
    }

    private func makeCasual(_ text: String) -> String {
        var result = text
        let expansions: [(String, String)] = [
            ("do not", "don't"), ("does not", "doesn't"),
            ("will not", "won't"), ("cannot", "can't"),
            ("should not", "shouldn't"), ("would not", "wouldn't"),
            ("could not", "couldn't"), ("is not", "isn't"),
            ("are not", "aren't"), ("I am", "I'm"),
            ("you are", "you're"), ("we are", "we're"),
            ("they are", "they're"), ("it is", "it's"),
            ("I have", "I've"), ("you have", "you've"),
            ("let us", "let's"), ("I will", "I'll"),
            ("you will", "you'll"), ("we will", "we'll"),
        ]
        for (expanded, contraction) in expansions {
            result = result.replacingOccurrences(of: expanded, with: contraction, options: .caseInsensitive)
        }
        let softeners: [(String, String)] = [
            ("therefore", "so"), ("however", "but"),
            ("furthermore", "also"), ("regarding", "about"),
            ("approximately", "about"), ("require", "need"),
            ("utilize", "use"), ("commence", "start"),
            ("terminate", "end"), ("sufficient", "enough"),
            ("inquire", "ask"), ("assist", "help"),
            ("purchase", "buy"), ("additional", "more"),
        ]
        for (formal, casual) in softeners {
            result = result.replacingOccurrences(of: formal, with: casual, options: .caseInsensitive)
        }
        return result
    }

    private func makeFriendly(_ text: String) -> String {
        var result = text
        let softeners: [(String, String)] = [
            ("you must", "it would be great if you could"),
            ("You must", "It would be great if you could"),
            ("you need to", "it would be nice if you could"),
            ("You need to", "It would be nice if you could"),
            ("you should", "you might want to"),
            ("You should", "You might want to"),
            ("do this", "try this"),
            ("Do this", "Try this"),
            ("I need", "I would appreciate"),
            ("send me", "could you send me"),
            ("Send me", "Could you send me"),
            ("tell me", "could you tell me"),
            ("Tell me", "Could you tell me"),
            ("don't forget", "just a reminder"),
            ("Don't forget", "Just a reminder"),
        ]
        for (harsh, friendly) in softeners {
            result = result.replacingOccurrences(of: harsh, with: friendly)
        }
        let trimmed = result.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.count < 80 && !trimmed.hasSuffix("!") && !trimmed.hasSuffix("?") && !trimmed.hasSuffix(")") {
            if trimmed.hasSuffix(".") {
                result = String(trimmed.dropLast()) + "!"
            }
        }
        return result
    }

    private func makePlayful(_ text: String) -> String {
        var result = text
        let expansions: [(String, String)] = [
            ("do not", "don't"), ("does not", "doesn't"),
            ("will not", "won't"), ("cannot", "can't"),
            ("should not", "shouldn't"), ("would not", "wouldn't"),
            ("could not", "couldn't"), ("is not", "isn't"),
            ("are not", "aren't"), ("I am", "I'm"),
            ("you are", "you're"), ("we are", "we're"),
            ("they are", "they're"), ("it is", "it's"),
            ("I have", "I've"), ("you have", "you've"),
            ("let us", "let's"),
        ]
        for (expanded, contraction) in expansions {
            result = result.replacingOccurrences(of: expanded, with: contraction, options: .caseInsensitive)
        }
        let sentences = result.components(separatedBy: ". ")
        if sentences.count > 1 {
            var playfulSentences: [String] = []
            for (i, sentence) in sentences.enumerated() {
                if i < sentences.count - 1 {
                    playfulSentences.append(sentence + "!")
                } else {
                    playfulSentences.append(sentence)
                }
            }
            result = playfulSentences.joined(separator: " ")
        }
        let trimmed = result.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.count < 60 && !trimmed.hasSuffix("!") && !trimmed.hasSuffix("?") {
            result = trimmed + "!"
        }
        return result
    }

    private func makePersuasive(_ text: String) -> String {
        var result = text
        let strengtheners: [(String, String)] = [
            ("I think", "I'm convinced"),
            ("i think", "I'm convinced"),
            ("maybe we should", "we should definitely"),
            ("we could", "we should"),
            ("it might be good", "it would be great"),
            ("it would be nice", "it's essential"),
            ("try to", "make sure to"),
            ("I guess", "I believe"),
            ("sort of", "clearly"),
            ("kind of", "truly"),
            ("pretty good", "excellent"),
            ("not bad", "impressive"),
            ("I feel like", "I'm certain"),
        ]
        for (weak, strong) in strengtheners {
            result = result.replacingOccurrences(of: weak, with: strong, options: .caseInsensitive)
        }
        result = formalizeContractions(result)
        let trimmed = result.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.hasSuffix(".") && !trimmed.hasSuffix("!") && !trimmed.hasSuffix("?") && trimmed.count > 3 {
            result = trimmed + "."
        }
        return result
    }
}

// MARK: - Translation Target Languages

struct TranslationTarget: Identifiable, Hashable {
    let id: String // language code
    let name: String

    static let common: [TranslationTarget] = {
        let codes = ["en", "fr", "de", "es", "it", "pt", "ru", "zh-Hans", "ja", "ar", "ko", "hi", "pl", "uk", "he", "fa"]
        return codes.compactMap { code in
            let name = Locale.current.localizedString(forLanguageCode: code) ?? code
            return TranslationTarget(id: code, name: name)
        }
    }()
}

// MARK: - AI Shortcuts View

struct AIShortcutsView: View {
    @Binding var messageText: String
    @Binding var isPresented: Bool
    @StateObject private var nlpManager = NLPManager.shared
    @State private var correctedText: String = ""
    @State private var selectedAction: AIAction = .correct
    @State private var selectedStyle: NLPManager.ImprovementStyle = .formal
    @State private var showTranslation = false
    @State private var translationSourceText: String = ""
    @State private var detectedLangDisplay: String = ""
    @State private var isLoading = false
    @State private var selectedTranslationTarget: TranslationTarget? = nil
    @State private var showLanguagePicker = false
    @State private var askQuestion: String = ""
    @State private var askAnswer: String = ""

    private let brandColor = Color(red: 0.337, green: 0.086, blue: 0.925)

    private var usesFoundationModel: Bool {
        NLPManager.shared.isFoundationModelAvailable
    }

    enum AIAction: String, CaseIterable {
        case correct = "ai.action.correct"
        case improve = "ai.action.improve"
        case translate = "ai.action.translate"
        case tone = "ai.action.tone"
        case ask = "ai.action.ask"
    }

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                // Original text
                VStack(alignment: .leading, spacing: 6) {
                    Text("ai.original".localized)
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Text(messageText)
                        .font(.body)
                        .padding(12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color(.secondarySystemGroupedBackground))
                        .cornerRadius(12)
                }
                .padding(.horizontal)
                .padding(.top, 12)

                // Action picker
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(AIAction.allCases, id: \.rawValue) { action in
                            Button {
                                selectedAction = action
                                if action == .tone {
                                    processStyle(selectedStyle)
                                } else {
                                    processAction(action)
                                }
                            } label: {
                                HStack(spacing: 4) {
                                    Image(systemName: iconForAction(action))
                                    Text(action.rawValue.localized)
                                }
                                .font(.subheadline.weight(.medium))
                                .padding(.horizontal, 14)
                                .padding(.vertical, 8)
                                .background(selectedAction == action ? brandColor : Color(.tertiarySystemGroupedBackground))
                                .foregroundColor(selectedAction == action ? .white : .primary)
                                .cornerRadius(20)
                            }
                        }
                    }
                    .padding(.horizontal)
                    .padding(.vertical, 12)
                }

                // Tone selector
                if selectedAction == .tone {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(NLPManager.ImprovementStyle.allCases, id: \.rawValue) { style in
                                Button {
                                    selectedStyle = style
                                    processStyle(style)
                                } label: {
                                    HStack(spacing: 4) {
                                        Image(systemName: style.icon)
                                            .font(.caption)
                                        Text(style.localizedKey.localized)
                                    }
                                    .font(.caption.weight(.medium))
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 6)
                                    .background(selectedStyle == style ? brandColor.opacity(0.2) : Color(.quaternarySystemFill))
                                    .foregroundColor(selectedStyle == style ? brandColor : .secondary)
                                    .cornerRadius(16)
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 16)
                                            .stroke(selectedStyle == style ? brandColor : Color.clear, lineWidth: 1)
                                    )
                                }
                            }
                        }
                        .padding(.horizontal)
                        .padding(.bottom, 8)
                    }
                }

                // Translation language picker (Foundation Models)
                if selectedAction == .translate && usesFoundationModel {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(TranslationTarget.common) { target in
                                // Don't show current detected language
                                if target.id != nlpManager.detectedLanguage {
                                    Button {
                                        selectedTranslationTarget = target
                                        processTranslation(to: target.id)
                                    } label: {
                                        Text(target.name)
                                            .font(.caption.weight(.medium))
                                            .padding(.horizontal, 12)
                                            .padding(.vertical, 6)
                                            .background(selectedTranslationTarget?.id == target.id ? brandColor.opacity(0.2) : Color(.quaternarySystemFill))
                                            .foregroundColor(selectedTranslationTarget?.id == target.id ? brandColor : .secondary)
                                            .cornerRadius(16)
                                            .overlay(
                                                RoundedRectangle(cornerRadius: 16)
                                                    .stroke(selectedTranslationTarget?.id == target.id ? brandColor : Color.clear, lineWidth: 1)
                                            )
                                    }
                                }
                            }
                        }
                        .padding(.horizontal)
                        .padding(.bottom, 8)
                    }
                }

                // Ask AI input
                if selectedAction == .ask && usesFoundationModel {
                    VStack(spacing: 8) {
                        TextField("ai.ask_placeholder".localized, text: $askQuestion, axis: .vertical)
                            .textFieldStyle(.plain)
                            .padding(12)
                            .background(Color(.secondarySystemGroupedBackground))
                            .cornerRadius(12)
                            .lineLimit(1...4)

                        Button {
                            guard !askQuestion.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
                            isLoading = true
                            correctedText = ""
                            Task {
                                let answer = await nlpManager.processAskAI(askQuestion) ?? "ai.ask_unavailable".localized
                                await MainActor.run {
                                    correctedText = answer
                                    isLoading = false
                                }
                            }
                        } label: {
                            HStack(spacing: 6) {
                                Image(systemName: "paperplane.fill")
                                Text("ai.ask_send".localized)
                            }
                            .font(.subheadline.weight(.medium))
                            .padding(.horizontal, 20)
                            .padding(.vertical, 10)
                            .background(brandColor)
                            .foregroundColor(.white)
                            .cornerRadius(20)
                        }
                    }
                    .padding(.horizontal)
                    .padding(.bottom, 8)
                } else if selectedAction == .ask && !usesFoundationModel {
                    Text("ai.ask_requires_ios26".localized)
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .padding()
                }

                // Loading indicator
                if isLoading {
                    HStack(spacing: 8) {
                        ProgressView()
                            .tint(brandColor)
                        Text("ai.processing".localized)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    .padding(.vertical, 12)
                }

                // Result
                if !correctedText.isEmpty && !isLoading {
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text("ai.result".localized)
                                .font(.caption)
                                .foregroundColor(.secondary)

                            Spacer()

                            if !detectedLangDisplay.isEmpty {
                                Text(detectedLangDisplay)
                                    .font(.caption2)
                                    .foregroundColor(brandColor)
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 2)
                                    .background(brandColor.opacity(0.1))
                                    .cornerRadius(8)
                            }

                            // Badge: AI or Rule-based
                            if usesFoundationModel {
                                Text("AI")
                                    .font(.caption2.weight(.bold))
                                    .foregroundColor(.white)
                                    .padding(.horizontal, 6)
                                    .padding(.vertical, 2)
                                    .background(brandColor)
                                    .cornerRadius(6)
                            }
                        }

                        Text(correctedText)
                            .font(.body)
                            .padding(12)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(brandColor.opacity(0.08))
                            .cornerRadius(12)

                        // Spell errors count (rule-based mode only)
                        if !usesFoundationModel && !nlpManager.spellSuggestions.isEmpty {
                            Text(String(format: "ai.errors_found".localized, nlpManager.spellSuggestions.count))
                                .font(.caption2)
                                .foregroundColor(.orange)
                        }
                    }
                    .padding(.horizontal)
                    .transition(.opacity)
                }

                Spacer()

                // Apply / Cancel buttons
                if !correctedText.isEmpty && !isLoading {
                    HStack(spacing: 12) {
                        Button {
                            isPresented = false
                        } label: {
                            Text("common.cancel".localized)
                                .font(.headline)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 14)
                                .background(Color(.tertiarySystemGroupedBackground))
                                .foregroundColor(.primary)
                                .cornerRadius(14)
                        }

                        Button {
                            messageText = correctedText
                            isPresented = false
                        } label: {
                            Text("ai.apply".localized)
                                .font(.headline)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 14)
                                .background(brandColor)
                                .foregroundColor(.white)
                                .cornerRadius(14)
                        }
                    }
                    .padding(.horizontal)
                    .padding(.bottom, 16)
                }
            }
            .navigationTitle("ai.shortcuts.title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel".localized) { isPresented = false }
                }
            }
            .if_available_translation(sourceText: messageText, isPresented: $showTranslation)
        }
        .onAppear {
            // Detect language once on appear (not in body)
            if let lang = nlpManager.detectLanguage(messageText) {
                detectedLangDisplay = nlpManager.languageName(for: lang.rawValue)
            }
            processAction(.correct)
        }
    }

    private func processAction(_ action: AIAction) {
        let text = messageText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }

        switch action {
        case .correct:
            runAsync { await nlpManager.processCorrection(text) }
        case .improve:
            runAsync { await nlpManager.processImprovement(text, style: .casual) }
        case .translate:
            if usesFoundationModel {
                // Show language picker, user picks target
                correctedText = ""
            } else {
                // iOS < 26: use system Translation sheet (iOS 18+) or show unavailable
                if #available(iOS 18.0, *) {
                    showTranslation = true
                    correctedText = text
                } else {
                    correctedText = "ai.translate_unavailable".localized
                }
            }
        case .tone:
            processStyle(selectedStyle)
        case .ask:
            correctedText = ""
        }
    }

    private func processStyle(_ style: NLPManager.ImprovementStyle) {
        let text = messageText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        runAsync { await nlpManager.processImprovement(text, style: style) }
    }

    private func processTranslation(to targetLanguage: String) {
        let text = messageText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        runAsync {
            if let translated = await nlpManager.processTranslation(text, to: targetLanguage) {
                return translated
            }
            return text
        }
    }

    /// Run an async AI operation with loading state
    private func runAsync(_ operation: @escaping () async -> String) {
        isLoading = true
        correctedText = ""
        Task {
            let result = await operation()
            await MainActor.run {
                correctedText = result
                isLoading = false
            }
        }
    }

    private func iconForAction(_ action: AIAction) -> String {
        switch action {
        case .correct: return "textformat.abc"
        case .improve: return "sparkles"
        case .translate: return "globe"
        case .tone: return "theatermasks.fill"
        case .ask: return "bubble.left.and.text.bubble.right.fill"
        }
    }
}

// MARK: - Translation Modifier (iOS 18+)

extension View {
    @ViewBuilder
    func if_available_translation(sourceText: String, isPresented: Binding<Bool>) -> some View {
        if #available(iOS 18.0, *) {
            self.modifier(TranslationModifier(sourceText: sourceText, isPresented: isPresented))
        } else {
            self
        }
    }
}

@available(iOS 18.0, *)
struct TranslationModifier: ViewModifier {
    let sourceText: String
    @Binding var isPresented: Bool

    func body(content: Content) -> some View {
        content
            .translationPresentation(isPresented: $isPresented, text: sourceText)
    }
}

// MARK: - Compact AI Toolbar (inline above keyboard)

struct AIQuickBar: View {
    @Binding var messageText: String
    @State private var showAISheet = false

    private let brandColor = Color(red: 0.337, green: 0.086, blue: 0.925)

    var body: some View {
        HStack(spacing: 8) {
            // Quick spell check
            Button {
                let text = messageText
                Task {
                    let result = await NLPManager.shared.processCorrection(text)
                    await MainActor.run { messageText = result }
                }
            } label: {
                Label("ai.quick.correct".localized, systemImage: "textformat.abc")
                    .font(.caption2.weight(.medium))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Color(.tertiarySystemGroupedBackground))
                    .cornerRadius(14)
            }

            // Quick improve
            Button {
                let text = messageText
                Task {
                    let result = await NLPManager.shared.processImprovement(text, style: .casual)
                    await MainActor.run { messageText = result }
                }
            } label: {
                Label("ai.quick.improve".localized, systemImage: "sparkles")
                    .font(.caption2.weight(.medium))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Color(.tertiarySystemGroupedBackground))
                    .cornerRadius(14)
            }

            // Full AI sheet
            Button {
                showAISheet = true
            } label: {
                Image(systemName: "ellipsis.circle")
                    .font(.caption)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 6)
                    .background(brandColor.opacity(0.1))
                    .foregroundColor(brandColor)
                    .cornerRadius(14)
            }
        }
        .sheet(isPresented: $showAISheet) {
            AIShortcutsView(messageText: $messageText, isPresented: $showAISheet)
                .presentationDetents([.medium, .large])
        }
    }
}
