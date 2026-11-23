//
//  MeshBoosterManager.swift
//  OSHI - Gamification & Rewards System
//
//  🏆 Rewards users who actively participate in the mesh network
//  by relaying messages for others.
//

import Foundation
import SwiftUI
import Combine

// MARK: - Badge Types

enum MeshBadge: String, Codable, CaseIterable {
    case newcomer = "newcomer"           // First relay
    case helper = "helper"               // 10 relays
    case connector = "connector"         // 50 relays
    case meshHero = "mesh_hero"          // 100 relays
    case networkGuardian = "guardian"    // 500 relays
    case meshLegend = "legend"           // 1000 relays
    case ultraBooster = "ultra"          // 5000 relays
    
    var threshold: Int {
        switch self {
        case .newcomer: return 1
        case .helper: return 10
        case .connector: return 50
        case .meshHero: return 100
        case .networkGuardian: return 500
        case .meshLegend: return 1000
        case .ultraBooster: return 5000
        }
    }
    
    var icon: String {
        switch self {
        case .newcomer: return "🌱"
        case .helper: return "🤝"
        case .connector: return "🔗"
        case .meshHero: return "🦸"
        case .networkGuardian: return "🛡️"
        case .meshLegend: return "⭐"
        case .ultraBooster: return "🚀"
        }
    }
    
    var title: String {
        switch self {
        case .newcomer: return "mesh_badge_newcomer".localized
        case .helper: return "mesh_badge_helper".localized
        case .connector: return "mesh_badge_connector".localized
        case .meshHero: return "mesh_badge_hero".localized
        case .networkGuardian: return "mesh_badge_guardian".localized
        case .meshLegend: return "mesh_badge_legend".localized
        case .ultraBooster: return "mesh_badge_ultra".localized
        }
    }
    
    var localizedTitle: String {
        switch self {
        case .newcomer: return "Newcomer"
        case .helper: return "Helper"
        case .connector: return "Connector"
        case .meshHero: return "Mesh Hero"
        case .networkGuardian: return "Network Guardian"
        case .meshLegend: return "Mesh Legend"
        case .ultraBooster: return "Ultra Booster"
        }
    }
    
    var description: String {
        switch self {
        case .newcomer: return "First message relayed!"
        case .helper: return "Relayed 10 messages"
        case .connector: return "Relayed 50 messages"
        case .meshHero: return "Relayed 100 messages"
        case .networkGuardian: return "Relayed 500 messages"
        case .meshLegend: return "Relayed 1000 messages"
        case .ultraBooster: return "Relayed 5000 messages!"
        }
    }
    
    var color: Color {
        switch self {
        case .newcomer: return .green
        case .helper: return .blue
        case .connector: return .cyan
        case .meshHero: return .orange
        case .networkGuardian: return .purple
        case .meshLegend: return .yellow
        case .ultraBooster: return Color(red: 1, green: 0.2, blue: 0.5) // Pink/Red gradient
        }
    }
    
    /// Next badge to unlock (nil if max)
    var next: MeshBadge? {
        switch self {
        case .newcomer: return .helper
        case .helper: return .connector
        case .connector: return .meshHero
        case .meshHero: return .networkGuardian
        case .networkGuardian: return .meshLegend
        case .meshLegend: return .ultraBooster
        case .ultraBooster: return nil
        }
    }
}

// MARK: - User Stats

struct MeshUserStats: Codable {
    var totalRelays: Int = 0
    var relaysToday: Int = 0
    var relaysThisWeek: Int = 0
    var relaysThisMonth: Int = 0
    var lastRelayDate: Date?
    var consecutiveDays: Int = 0
    var longestStreak: Int = 0
    var peersHelped: Set<String> = []
    var earnedBadges: [MeshBadge] = []
    var lastBadgeEarned: MeshBadge?
    var joinDate: Date = Date()
    
    // Computed
    var currentBadge: MeshBadge {
        earnedBadges.max(by: { $0.threshold < $1.threshold }) ?? .newcomer
    }
    
    var progressToNextBadge: Double {
        guard let next = currentBadge.next else { return 1.0 }
        let current = currentBadge.threshold
        let progress = Double(totalRelays - current) / Double(next.threshold - current)
        return min(max(progress, 0), 1.0)
    }
    
    var relaysToNextBadge: Int {
        guard let next = currentBadge.next else { return 0 }
        return max(0, next.threshold - totalRelays)
    }
}

