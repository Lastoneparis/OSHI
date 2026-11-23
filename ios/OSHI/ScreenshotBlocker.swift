//
//  ScreenshotBlocker.swift
//  OSHI - Screenshot Protection (WhatsApp Style)
//
//  Shows LaunchScreenView in screenshots by using secure text field
//

import SwiftUI
import UIKit

// MARK: - Secure Field View (UIKit)

class SecureFieldViewController: UIViewController {
    private var secureTextField: UITextField!
    private var launchScreenHost: UIHostingController<LaunchScreenView>!
    private var contentHost: UIHostingController<AnyView>!
    
    init<Content: View>(content: Content) {
        self.contentHost = UIHostingController(rootView: AnyView(content))
        super.init(nibName: nil, bundle: nil)
    }
    
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
    
    override func viewDidLoad() {
        super.viewDidLoad()
        
        // 1. Create secure text field (this makes screenshots show what's inside it)
        secureTextField = UITextField()
        secureTextField.isSecureTextEntry = true
        secureTextField.isUserInteractionEnabled = false
        secureTextField.backgroundColor = .clear
        secureTextField.text = " "  // Needs text to be active
        view.addSubview(secureTextField)
        
        secureTextField.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            secureTextField.topAnchor.constraint(equalTo: view.topAnchor),
            secureTextField.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            secureTextField.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            secureTextField.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
        
        // 2. Add LaunchScreenView INSIDE the secure field
        launchScreenHost = UIHostingController(rootView: LaunchScreenView())
        launchScreenHost.view.backgroundColor = .systemBackground
        
        if let textFieldContentView = secureTextField.subviews.first {
            textFieldContentView.addSubview(launchScreenHost.view)
            launchScreenHost.view.frame = textFieldContentView.bounds
            launchScreenHost.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            OshiLog.ui.info("✅ LaunchScreenView placed inside secure field")
        }
        
        // 3. Add actual content OUTSIDE the secure field (for normal viewing)
        addChild(contentHost)
        view.addSubview(contentHost.view)
        contentHost.view.frame = view.bounds
        contentHost.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        contentHost.view.backgroundColor = .clear
        contentHost.didMove(toParent: self)
        
        OshiLog.ui.info("🔒 Screenshot protection active: LaunchScreenView will appear in screenshots")
    }
    
    func updateContent<Content: View>(_ content: Content) {
        contentHost.rootView = AnyView(content)
    }
}

// MARK: - SwiftUI Wrapper

struct ScreenshotProtectedView<Content: View>: UIViewControllerRepresentable {
    let content: Content
    
    func makeUIViewController(context: Context) -> SecureFieldViewController {
        return SecureFieldViewController(content: content)
    }
    
    func updateUIViewController(_ uiViewController: SecureFieldViewController, context: Context) {
        uiViewController.updateContent(content)
    }
}

// MARK: - View Extension

extension View {
    /// Protects this view by showing LaunchScreenView in screenshots
    /// Works like WhatsApp's disappearing photo protection
    func protectWithLaunchScreen() -> some View {
        ScreenshotProtectedView(content: self)
    }
}
