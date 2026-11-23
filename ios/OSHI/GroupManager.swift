//
//  GroupManager.swift
//  OSHI - Group management with FileStorage integration
//  ✅ UPDATED FOR NEW FileStorage API
//

import Foundation
import Combine

@MainActor
class GroupManager: ObservableObject {
    @Published var groups: [Group] = []
    @Published var currentGroup: Group?
    
    // Use explicit initialization to avoid ambiguity
    static let shared: GroupManager = {
        let instance = GroupManager()
        return instance
    }()
    
    // Private init to enforce singleton pattern
    private init() {
        loadGroups()
    }
    
    // MARK: - Load/Save
    
    func loadGroups() {
        // ✅ Use the correct FileStorage API
        if let loaded: [Group] = FileStorage.shared.load(forKey: "groups") {
            self.groups = loaded
            OshiLog.messaging.info("📂 Loaded \(loaded.count) groups")
        } else {
            // No groups yet, start with empty array
            self.groups = []
            OshiLog.messaging.info("📂 No existing groups found, starting fresh")
        }
    }
    
    func saveGroups() {
        // ✅ Use FileStorage.shared.save
        FileStorage.shared.save(groups, forKey: "groups")
        OshiLog.messaging.info("💾 Saved \(groups.count) groups")
    }
    
    // MARK: - Group Operations
    
    func createGroup(name: String, members: [String]) -> Group {
        let group = Group(
            id: UUID().uuidString,
            name: name,
            members: members,
            createdAt: Date()
        )
        groups.append(group)
        saveGroups()
        OshiLog.messaging.info("✅ Created group: \(name)")
        return group
    }
    
    func updateGroup(_ group: Group) {
        if let index = groups.firstIndex(where: { $0.id == group.id }) {
            groups[index] = group
            saveGroups()
            OshiLog.messaging.info("✅ Updated group: \(group.name)")
        }
    }
    
    func deleteGroup(_ group: Group) {
        groups.removeAll { $0.id == group.id }
        if currentGroup?.id == group.id {
            currentGroup = nil
        }
        saveGroups()
        OshiLog.messaging.info("🗑️ Deleted group: \(group.name)")
    }
    
    func getGroup(byId id: String) -> Group? {
        return groups.first { $0.id == id }
    }
    
    func updateLastMessage(for groupId: String) {
        if let index = groups.firstIndex(where: { $0.id == groupId }) {
            groups[index].lastMessageAt = Date()
            saveGroups()
        }
    }
    
    func getTotalUnreadCount() -> Int {
        // Implement unread count logic if needed
        return 0
    }
}

// MARK: - Group Model

struct Group: Codable, Identifiable, Hashable {
    let id: String
    var name: String
    var members: [String]
    var createdAt: Date
    var lastMessageAt: Date?
    
    init(id: String, name: String, members: [String], createdAt: Date = Date(), lastMessageAt: Date? = nil) {
        self.id = id
        self.name = name
        self.members = members
        self.createdAt = createdAt
        self.lastMessageAt = lastMessageAt
    }
    
    func hash(into hasher: inout Hasher) {
        hasher.combine(id)
    }
    
    static func == (lhs: Group, rhs: Group) -> Bool {
        lhs.id == rhs.id
    }
}
