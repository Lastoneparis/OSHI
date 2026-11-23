//
//  MeshBoosterView.swift
//  OSHI - Mesh Booster Stats & Badges
//
//  🏆 Full view showing all gamification stats and earned badges
//

import SwiftUI

struct MeshBoosterView: View {
    @ObservedObject var booster = MeshBoosterManager.shared
    @State private var selectedCategory: BadgeCategory = .all
    
    private let brandColor = Theme.brand
    
    enum BadgeCategory: String, CaseIterable {
        case all = "All"
        case earned = "Earned"
        case locked = "Locked"

        var localizedString: String {
            switch self {
            case .all: return "mesh_booster.category.all".localized
            case .earned: return "mesh_booster.category.earned".localized
            case .locked: return "mesh_booster.category.locked".localized
            }
        }
    }
    
    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                // Level Card
                levelCard

                // Stats Grid
                statsGrid

                // Progress Section
                progressSection

                // Badges Section
                badgesSection
            }
            .padding()
            // Cap content width on iPad / landscape so cards don't stretch edge-to-edge
            .frame(maxWidth: 640)
            .frame(maxWidth: .infinity)
        }
        .navigationTitle("mesh_booster.title".localized)
        .navigationBarTitleDisplayMode(.large)
        .background(Color(.systemGroupedBackground))
    }
    
    // MARK: - Level Card
    
    private var levelCard: some View {
        VStack(spacing: 16) {
            // Current badge
            ZStack {
                Circle()
                    .fill(
                        LinearGradient(
                            colors: [brandColor, brandColor.opacity(0.6)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .frame(width: 100, height: 100)
                    .shadow(color: brandColor.opacity(0.4), radius: 10, x: 0, y: 5)
                
                Text(booster.stats.currentBadge.icon)
                    .font(.scaled(50))
                    .accessibilityHidden(true)
            }

            // Badge title
            Text(booster.stats.currentBadge.localizedTitle)
                .font(.title2)
                .fontWeight(.bold)
            
            // Progress to next
            if let nextBadge = booster.stats.currentBadge.next {
                VStack(spacing: 8) {
                    // Progress bar
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            RoundedRectangle(cornerRadius: 6)
                                .fill(Color(.systemGray5))
                                .frame(height: 12)
                            
                            RoundedRectangle(cornerRadius: 6)
                                .fill(
                                    LinearGradient(
                                        colors: [brandColor, brandColor.opacity(0.7)],
                                        startPoint: .leading,
                                        endPoint: .trailing
                                    )
                                )
                                .frame(width: geo.size.width * booster.stats.progressToNextBadge, height: 12)
                        }
                    }
                    .frame(height: 12)
                    .padding(.horizontal, 20)
                    
                    HStack {
                        Text(String(format: "mesh_booster.relays_to".localized, booster.stats.relaysToNextBadge))
                            .font(.caption)
                            .foregroundColor(.secondary)

                        Text(nextBadge.icon)
                            .accessibilityHidden(true)

                        Text(nextBadge.localizedTitle)
                            .font(.caption)
                            .fontWeight(.semibold)
                            .foregroundColor(brandColor)
                    }
                    .accessibilityElement(children: .combine)
                }
            } else {
                Text("mesh_booster.max_level".localized)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity)
        .background(
            RoundedRectangle(cornerRadius: 20)
                .fill(Color(.systemBackground))
                .shadow(color: .black.opacity(0.05), radius: 10, x: 0, y: 5)
        )
    }
    
    // MARK: - Stats Grid
    
    private var statsGrid: some View {
        VStack(spacing: 16) {
            // First row
            HStack(spacing: 16) {
                StatCard(
                    icon: "arrow.triangle.2.circlepath",
                    value: "\(booster.stats.totalRelays)",
                    label: "mesh_booster.total_relays".localized,
                    color: brandColor
                )

                StatCard(
                    icon: "person.2.fill",
                    value: "\(booster.stats.peersHelped.count)",
                    label: "mesh_booster.peers_helped".localized,
                    color: .green
                )

                StatCard(
                    icon: "flame.fill",
                    value: "\(booster.stats.consecutiveDays)",
                    label: "mesh_booster.day_streak".localized,
                    color: .orange
                )
            }
            
            // Second row
            HStack(spacing: 16) {
                StatCard(
                    icon: "sun.max.fill",
                    value: "\(booster.stats.relaysToday)",
                    label: "mesh_booster.today".localized,
                    color: .yellow
                )

                StatCard(
                    icon: "calendar",
                    value: "\(booster.stats.relaysThisWeek)",
                    label: "mesh_booster.this_week".localized,
                    color: .blue
                )

                StatCard(
                    icon: "trophy.fill",
                    value: "\(booster.stats.longestStreak)",
                    label: "mesh_booster.best_streak".localized,
                    color: .purple
                )
            }
        }
    }
    
    // MARK: - Progress Section
    
    private var progressSection: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("mesh_booster.your_journey".localized)
                .font(.headline)
            
            // Milestones
            ForEach(MeshBadge.allCases, id: \.self) { badge in
                MilestoneRow(
                    badge: badge,
                    totalRelays: booster.stats.totalRelays,
                    isEarned: booster.stats.earnedBadges.contains(badge)
                )
            }
        }
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(Color(.systemBackground))
        )
    }
    
    // MARK: - Badges Section
    
    private var badgesSection: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Text("mesh_booster.badges_collection".localized)
                    .font(.headline)
                
                Spacer()
                
                Text("\(booster.stats.earnedBadges.count)/\(MeshBadge.allCases.count)")
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Color(.systemGray5))
                    .cornerRadius(8)
            }
            
            // Category picker
            Picker("mesh_booster.category".localized, selection: $selectedCategory) {
                ForEach(BadgeCategory.allCases, id: \.self) { category in
                    Text(category.localizedString).tag(category)
                }
            }
            .pickerStyle(.segmented)
            
            // Badge grid
            let filteredBadges = getFilteredBadges()
            
            LazyVGrid(columns: [
                GridItem(.flexible()),
                GridItem(.flexible()),
                GridItem(.flexible())
            ], spacing: 16) {
                ForEach(filteredBadges, id: \.self) { badge in
                    BadgeCardView(
                        badge: badge,
                        isEarned: booster.stats.earnedBadges.contains(badge)
                    )
                }
            }
            
            if filteredBadges.isEmpty {
                Text("mesh_booster.no_badges".localized)
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .frame(maxWidth: .infinity)
                    .padding()
            }
        }
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(Color(.systemBackground))
        )
    }
    
    // MARK: - Helpers
    
    private func getFilteredBadges() -> [MeshBadge] {
        switch selectedCategory {
        case .all:
            return MeshBadge.allCases
        case .earned:
            return MeshBadge.allCases.filter { booster.stats.earnedBadges.contains($0) }
        case .locked:
            return MeshBadge.allCases.filter { !booster.stats.earnedBadges.contains($0) }
        }
    }
}