// MARK: - Mesh Booster Manager

class MeshBoosterManager: ObservableObject {
    static let shared = MeshBoosterManager()
    
    @Published var stats: MeshUserStats = MeshUserStats()
    @Published var showBadgeUnlocked: Bool = false
    @Published var unlockedBadge: MeshBadge?
    @Published var showRelayAnimation: Bool = false
    
    private let storageKey = "meshBoosterStats"
    private var cancellables = Set<AnyCancellable>()
    
    private init() {
        loadStats()
        setupObservers()
        resetDailyStatsIfNeeded()
        
        OshiLog.mesh.info("")
        OshiLog.mesh.info("╔════════════════════════════════════════════════════════╗")
        OshiLog.mesh.info("║  🏆 MeshBoosterManager INITIALIZED                    ║")
        OshiLog.mesh.info("║  📊 Total relays: \(stats.totalRelays)                              ║")
        OshiLog.mesh.info("║  🎖️ Current badge: \(stats.currentBadge.icon) \(stats.currentBadge.localizedTitle)              ║")
        OshiLog.mesh.info("╚════════════════════════════════════════════════════════╝")
        OshiLog.mesh.info("")
    }
    
    // MARK: - Record Relay
    
    /// Call this when a message is relayed through this device
    func recordRelay(forPeer peerKey: String? = nil) {
        let now = Date()
        
        // Update counts
        stats.totalRelays += 1
        stats.relaysToday += 1
        stats.relaysThisWeek += 1
        stats.relaysThisMonth += 1
        
        // Track peer helped
        if let peer = peerKey {
            stats.peersHelped.insert(String(peer.prefix(20)))
        }
        
        // Update streak
        if let lastRelay = stats.lastRelayDate {
            let calendar = Calendar.current
            if calendar.isDateInYesterday(lastRelay) {
                stats.consecutiveDays += 1
                stats.longestStreak = max(stats.longestStreak, stats.consecutiveDays)
            } else if !calendar.isDateInToday(lastRelay) {
                stats.consecutiveDays = 1
            }
        } else {
            stats.consecutiveDays = 1
        }
        
        stats.lastRelayDate = now
        
        // Check for new badges
        checkForNewBadges()
        
        // Show relay animation
        DispatchQueue.main.async {
            self.showRelayAnimation = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                self.showRelayAnimation = false
            }
        }
        
        saveStats()
        
        OshiLog.mesh.info("📡 Relay recorded! Total: \(stats.totalRelays)")
    }
    
    // MARK: - Badge Checking
    
    private func checkForNewBadges() {
        for badge in MeshBadge.allCases {
            if stats.totalRelays >= badge.threshold && !stats.earnedBadges.contains(badge) {
                // New badge earned!
                stats.earnedBadges.append(badge)
                stats.lastBadgeEarned = badge
                
                DispatchQueue.main.async {
                    self.unlockedBadge = badge
                    self.showBadgeUnlocked = true
                    
                    // Post notification for UI
                    NotificationCenter.default.post(
                        name: .meshBadgeUnlocked,
                        object: nil,
                        userInfo: ["badge": badge]
                    )
                }
                
                OshiLog.mesh.info("🏆 NEW BADGE UNLOCKED: \(badge.icon) \(badge.localizedTitle)")
                
                // Haptic feedback
                let generator = UINotificationFeedbackGenerator()
                generator.notificationOccurred(.success)
            }
        }
    }
    
    // MARK: - Daily Reset
    
    private func resetDailyStatsIfNeeded() {
        guard let lastRelay = stats.lastRelayDate else { return }
        
        let calendar = Calendar.current
        
        // Reset daily
        if !calendar.isDateInToday(lastRelay) {
            stats.relaysToday = 0
        }
        
        // Reset weekly (Monday)
        if let lastWeek = calendar.dateComponents([.weekOfYear], from: lastRelay).weekOfYear,
           let currentWeek = calendar.dateComponents([.weekOfYear], from: Date()).weekOfYear,
           lastWeek != currentWeek {
            stats.relaysThisWeek = 0
        }
        
        // Reset monthly
        if let lastMonth = calendar.dateComponents([.month], from: lastRelay).month,
           let currentMonth = calendar.dateComponents([.month], from: Date()).month,
           lastMonth != currentMonth {
            stats.relaysThisMonth = 0
        }
        
        saveStats()
    }
    
    // MARK: - Observers
    
    private func setupObservers() {
        // Listen for mesh relay events
        NotificationCenter.default.addObserver(
            forName: NSNotification.Name("MeshMessageRelayed"),
            object: nil,
            queue: .main
        ) { [weak self] notification in
            let peerKey = notification.userInfo?["peerKey"] as? String
            self?.recordRelay(forPeer: peerKey)
        }
    }
    
    // MARK: - Persistence
    
    private func loadStats() {
        guard let data = UserDefaults.standard.data(forKey: storageKey),
              let decoded = try? JSONDecoder().decode(MeshUserStats.self, from: data) else {
            stats = MeshUserStats()
            return
        }
        stats = decoded
    }
    
    private func saveStats() {
        if let encoded = try? JSONEncoder().encode(stats) {
            UserDefaults.standard.set(encoded, forKey: storageKey)
        }
    }
    
    // MARK: - Public Methods
    
    func dismissBadgeAlert() {
        showBadgeUnlocked = false
        unlockedBadge = nil
    }
    
    func getLeaderboardPosition() -> Int {
        // Placeholder - would need server for real leaderboard
        // For now, estimate based on relay count
        if stats.totalRelays >= 5000 { return 1 }
        if stats.totalRelays >= 1000 { return Int.random(in: 2...10) }
        if stats.totalRelays >= 500 { return Int.random(in: 11...50) }
        if stats.totalRelays >= 100 { return Int.random(in: 51...200) }
        if stats.totalRelays >= 50 { return Int.random(in: 201...500) }
        return Int.random(in: 501...1000)
    }
}

