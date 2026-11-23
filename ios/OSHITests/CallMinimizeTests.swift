//
//  CallMinimizeTests.swift
//  OSHITests
//
//  __CALL_MINIMIZE_2026_08_16__
//
//  The call UI is a full-screen overlay at `zIndex(1000)`, so while a call was up
//  the rest of the app was unreachable: you could not read the address someone
//  had just sent you without hanging up first. Minimizing steps that overlay
//  aside and leaves a compact bar.
//
//  The one thing that must be true, and the reason the flag lives on the MANAGER
//  rather than in a view: minimizing must never touch the call, and a call must
//  never inherit the previous call's minimized state — which would open the next
//  call invisible, with only a small bar to explain why the phone is talking.
//

import XCTest
@testable import OSHI

@MainActor
final class CallMinimizeTests: XCTestCase {

    private var manager: VoiceCallManager { VoiceCallManager.shared }

    override func tearDown() async throws {
        manager.isCallMinimized = false
        manager.callState = .idle
        try await super.tearDown()
    }

    /// Minimiser n'est possible que pendant l'appel, et n'y touche pas.
    func testMinimizingDoesNotChangeTheCallState() {
        manager.callState = .inCall
        manager.isCallMinimized = true

        XCTAssertEqual(manager.callState, .inCall, "réduire l'affichage ne doit pas toucher l'appel")
        XCTAssertTrue(manager.isCallMinimized)
    }

    /// Le piège de l'état collant: sans remise à zéro, l'appel SUIVANT s'ouvre
    /// réduit. `callState.didSet` est le seul point de passage de toutes les
    /// transitions, donc c'est là que la remise à zéro vit.
    func testEndingACallClearsTheMinimizedState() {
        manager.callState = .inCall
        manager.isCallMinimized = true

        manager.callState = .idle
        XCTAssertFalse(manager.isCallMinimized, "l'appel suivant s'ouvrirait invisible")
    }

    func testNextCallStartsExpanded() {
        manager.callState = .inCall
        manager.isCallMinimized = true
        manager.callState = .idle

        // Un nouvel appel
        manager.callState = .connecting
        XCTAssertFalse(manager.isCallMinimized)
        manager.callState = .inCall
        XCTAssertFalse(manager.isCallMinimized, "un appel qui commence doit s'afficher")
    }

    /// Toute sortie de `.inCall` remet à zéro — pas seulement `.idle`.
    func testAnyTransitionOutOfInCallClearsIt() {
        for state in [CallState.idle, .connecting, .ringing] {
            manager.callState = .inCall
            manager.isCallMinimized = true
            manager.callState = state
            XCTAssertFalse(manager.isCallMinimized, "transition .inCall → \(state) doit déplier")
        }
    }

    /// Rester dans `.inCall` ne déplie pas: c'est le seul état où le drapeau a un
    /// sens, et un `didSet` trop large annulerait le geste de l'utilisateur au
    /// premier événement d'appel venu.
    func testStayingInCallKeepsItMinimized() {
        manager.callState = .inCall
        manager.isCallMinimized = true
        manager.callState = .inCall   // ré-affectation du même état
        XCTAssertTrue(manager.isCallMinimized)
    }
}
