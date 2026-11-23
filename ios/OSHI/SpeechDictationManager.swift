//
//  SpeechDictationManager.swift
//  OSHI — native, ON-DEVICE voice dictation for the AI chat input.
//
//  Uses Apple's SFSpeechRecognizer with `requiresOnDeviceRecognition = true`, so
//  speech never leaves the phone — consistent with OSHI's offline / privacy
//  promise (nothing is sent to Apple's servers for transcription). Falls back to
//  reporting unavailable if the locale has no on-device model installed, rather
//  than silently sending audio to the cloud.
//

import Foundation
import Speech
import AVFoundation
import Combine

@MainActor
final class SpeechDictationManager: ObservableObject {
    /// Live partial transcript while dictating (the caller mirrors this into the
    /// text field so the user sees words appear as they speak).
    @Published private(set) var transcript: String = ""
    @Published private(set) var isRecording = false
    /// Non-nil when something went wrong (permission denied, no on-device model…).
    @Published private(set) var errorMessage: String?
    /// Whether dictation is even possible on this device/locale (drives whether
    /// the mic button is shown at all).
    @Published private(set) var isAvailable = false

    private let recognizer: SFSpeechRecognizer?
    private let audioEngine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?

    init() {
        // Prefer the user's current locale; SFSpeechRecognizer(locale:) returns
        // nil for unsupported locales, in which case dictation is hidden.
        self.recognizer = SFSpeechRecognizer(locale: Locale.current)
            ?? SFSpeechRecognizer(locale: Locale(identifier: "en-US"))
        // Show the button whenever speech recognition is usable at all. We still
        // REFUSE to run unless on-device recognition is available at start() —
        // so the button is discoverable, but audio is never uploaded to Apple.
        // (Requiring supportsOnDeviceRecognition here would hide the button on
        // devices that simply haven't downloaded the locale model yet.)
        self.isAvailable = recognizer?.isAvailable ?? false
    }

    /// Ask for Speech + Microphone permission. Returns true if both granted.
    func requestAuthorization() async -> Bool {
        let speechOK = await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
            SFSpeechRecognizer.requestAuthorization { status in
                cont.resume(returning: status == .authorized)
            }
        }
        guard speechOK else {
            await MainActor.run { self.errorMessage = "speech.denied".localized }
            return false
        }
        let micOK = await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
            if #available(iOS 17.0, *) {
                AVAudioApplication.requestRecordPermission { cont.resume(returning: $0) }
            } else {
                AVAudioSession.sharedInstance().requestRecordPermission { cont.resume(returning: $0) }
            }
        }
        if !micOK { await MainActor.run { self.errorMessage = "speech.mic_denied".localized } }
        return micOK
    }

    /// Toggle dictation. `onFinal` is called with the completed transcript when
    /// the user stops (so the caller can keep it even after `transcript` clears).
    func toggle(onFinal: @escaping (String) -> Void) {
        if isRecording { stop(onFinal: onFinal) } else { Task { await start() } }
    }

    private func start() async {
        guard !isRecording else { return }
        guard let recognizer, recognizer.isAvailable else {
            errorMessage = "speech.unavailable".localized; return
        }
        // Hard privacy gate: only run when the transcription can happen fully
        // on-device. If the locale's model isn't present we refuse and tell the
        // user rather than letting the request stream audio to Apple's servers.
        guard recognizer.supportsOnDeviceRecognition else {
            errorMessage = "speech.unavailable".localized; return
        }
        guard await requestAuthorization() else { return }

        errorMessage = nil
        transcript = ""

        let req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        // Keep transcription fully on-device — never uploads audio to Apple.
        req.requiresOnDeviceRecognition = true
        request = req

        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .measurement, options: .duckOthers)
            try session.setActive(true, options: .notifyOthersOnDeactivation)

            let input = audioEngine.inputNode
            let format = input.outputFormat(forBus: 0)
            input.removeTap(onBus: 0)
            input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak req] buffer, _ in
                req?.append(buffer)
            }
            audioEngine.prepare()
            try audioEngine.start()
            isRecording = true

            task = recognizer.recognitionTask(with: req) { [weak self] result, error in
                guard let self else { return }
                Task { @MainActor in
                    if let result {
                        self.transcript = result.bestTranscription.formattedString
                    }
                    if error != nil || (result?.isFinal ?? false) {
                        self.teardownEngine()
                    }
                }
            }
        } catch {
            errorMessage = error.localizedDescription
            teardownEngine()
        }
    }

    private func stop(onFinal: @escaping (String) -> Void) {
        let final = transcript
        request?.endAudio()
        task?.finish()
        teardownEngine()
        if !final.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            onFinal(final)
        }
    }

    /// Cancel without emitting the transcript (e.g. the view disappeared).
    func cancel() {
        request?.endAudio()
        task?.cancel()
        teardownEngine()
    }

    private func teardownEngine() {
        if audioEngine.isRunning {
            audioEngine.stop()
            audioEngine.inputNode.removeTap(onBus: 0)
        }
        request = nil
        task = nil
        isRecording = false
        transcript = ""
        // Release the audio session so calls / audio-message playback can reclaim it.
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
