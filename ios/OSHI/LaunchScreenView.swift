//
//  LaunchScreenView.swift
//  OSHI
//
//  Dark purple, fast, interactive launch screen
//  Optimized for quick app switching
//

import SwiftUI

// MARK: - Brand Colors (Darker Purple)
private let oshiPurpleDark = Color(red: 0.15, green: 0.05, blue: 0.25)      // Very dark purple
private let oshiPurpleMid = Color(red: 0.25, green: 0.08, blue: 0.45)       // Mid purple
private let oshiPurpleAccent = Theme.brand // Brand accent
private let oshiPurpleGlow = Color(red: 0.5, green: 0.2, blue: 0.8)         // Glow color

struct LaunchScreenView: View {
    @State private var isAnimating = false
    @State private var pulseScale: CGFloat = 1.0
    @State private var ringRotation: Double = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// __OLED_BLACK_2026_11_16__ OLED black: the splash ground is true black.
    @Environment(\.oledBlackActive) private var oledBlack

    var body: some View {
        ZStack {
            // Dark purple gradient background (true black under OLED black)
            LinearGradient(
                colors: oledBlack ? [.black, .black] : [
                    Color(red: 0.08, green: 0.02, blue: 0.15),   // Deep purple-black
                    Color(red: 0.12, green: 0.04, blue: 0.22),   // Dark purple
                    Color(red: 0.06, green: 0.01, blue: 0.12)    // Almost black purple
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()
            
            // Subtle grid pattern
            GeometryReader { geometry in
                ZStack {
                    // Horizontal lines
                    ForEach(0..<12) { i in
                        Rectangle()
                            .fill(oshiPurpleAccent.opacity(0.08))
                            .frame(width: geometry.size.width, height: 0.5)
                            .position(x: geometry.size.width / 2, y: CGFloat(i) * (geometry.size.height / 12))
                    }
                    
                    // Vertical lines
                    ForEach(0..<8) { i in
                        Rectangle()
                            .fill(oshiPurpleAccent.opacity(0.06))
                            .frame(width: 0.5, height: geometry.size.height)
                            .position(x: CGFloat(i) * (geometry.size.width / 8), y: geometry.size.height / 2)
                    }
                }
            }
            .ignoresSafeArea()
            .accessibilityHidden(true)

            // Main content
            VStack(spacing: 24) {
                Spacer()
                
                // Logo container with glow effects
                ZStack {
                    // Outer pulsing glow
                    Circle()
                        .fill(
                            RadialGradient(
                                colors: [
                                    oshiPurpleGlow.opacity(0.3),
                                    oshiPurpleAccent.opacity(0.1),
                                    Color.clear
                                ],
                                center: .center,
                                startRadius: 40,
                                endRadius: 120
                            )
                        )
                        .frame(width: 240, height: 240)
                        .scaleEffect(pulseScale)
                    
                    // Rotating ring
                    Circle()
                        .trim(from: 0, to: 0.3)
                        .stroke(
                            LinearGradient(
                                colors: [oshiPurpleAccent, oshiPurpleGlow.opacity(0.5)],
                                startPoint: .leading,
                                endPoint: .trailing
                            ),
                            style: StrokeStyle(lineWidth: 2, lineCap: .round)
                        )
                        .frame(width: 160, height: 160)
                        .rotationEffect(.degrees(ringRotation))
                    
                    // Second rotating ring (opposite direction)
                    Circle()
                        .trim(from: 0, to: 0.2)
                        .stroke(oshiPurpleGlow.opacity(0.4), style: StrokeStyle(lineWidth: 1.5, lineCap: .round))
                        .frame(width: 180, height: 180)
                        .rotationEffect(.degrees(-ringRotation * 0.7))
                    
                    // Static outer ring
                    Circle()
                        .stroke(oshiPurpleAccent.opacity(0.3), lineWidth: 1)
                        .frame(width: 140, height: 140)

                    // App logo (matches the App Store icon)
                    Image("LaunchLogo")
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                        .frame(width: 116, height: 116)
                        .clipShape(RoundedRectangle(cornerRadius: 26, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 26, style: .continuous)
                                .stroke(oshiPurpleAccent.opacity(0.35), lineWidth: 1)
                        )
                        .shadow(color: oshiPurpleAccent.opacity(0.5), radius: 12)
                        .scaleEffect(isAnimating ? 1.0 : 0.9)
                }
                
                // App name
                VStack(spacing: 8) {
                    Text("OSHI")
                        .font(.scaled(42, weight: .bold, design: .rounded))
                        .foregroundStyle(
                            LinearGradient(
                                colors: [.white, Color(red: 0.8, green: 0.7, blue: 1.0)],
                                startPoint: .top,
                                endPoint: .bottom
                            )
                        )
                        .shadow(color: oshiPurpleAccent.opacity(0.5), radius: 8)
                        .tracking(6)
                    
                    Text("launch_subtitle".localized)
                        .font(.scaled(14, weight: .medium))
                        .foregroundColor(oshiPurpleGlow.opacity(0.9))
                        .tracking(2)
                }
                .opacity(isAnimating ? 1.0 : 0.0)
                
                Spacer()
                
                // Bottom loading indicator
                HStack(spacing: 6) {
                    ForEach(0..<3) { i in
                        Circle()
                            .fill(oshiPurpleAccent)
                            .frame(width: 6, height: 6)
                            .scaleEffect(isAnimating ? 1.0 : 0.5)
                            .opacity(isAnimating ? 1.0 : 0.3)
                            .animation(
                                .easeInOut(duration: 0.4)
                                .repeatForever(autoreverses: true)
                                .delay(Double(i) * 0.15),
                                value: isAnimating
                            )
                    }
                }
                .padding(.bottom, 50)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("OSHI")
        .accessibilityAddTraits(.isImage)
        .onAppear {
            // Fast initial animation (was 1.2s, now 0.3s)
            withAnimation(.easeOut(duration: 0.3)) {
                isAnimating = true
            }

            // Respect Reduce Motion: skip the continuous pulse/rotation that
            // would otherwise loop forever behind the app.
            guard !reduceMotion else { return }

            // Continuous pulse animation
            withAnimation(.easeInOut(duration: 1.5).repeatForever(autoreverses: true)) {
                pulseScale = 1.08
            }

            // Ring rotation - continuous
            withAnimation(.linear(duration: 3).repeatForever(autoreverses: false)) {
                ringRotation = 360
            }
        }
    }
}

// MARK: - Minimal Launch Screen (for fastest loading)
struct MinimalLaunchScreenView: View {
    var body: some View {
        ZStack {
            // Dark purple background
            Color(red: 0.08, green: 0.02, blue: 0.15)
                .ignoresSafeArea()
            
            VStack(spacing: 16) {
                // App logo (matches the App Store icon)
                Image("LaunchLogo")
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .frame(width: 96, height: 96)
                    .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))

                Text("OSHI")
                    .font(.scaled(36, weight: .bold))
                    .foregroundColor(.white)
                    .tracking(4)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("OSHI")
        }
    }
}

#Preview("Launch Screen") {
    LaunchScreenView()
}

#Preview("Minimal") {
    MinimalLaunchScreenView()
}
