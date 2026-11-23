//
//  FirstTimeGuideView.swift
//  OSHI - Interactive First Time User Guide
//
//  Shows step-by-step tooltips pointing to specific UI elements
//  Explains the collaborative mesh network concept
//

import SwiftUI

// MARK: - Brand Color
private let appBrandColor = Theme.brand

// MARK: - Guide Step Enum
enum GuideStep: Int, CaseIterable {
    case welcome = 0
    case messagesTab = 1
    case groupsTab = 2
    case newMessageTab = 3
    case settingsTab = 4
    case collaborative = 5
    case complete = 6
    
    var title: String {
        switch self {
        case .welcome: return "guide_welcome_title".localized
        case .messagesTab: return "guide_messages_tab_title".localized
        case .groupsTab: return "guide_groups_tab_title".localized
        case .newMessageTab: return "guide_new_tab_title".localized
        case .settingsTab: return "guide_settings_tab_title".localized
        case .collaborative: return "guide_collaborative_title".localized
        case .complete: return "guide_complete_title".localized
        }
    }
    
    var description: String {
        switch self {
        case .welcome: return "guide_welcome_desc".localized
        case .messagesTab: return "guide_messages_tab_desc".localized
        case .groupsTab: return "guide_groups_tab_desc".localized
        case .newMessageTab: return "guide_new_tab_desc".localized
        case .settingsTab: return "guide_settings_tab_desc".localized
        case .collaborative: return "guide_collaborative_desc".localized
        case .complete: return "guide_complete_desc".localized
        }
    }
    
    var icon: String {
        switch self {
        case .welcome: return "hand.wave.fill"
        case .messagesTab: return "message.fill"
        case .groupsTab: return "person.3.fill"
        case .newMessageTab: return "square.and.pencil"
        case .settingsTab: return "gear"
        case .collaborative: return "antenna.radiowaves.left.and.right"
        case .complete: return "checkmark.circle.fill"
        }
    }
    
    var iconColor: Color {
        switch self {
        case .welcome: return appBrandColor
        case .messagesTab: return appBrandColor
        case .groupsTab: return .purple
        case .newMessageTab: return .green
        case .settingsTab: return .gray
        case .collaborative: return .orange
        case .complete: return .green
        }
    }
    
    // Which tab this step refers to (for highlighting)
    var targetTab: Int? {
        switch self {
        case .welcome: return nil
        case .messagesTab: return 0
        case .groupsTab: return 1
        case .newMessageTab: return 2
        case .settingsTab: return 4
        case .collaborative: return nil
        case .complete: return nil
        }
    }
    
    // Position of the tooltip
    var tooltipPosition: TooltipPosition {
        switch self {
        case .welcome: return .center
        case .messagesTab: return .aboveTabBar(tabIndex: 0)
        case .groupsTab: return .aboveTabBar(tabIndex: 1)
        case .newMessageTab: return .aboveTabBar(tabIndex: 2)
        case .settingsTab: return .aboveTabBar(tabIndex: 4)
        case .collaborative: return .center
        case .complete: return .center
        }
    }
}

enum TooltipPosition {
    case center
    case aboveTabBar(tabIndex: Int)
}

// MARK: - Guide Manager
class FirstTimeGuideManager: ObservableObject {
    static let shared = FirstTimeGuideManager()
    
    @AppStorage("hasCompletedFirstTimeGuide") var hasCompletedGuide = false
    @AppStorage("currentGuideStep") private var currentStepRaw = 0
    
    @Published var isShowingGuide = false
    @Published var currentStep: GuideStep = .welcome
    
    private init() {
        currentStep = GuideStep(rawValue: currentStepRaw) ?? .welcome
    }
    
    func startGuide() {
        guard !hasCompletedGuide else { return }
        currentStep = .welcome
        currentStepRaw = 0
        isShowingGuide = true
    }
    
    func nextStep() {
        let nextRaw = currentStep.rawValue + 1
        if let next = GuideStep(rawValue: nextRaw) {
            currentStep = next
            currentStepRaw = nextRaw
            
            if next == .complete {
                // Auto-complete after showing complete step
                DispatchQueue.main.asyncAfter(deadline: .now() + 4) {
                    self.completeGuide()
                }
            }
        } else {
            completeGuide()
        }
    }
    