// MARK: - Notification Names

extension Notification.Name {
    static let meshBadgeUnlocked = Notification.Name("MeshBadgeUnlocked")
    static let meshRelayRecorded = Notification.Name("MeshRelayRecorded")
}

// MARK: - Badge View Component

struct MeshBadgeView: View {
    let badge: MeshBadge
    let size: CGFloat
    let showTitle: Bool
    
    init(badge: MeshBadge, size: CGFloat = 60, showTitle: Bool = true) {
        self.badge = badge
        self.size = size
        self.showTitle = showTitle
    }
    
    var body: some View {
        VStack(spacing: 4) {
            ZStack {
                Circle()
                    .fill(
                        LinearGradient(
                            colors: [badge.color.opacity(0.3), badge.color.opacity(0.1)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .frame(width: size, height: size)
                
                Circle()
                    .strokeBorder(badge.color, lineWidth: 2)
                    .frame(width: size, height: size)
                
                Text(badge.icon)
                    .font(.system(size: size * 0.5))
            }
            
            if showTitle {
                Text(badge.localizedTitle)
                    .font(.caption2)
                    .fontWeight(.medium)
                    .foregroundColor(badge.color)
            }
        }
    }
}

// MARK: - Badge Unlock Alert View

struct BadgeUnlockView: View {
    let badge: MeshBadge
    let onDismiss: () -> Void
    
    @State private var scale: CGFloat = 0.5
    @State private var opacity: Double = 0
    @State private var rotation: Double = -30
    
    var body: some View {
        ZStack {
            // Background blur
            Color.black.opacity(0.6)
                .ignoresSafeArea()
                .onTapGesture { onDismiss() }
            
            VStack(spacing: 20) {
                Text("🎉")
                    .font(.scaled(50))
                
                Text("badge_unlocked".localized)
                    .font(.title2)
                    .fontWeight(.bold)
                    .foregroundColor(.white)
                
                MeshBadgeView(badge: badge, size: 100)
                    .rotationEffect(.degrees(rotation))
                
                Text(badge.description)
                    .font(.body)
                    .foregroundColor(.white.opacity(0.8))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)
                
                Button(action: onDismiss) {
                    Text("awesome".localized)
                        .font(.headline)
                        .foregroundColor(.white)
                        .padding(.horizontal, 40)
                        .padding(.vertical, 12)
                        .background(badge.color)
                        .cornerRadius(25)
                }
                .padding(.top, 10)
            }
            .padding(30)
            .background(
                RoundedRectangle(cornerRadius: 25)
                    .fill(Color(UIColor.systemBackground).opacity(0.95))
            )
            .scaleEffect(scale)
            .opacity(opacity)
        }
        .onAppear {
            withAnimation(.spring(response: 0.5, dampingFraction: 0.6)) {
                scale = 1.0
                opacity = 1.0
                rotation = 0
            }
            
            // Haptic
            let generator = UINotificationFeedbackGenerator()
            generator.notificationOccurred(.success)
        }
    }
}

// MARK: - Stats Card View

struct MeshBoosterStatsCard: View {
    @ObservedObject var manager = MeshBoosterManager.shared
    
    private let brandColor = Theme.brand
    
    var body: some View {
        VStack(spacing: 16) {
            // Current Badge
            HStack {
                MeshBadgeView(badge: manager.stats.currentBadge, size: 50)
                
                VStack(alignment: .leading, spacing: 4) {
                    Text("mesh_booster_status".localized)
                        .font(.caption)
                        .foregroundColor(.secondary)
                    
                    Text(manager.stats.currentBadge.localizedTitle)
                        .font(.headline)
                        .foregroundColor(manager.stats.currentBadge.color)
                }
                
                Spacer()
                
                VStack(alignment: .trailing) {
                    Text("\(manager.stats.totalRelays)")
                        .font(.title2)
                        .fontWeight(.bold)
                        .foregroundColor(brandColor)
                    
                    Text("relays_total".localized)
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
            }
            
            // Progress to next badge
            if let next = manager.stats.currentBadge.next {
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text("next_badge".localized)
                            .font(.caption)
                            .foregroundColor(.secondary)
                        
                        Spacer()
                        
                        Text("\(manager.stats.relaysToNextBadge) " + "relays_remaining".localized)
                            .font(.caption)
                            .foregroundColor(next.color)
                    }
                    
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            RoundedRectangle(cornerRadius: 4)
                                .fill(Color.gray.opacity(0.2))
                                .frame(height: 8)
                            
                            RoundedRectangle(cornerRadius: 4)
                                .fill(
                                    LinearGradient(
                                        colors: [manager.stats.currentBadge.color, next.color],
                                        startPoint: .leading,
                                        endPoint: .trailing
                                    )
                                )
                                .frame(width: geo.size.width * manager.stats.progressToNextBadge, height: 8)
                        }
                    }
                    .frame(height: 8)
                    
                    HStack {
                        Text(manager.stats.currentBadge.icon)
                        Spacer()
                        Text(next.icon + " " + next.localizedTitle)
                            .font(.caption2)
                            .foregroundColor(next.color)
                    }
                }
            }
            
            // Quick stats
            HStack(spacing: 20) {
                BoosterStatItem(value: manager.stats.relaysToday, label: "today".localized, icon: "sun.max.fill")
                BoosterStatItem(value: manager.stats.consecutiveDays, label: "streak".localized, icon: "flame.fill")
                BoosterStatItem(value: manager.stats.peersHelped.count, label: "peers".localized, icon: "person.2.fill")
            }
        }
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(Color(UIColor.secondarySystemBackground))
        )
    }
}