// MARK: - Supporting Views

struct StatCard: View {
    let icon: String
    let value: String
    let label: String
    let color: Color
    
    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundColor(color)
                .accessibilityHidden(true)

            Text(value)
                .font(.title2)
                .fontWeight(.bold)
                .minimumScaleFactor(0.6)
                .lineLimit(1)

            Text(label)
                .font(.caption)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(Color(.systemBackground))
        )
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(label): \(value)")
    }
}

struct MilestoneRow: View {
    let badge: MeshBadge
    let totalRelays: Int
    let isEarned: Bool
    
    private let brandColor = Theme.brand
    
    var progress: Double {
        min(Double(totalRelays) / Double(badge.threshold), 1.0)
    }
    
    var body: some View {
        HStack(spacing: 12) {
            // Badge icon
            ZStack {
                Circle()
                    .fill(isEarned ? brandColor.opacity(0.2) : Color(.systemGray5))
                    .frame(width: 40, height: 40)

                Text(badge.icon)
                    .font(.title3)
                    .grayscale(isEarned ? 0 : 1)
                    .opacity(isEarned ? 1 : 0.5)
            }
            .accessibilityHidden(true)

            // Info
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(badge.localizedTitle)
                        .font(.subheadline)
                        .fontWeight(isEarned ? .semibold : .regular)

                    if isEarned {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.caption)
                            .foregroundColor(.green)
                            .accessibilityHidden(true)
                    }
                }
                
                // Progress bar
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        RoundedRectangle(cornerRadius: 3)
                            .fill(Color(.systemGray5))
                            .frame(height: 6)
                        
                        RoundedRectangle(cornerRadius: 3)
                            .fill(isEarned ? .green : brandColor)
                            .frame(width: geo.size.width * progress, height: 6)
                    }
                }
                .frame(height: 6)
            }
            
            // Count
            Text("\(min(totalRelays, badge.threshold))/\(badge.threshold)")
                .font(.caption)
                .foregroundColor(.secondary)
                .monospacedDigit()
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isEarned ? .isSelected : [])
    }
}

struct BadgeCardView: View {
    let badge: MeshBadge
    let isEarned: Bool
    
    private let brandColor = Theme.brand
    
    var body: some View {
        VStack(spacing: 8) {
            ZStack {
                Circle()
                    .fill(isEarned ? brandColor.opacity(0.15) : Color(.systemGray5))
                    .frame(width: 60, height: 60)

                if isEarned {
                    Text(badge.icon)
                        .font(.largeTitle)
                } else {
                    Image(systemName: "lock.fill")
                        .font(.title3)
                        .foregroundColor(.gray)
                }
            }
            .accessibilityHidden(true)

            Text(badge.localizedTitle)
                .font(.caption)
                .fontWeight(.medium)
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .foregroundColor(isEarned ? .primary : .secondary)

            Text(String(format: "mesh_booster.badge_relays".localized, badge.threshold))
                .font(.caption2)
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
        .opacity(isEarned ? 1.0 : 0.6)
        .accessibilityElement(children: .combine)
        .accessibilityHint(isEarned ? "" : "mesh_booster.badge.locked_hint".localized)
        .accessibilityAddTraits(isEarned ? .isSelected : [])
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        MeshBoosterView()
    }
}