    func skipGuide() {
        completeGuide()
    }
    
    func completeGuide() {
        hasCompletedGuide = true
        isShowingGuide = false
        currentStep = .complete
    }
    
    func resetGuide() {
        hasCompletedGuide = false
        currentStepRaw = 0
        currentStep = .welcome
    }
}

// MARK: - Guide Overlay View
struct FirstTimeGuideOverlay: View {
    @ObservedObject var guideManager = FirstTimeGuideManager.shared
    @Binding var selectedTab: Int
    
    var body: some View {
        if guideManager.isShowingGuide && !guideManager.hasCompletedGuide {
            GeometryReader { geometry in
                ZStack {
                    // Semi-transparent background with cutout for highlighted tab
                    GuideBackgroundWithCutout(
                        step: guideManager.currentStep,
                        geometry: geometry
                    )
                    .ignoresSafeArea()
                    
                    // Tooltip card positioned based on step
                    tooltipView(for: guideManager.currentStep, in: geometry)
                }
            }
            .transition(.opacity)
            .animation(.easeInOut(duration: 0.3), value: guideManager.currentStep)
        }
    }
    
    @ViewBuilder
    private func tooltipView(for step: GuideStep, in geometry: GeometryProxy) -> some View {
        let card = GuideTooltipCard(
            step: step,
            stepNumber: step.rawValue + 1,
            totalSteps: GuideStep.allCases.count,
            onNext: {
                guideManager.nextStep()
            },
            onSkip: {
                guideManager.skipGuide()
            },
            isLastStep: step == .complete
        )
        
        switch step.tooltipPosition {
        case .center:
            card
                .padding(.horizontal, 24)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            
        case .aboveTabBar(let tabIndex):
            VStack {
                Spacer()
                
                card
                    .padding(.horizontal, 24)
                
                // Arrow pointing down to tab
                Image(systemName: "arrowtriangle.down.fill")
                    .font(.scaled(24))
                    .foregroundColor(Color(red: 0.1, green: 0.15, blue: 0.2))
                    .offset(x: tabOffset(for: tabIndex, in: geometry))
                
                Spacer()
                    .frame(height: 90) // Space for tab bar
            }
        }
    }
    
    private func tabOffset(for tabIndex: Int, in geometry: GeometryProxy) -> CGFloat {
        let tabWidth = geometry.size.width / 5
        let centerX = geometry.size.width / 2
        let tabCenterX = tabWidth * CGFloat(tabIndex) + tabWidth / 2
        return tabCenterX - centerX
    }
}

// MARK: - Background with Cutout for Tab Highlight
struct GuideBackgroundWithCutout: View {
    let step: GuideStep
    let geometry: GeometryProxy
    
    var body: some View {
        ZStack {
            // Dark overlay
            Color.black.opacity(0.75)
            
            // Highlight circle around the target tab
            if let tabIndex = step.targetTab {
                let tabWidth = geometry.size.width / 5
                let tabCenterX = tabWidth * CGFloat(tabIndex) + tabWidth / 2
                let tabBarY = geometry.size.height - 45 // Approximate tab bar center
                
                // Glowing highlight
                Circle()
                    .fill(
                        RadialGradient(
                            colors: [
                                step.iconColor.opacity(0.4),
                                step.iconColor.opacity(0.2),
                                Color.clear
                            ],
                            center: .center,
                            startRadius: 20,
                            endRadius: 50
                        )
                    )
                    .frame(width: 100, height: 100)
                    .position(x: tabCenterX, y: tabBarY)
                
                // Pulsing ring
                Circle()
                    .stroke(step.iconColor, lineWidth: 3)
                    .frame(width: 60, height: 60)
                    .position(x: tabCenterX, y: tabBarY)
                    .modifier(PulsingModifier())
            }
        }
    }
}