private struct BoosterStatItem: View {
    let value: Int
    let label: String
    let icon: String
    
    var body: some View {
        VStack(spacing: 4) {
            Image(systemName: icon)
                .font(.caption)
                .foregroundColor(.orange)
            
            Text("\(value)")
                .font(.headline)
                .fontWeight(.bold)
            
            Text(label)
                .font(.caption2)
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity)
    }
}

// MARK: - Relay Animation Overlay

struct RelayAnimationOverlay: View {
    @Binding var isVisible: Bool
    
    @State private var scale: CGFloat = 0.5
    @State private var opacity: Double = 1
    @State private var yOffset: CGFloat = 0
    
    var body: some View {
        if isVisible {
            VStack {
                Spacer()
                
                HStack {
                    Spacer()
                    
                    HStack(spacing: 8) {
                        Image(systemName: "arrow.triangle.2.circlepath")
                            .font(.scaled(16, weight: .bold))
                        
                        Text("+1 Relay")
                            .font(.caption)
                            .fontWeight(.bold)
                    }
                    .foregroundColor(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(
                        Capsule()
                            .fill(Color.green)
                    )
                    .scaleEffect(scale)
                    .opacity(opacity)
                    .offset(y: yOffset)
                    .padding(.trailing, 20)
                    .padding(.bottom, 100)
                }
            }
            .onAppear {
                withAnimation(.spring(response: 0.3, dampingFraction: 0.6)) {
                    scale = 1.0
                }
                
                withAnimation(.easeOut(duration: 1.5).delay(0.5)) {
                    yOffset = -50
                    opacity = 0
                }
            }
        }
    }
}
