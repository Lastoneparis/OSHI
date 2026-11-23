//
//  SceneDelegate.swift
//  OSHI - App Switcher Privacy Protection
//
//  Shows LaunchScreenView when app goes to background
//

import UIKit
import SwiftUI

class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    
    var window: UIWindow?
    private var privacyView: UIView?
    
    // ✅ Called when scene will resign active (app switcher / screenshot)
    func sceneWillResignActive(_ scene: UIScene) {
        // Notify VoiceCallManager regardless of call state — it needs the
        // timestamp to detect "did we background long enough to need a
        // resync flush?" when the scene returns active.
        NotificationCenter.default.post(name: NSNotification.Name("OshiSceneWillResignActive"), object: nil)

        guard let windowScene = scene as? UIWindowScene else { return }
        guard let window = windowScene.windows.first else { return }

        // Skip privacy overlay during active calls — it disrupts audio session and camera
        let callState = VoiceCallManager.shared.callState
        if callState == .inCall || callState == .ringing || callState == .connecting {
            OshiLog.app.info("📞 Skipping privacy overlay — call is active (\(callState))")
            return
        }

        // __PRIVACY_COVER_IS_NOT_A_RELAUNCH_2026_10_23__ The cover is the
        // MINIMAL screen, not the full branded one.
        //
        // Both hide the thumbnail equally well — that is all this has to do. But
        // the full `LaunchScreenView` carries the cold-launch animation, so
        // flicking to another app and straight back looked exactly like the app
        // had restarted: brand sequence, then the conversation. People read that
        // as "it reloaded", and in a messenger that reads as "did my message
        // survive?".
        //
        // The branded sequence belongs to a cold launch, where it is the only
        // thing on screen and the wait is real. Here it is noise on top of a
        // 200 ms absence.
        if privacyView == nil {
            let hostingController = UIHostingController(rootView: MinimalLaunchScreenView())
            let view = hostingController.view!
            view.frame = window.bounds
            view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            view.tag = 999999  // Tag for easy removal
            window.addSubview(view)
            privacyView = view
            
            OshiLog.app.info("🔒 LaunchScreenView added (app switcher protection)")
        }
    }
    
    // ✅ Called when scene becomes active again
    func sceneDidBecomeActive(_ scene: UIScene) {
        // Mirror sceneWillResignActive — VoiceCallManager listens here for
        // mid-call screen-unlock so it can flush jitter buffers + ask the
        // peer to do the same (resyncRequest signal).
        NotificationCenter.default.post(name: NSNotification.Name("OshiSceneDidBecomeActive"), object: nil)

        guard let windowScene = scene as? UIWindowScene else { return }
        guard let window = windowScene.windows.first else { return }

        // __PRIVACY_COVER_IS_NOT_A_RELAUNCH_2026_10_23__ Fade it out instead of
        // cutting it.
        //
        // A hard `removeFromSuperview()` is a jump cut: one frame of brand, next
        // frame of conversation. The eye reads a cut as a new scene — which is
        // precisely the "it restarted" impression we do not want. A short
        // cross-fade reads as "coming back", which is what actually happened.
        //
        // 180 ms: long enough to be perceived as a transition rather than a
        // glitch, short enough that nobody waits for it. The view is removed in
        // the completion, so the cover is never left behind if the animation is
        // interrupted — and `privacyView` is cleared FIRST so a rapid
        // background/foreground pair cannot find a stale reference and skip
        // adding a new cover.
        if let view = window.viewWithTag(999999) {
            privacyView = nil
            UIView.animate(withDuration: 0.18,
                           delay: 0,
                           options: [.curveEaseOut, .beginFromCurrentState],
                           animations: { view.alpha = 0 },
                           completion: { _ in view.removeFromSuperview() })
            OshiLog.app.info("✅ privacy cover faded out (app active)")
        }

        // 🔧 FIX R3: Removed duplicate checkPendingIPFSMessages() call here.
        // The onChange(of: scenePhase) in SecureWeb3MessengerApp.swift already handles foreground transition,
        // and the polling timer in MessageManager runs every 2s. No need for a 3rd trigger.

        // __ANATOLE_1311_IOS__ (N10) Opening the app no longer empties
        // Notification Centre. It used to call removeAllDeliveredNotifications()
        // and zero the badge here, so every unread banner vanished the moment
        // the app came to the front — before the person had read anything.
        // A banner now goes when it is tapped (iOS removes it) or when its
        // conversation is read: `MessageManager.markAsRead` (1:1) and
        // `MessageGroupManager.markGroupAsRead` (group). The badge is
        // recomputed from what is actually unread instead of forced to 0.
        // A panic wipe still clears everything (PanicWipe.cancelNotifications).
        MessageManager.shared?.syncAppBadge()
        ConversationPrefsStore.shared.republishMuted()

        // App Store review flow (HIG-compliant). This never prompts on launch by
        // itself: it counts the launch, then evaluates a milestone that was
        // already reached in prior use — and the request is only handed to
        // StoreKit once the screen is verified free, by `rateAppPrompt()`.
        Task { @MainActor in RateAppManager.shared.recordLaunchAndEvaluate() }

        // Check for app updates (throttled to once per day)
        AppUpdateManager.shared.checkForUpdate()
    }
    
    // ✅ Called when entering background
    func sceneDidEnterBackground(_ scene: UIScene) {
        // Privacy view already added in willResignActive
        OshiLog.app.info("💤 App entered background")
    }
    
    // ✅ Called when entering foreground
    func sceneWillEnterForeground(_ scene: UIScene) {
        OshiLog.app.info("📱 App entering foreground")
    }
}