// MARK: - Pulsing Animation Modifier
struct PulsingModifier: ViewModifier {
    @State private var isPulsing = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .scaleEffect(isPulsing ? 1.2 : 1.0)
            .opacity(isPulsing ? 0.5 : 1.0)
            .animation(
                reduceMotion ? nil :
                Animation.easeInOut(duration: 1.0).repeatForever(autoreverses: true),
                value: isPulsing
            )
            .onAppear {
                // Respect Reduce Motion: don't start the forever-pulsing ring.
                isPulsing = !reduceMotion
            }
    }
}

// MARK: - Guide Tooltip Card
struct GuideTooltipCard: View {
    let step: GuideStep
    let stepNumber: Int
    let totalSteps: Int
    let onNext: () -> Void
    let onSkip: () -> Void
    let isLastStep: Bool
    
    var body: some View {
        VStack(spacing: 16) {
            // Progress indicator
            HStack(spacing: 6) {
                ForEach(0..<totalSteps, id: \.self) { index in
                    Capsule()
                        .fill(index < stepNumber ? step.iconColor : Color.gray.opacity(0.3))
                        .frame(width: index < stepNumber ? 20 : 8, height: 6)
                }
            }
            .padding(.top, 16)
            .accessibilityHidden(true)

            // Icon
            ZStack {
                Circle()
                    .fill(step.iconColor.opacity(0.2))
                    .frame(width: 70, height: 70)

                Image(systemName: step.icon)
                    .font(.scaled(32))
                    .foregroundColor(step.iconColor)
            }
            .accessibilityHidden(true)

            // Step indicator
            Text("\(stepNumber)/\(totalSteps)")
                .font(.scaled(13, weight: .medium))
                .foregroundColor(.white.opacity(0.5))

            // Title
            Text(step.title)
                .font(.scaled(22, weight: .bold))
                .foregroundColor(.white)
                .multilineTextAlignment(.center)

            // Description
            Text(step.description)
                .font(.scaled(15))
                .foregroundColor(.white.opacity(0.85))
                .multilineTextAlignment(.center)
                .lineSpacing(4)
                .padding(.horizontal, 16)
                .fixedSize(horizontal: false, vertical: true)

            Spacer().frame(height: 8)
            
            // Buttons
            VStack(spacing: 10) {
                // Next/Complete button
                Button {
                    #if !targetEnvironment(macCatalyst)
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    #endif
                    onNext()
                } label: {
                    HStack {
                        Text(isLastStep ? "guide_button_start".localized : "guide_button_next".localized)
                            .font(.scaled(16, weight: .semibold))

                        if !isLastStep {
                            Image(systemName: "arrow.right")
                                .font(.scaled(14, weight: .semibold))
                                .accessibilityHidden(true)
                        }
                    }
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 48)
                    .background(
                        LinearGradient(
                            colors: [step.iconColor, step.iconColor.opacity(0.8)],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                    )
                    .cornerRadius(12)
                }

                // Skip button (not on last step)
                if !isLastStep && stepNumber > 1 {
                    Button(action: onSkip) {
                        Text("guide_button_skip".localized)
                            .font(.scaled(14))
                            .foregroundColor(.white.opacity(0.5))
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .contentShape(Rectangle())
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 20)
        }
        .frame(maxWidth: 460)
        .background(
            RoundedRectangle(cornerRadius: 20)
                .fill(
                    LinearGradient(
                        colors: [
                            Color(red: 0.1, green: 0.15, blue: 0.2),
                            Color(red: 0.05, green: 0.1, blue: 0.15)
                        ],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
        )
        .overlay(
            RoundedRectangle(cornerRadius: 20)
                .stroke(step.iconColor.opacity(0.3), lineWidth: 1)
        )
        .shadow(color: .black.opacity(0.4), radius: 20, x: 0, y: 10)
    }
}

// MARK: - Preview
#if DEBUG
struct FirstTimeGuideView_Previews: PreviewProvider {
    static var previews: some View {
        ZStack {
            Color.gray
            FirstTimeGuideOverlay(selectedTab: .constant(0))
        }
        .onAppear {
            FirstTimeGuideManager.shared.isShowingGuide = true
            FirstTimeGuideManager.shared.currentStep = .messagesTab
        }
    }
}
#endif
