//
//  ScreenshotShieldWindow.swift
//  OSHI - Always-Ready Screenshot Protection
//
//  Shows LaunchScreenView BEFORE screenshot is captured
//  Used by Revolut, N26, Crypto.com, banking apps
//

import UIKit
import SwiftUI

final class ScreenshotShieldWindow: UIWindow {
    static let shared = ScreenshotShieldWindow()
    
    private let shieldHosting = UIHostingController(rootView: LaunchScreenView())
    
    private override init(frame: CGRect = UIScreen.main.bounds) {
        super.init(frame: frame)
        
        self.windowLevel = .alert + 5   // ✅ Always above your app
        self.isHidden = false           // ✅ Window is active
        self.isUserInteractionEnabled = false  // ✅ Don't block touches
        
        rootViewController = UIViewController()
        rootViewController?.view.backgroundColor = .clear
        shieldHosting.view.backgroundColor = .systemBackground
        shieldHosting.view.isUserInteractionEnabled = false
        shieldHosting.view.alpha = 0.0  // ✅ Hidden by default (invisible)
        
        rootViewController?.view.addSubview(shieldHosting.view)
        shieldHosting.view.frame = rootViewController?.view.bounds ?? .zero
        shieldHosting.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        
        OshiLog.ui.info("🔒 Screenshot shield window initialized (always ready)")
    }
    
    required init?(coder: NSCoder) {
        fatalError("init(coder:) not implemented")
    }
    
    /// Show the shield INSTANTLY (synchronous)
    func showShield() {
        // No animation - instant!
        self.shieldHosting.view.alpha = 1.0
        OshiLog.ui.info("🔒 Shield SHOWN (alpha=1.0)")
    }
    
    /// Hide the shield after delay
    func hideShield() {
        UIView.animate(withDuration: 0.2) {
            self.shieldHosting.view.alpha = 0.0
        }
        OshiLog.ui.info("✅ Shield HIDDEN (alpha=0.0)")
    }
}
