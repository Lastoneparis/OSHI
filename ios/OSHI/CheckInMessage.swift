//
//  CheckInMessage.swift
//  OSHI
//
//  Accompagnement/Check-In Safety Feature
//  Similar to Apple's Check-In feature
//

import Foundation
import CoreLocation
import MapKit

// MARK: - Transport Mode

enum TransportMode: String, Codable, CaseIterable {
    case walking
    case driving
    case transit

    var icon: String {
        switch self {
        case .walking: return "figure.walk"
        case .driving: return "car.fill"
        case .transit: return "tram.fill"
        }
    }

    var emoji: String {
        switch self {
        case .walking: return "🚶"
        case .driving: return "🚗"
        case .transit: return "🚇"
        }
    }

    var localizedName: String {
        switch self {
        case .walking: return NSLocalizedString("checkin.walking", comment: "Walking")
        case .driving: return NSLocalizedString("checkin.driving", comment: "Driving")
        case .transit: return NSLocalizedString("checkin.transit", comment: "Transit")
        }
    }

    var mkTransportType: MKDirectionsTransportType {
        switch self {
        case .walking: return .walking
        case .driving: return .automobile
        case .transit: return .transit
        }
    }
}

// MARK: - Check-In Status

enum CheckInStatus: String, Codable {
    case active          // Check-in is ongoing
    case arrived         // User arrived safely
    case expired         // ETA passed, waiting for response
    case cancelled       // User cancelled check-in
    case emergency       // Emergency data was sent

    var localizedDescription: String {
        switch self {
        case .active: return NSLocalizedString("checkin.status.active", comment: "Active")
        case .arrived: return NSLocalizedString("checkin.status.arrived", comment: "Arrived")
        case .expired: return NSLocalizedString("checkin.status.expired", comment: "Expired")
        case .cancelled: return NSLocalizedString("checkin.status.cancelled", comment: "Cancelled")
        case .emergency: return NSLocalizedString("checkin.status.emergency", comment: "Emergency")
        }
    }
}

// MARK: - Check-In Message Type

enum CheckInMessageType: String, Codable {
    case started      // Initial check-in message
    case update       // Location update during trip
    case arrived      // Safely arrived
    case extended     // User extended time
    case cancelled    // User cancelled
    case warning      // Warning before emergency
    case emergency    // Timeout with emergency data
}

// MARK: - Emergency Data

struct EmergencyData: Codable {
    let lastKnownLatitude: Double
    let lastKnownLongitude: Double
    let lastLocationTime: Date
    let batteryLevel: Double          // 0.0-1.0
    let isCharging: Bool
    let networkType: String           // WiFi, Cellular, Offline
    let deviceName: String
    let lastMovementTime: Date?

    var lastKnownLocation: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: lastKnownLatitude, longitude: lastKnownLongitude)
    }

    var batteryPercentage: Int {
        Int(batteryLevel * 100)
    }

    var formattedBattery: String {
        let percent = batteryPercentage
        let charging = isCharging ? " ⚡" : ""
        return "\(percent)%\(charging)"
    }
}

// MARK: - Check-In Session

struct CheckInSession: Codable, Identifiable {
    let id: String
    let contactPublicKey: String
    let destinationLatitude: Double
    let destinationLongitude: Double
    let destinationAddress: String
    let transportMode: TransportMode
    let startTime: Date
    var estimatedArrivalTime: Date
    var extraTimeMinutes: Int
    var status: CheckInStatus
    var lastUpdateTime: Date
    var warningShownAt: Date?

    var destination: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: destinationLatitude, longitude: destinationLongitude)
    }

    var effectiveDeadline: Date {
        estimatedArrivalTime.addingTimeInterval(TimeInterval(extraTimeMinutes * 60))
    }

    var remainingTime: TimeInterval {
        effectiveDeadline.timeIntervalSince(Date())
    }

    var isOverdue: Bool {
        remainingTime < 0
    }

    var formattedETA: String {
        let formatter = DateFormatter()
        formatter.timeStyle = .short
        return formatter.string(from: estimatedArrivalTime)
    }

    var formattedRemainingTime: String {
        let remaining = max(0, remainingTime)
        let hours = Int(remaining) / 3600
        let minutes = (Int(remaining) % 3600) / 60

        if hours > 0 {
            return String(format: "%dh %dm", hours, minutes)
        } else {
            return String(format: "%d min", minutes)
        }
    }

    init(contactPublicKey: String,
         destination: CLLocationCoordinate2D,
         destinationAddress: String,
         transportMode: TransportMode,
         estimatedArrivalTime: Date,
         extraTimeMinutes: Int = 0) {
        self.id = UUID().uuidString
        self.contactPublicKey = contactPublicKey
        self.destinationLatitude = destination.latitude
        self.destinationLongitude = destination.longitude
        self.destinationAddress = destinationAddress
        self.transportMode = transportMode
        self.startTime = Date()
        self.estimatedArrivalTime = estimatedArrivalTime
        self.extraTimeMinutes = extraTimeMinutes
        self.status = .active
        self.lastUpdateTime = Date()
        self.warningShownAt = nil
    }

    mutating func extend(minutes: Int) {
        extraTimeMinutes += minutes
        lastUpdateTime = Date()
    }

    mutating func markArrived() {
        status = .arrived
        lastUpdateTime = Date()
    }

    mutating func markCancelled() {
        status = .cancelled
        lastUpdateTime = Date()
    }

    mutating func markEmergency() {
        status = .emergency
        lastUpdateTime = Date()
    }

    mutating func markWarningShown() {
        warningShownAt = Date()
        status = .expired
        lastUpdateTime = Date()
    }
}

// MARK: - Check-In Message

struct CheckInMessage: Codable {
    let type: CheckInMessageType
    let sessionId: String
    let destinationAddress: String?
    let transportMode: TransportMode?
    let estimatedArrival: Date?
    let currentLatitude: Double?
    let currentLongitude: Double?
    let emergencyData: EmergencyData?
    let timestamp: Date
    let senderName: String?

    static let messagePrefix = "🛡️CHECK_IN🛡️"

    var currentLocation: CLLocationCoordinate2D? {
        guard let lat = currentLatitude, let lon = currentLongitude else { return nil }
        return CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }

    // MARK: - Factory Methods

    static func started(session: CheckInSession, senderName: String? = nil) -> CheckInMessage {
        CheckInMessage(
            type: .started,
            sessionId: session.id,
            destinationAddress: session.destinationAddress,
            transportMode: session.transportMode,
            estimatedArrival: session.estimatedArrivalTime,
            currentLatitude: nil,
            currentLongitude: nil,
            emergencyData: nil,
            timestamp: Date(),
            senderName: senderName
        )
    }

    static func update(sessionId: String, location: CLLocationCoordinate2D) -> CheckInMessage {
        CheckInMessage(
            type: .update,
            sessionId: sessionId,
            destinationAddress: nil,
            transportMode: nil,
            estimatedArrival: nil,
            currentLatitude: location.latitude,
            currentLongitude: location.longitude,
            emergencyData: nil,
            timestamp: Date(),
            senderName: nil
        )
    }

    static func arrived(sessionId: String, destinationAddress: String, senderName: String? = nil) -> CheckInMessage {
        CheckInMessage(
            type: .arrived,
            sessionId: sessionId,
            destinationAddress: destinationAddress,
            transportMode: nil,
            estimatedArrival: nil,
            currentLatitude: nil,
            currentLongitude: nil,
            emergencyData: nil,
            timestamp: Date(),
            senderName: senderName
        )
    }

    static func extended(sessionId: String, newETA: Date) -> CheckInMessage {
        CheckInMessage(
            type: .extended,
            sessionId: sessionId,
            destinationAddress: nil,
            transportMode: nil,
            estimatedArrival: newETA,
            currentLatitude: nil,
            currentLongitude: nil,
            emergencyData: nil,
            timestamp: Date(),
            senderName: nil
        )
    }

    static func cancelled(sessionId: String) -> CheckInMessage {
        CheckInMessage(
            type: .cancelled,
            sessionId: sessionId,
            destinationAddress: nil,
            transportMode: nil,
            estimatedArrival: nil,
            currentLatitude: nil,
            currentLongitude: nil,
            emergencyData: nil,
            timestamp: Date(),
            senderName: nil
        )
    }

    // 🔧 FIX (2026-06-04): carry the last-known coord on `.warning` too, so
    // the receiver's mini-map can show where their friend was when the ETA
    // window expired. Default-nil parameter keeps every existing call site
    // source-compatible; the in-app caller in CheckInManager.checkDeadline
    // passes `currentLocation?.coordinate`.
    static func warning(sessionId: String, location: CLLocationCoordinate2D? = nil) -> CheckInMessage {
        CheckInMessage(
            type: .warning,
            sessionId: sessionId,
            destinationAddress: nil,
            transportMode: nil,
            estimatedArrival: nil,
            currentLatitude: location?.latitude,
            currentLongitude: location?.longitude,
            emergencyData: nil,
            timestamp: Date(),
            senderName: nil
        )
    }

    static func emergency(sessionId: String, emergencyData: EmergencyData, destinationAddress: String, senderName: String? = nil) -> CheckInMessage {
        CheckInMessage(
            type: .emergency,
            sessionId: sessionId,
            destinationAddress: destinationAddress,
            transportMode: nil,
            estimatedArrival: nil,
            currentLatitude: emergencyData.lastKnownLatitude,
            currentLongitude: emergencyData.lastKnownLongitude,
            emergencyData: emergencyData,
            timestamp: Date(),
            senderName: senderName
        )
    }

    // MARK: - Serialization

    func toMessageString() -> String {
        guard let data = try? JSONEncoder().encode(self),
              let json = String(data: data, encoding: .utf8) else {
            return ""
        }
        return "\(Self.messagePrefix)\(json)"
    }

    static func fromMessageString(_ string: String) -> CheckInMessage? {
        guard string.hasPrefix(messagePrefix) else { return nil }
        let jsonString = String(string.dropFirst(messagePrefix.count))
        guard let data = jsonString.data(using: .utf8),
              let message = try? JSONDecoder().decode(CheckInMessage.self, from: data) else {
            return nil
        }
        return message
    }

    static func isCheckInMessage(_ string: String) -> Bool {
        string.hasPrefix(messagePrefix)
    }

    // MARK: - Display Helpers

    var displayTitle: String {
        switch type {
        case .started:
            return NSLocalizedString("checkin.message.started", comment: "Check-In Started")
        case .update:
            return NSLocalizedString("checkin.message.update", comment: "Location Update")
        case .arrived:
            return NSLocalizedString("checkin.message.arrived", comment: "Arrived Safely")
        case .extended:
            return NSLocalizedString("checkin.message.extended", comment: "Time Extended")
        case .cancelled:
            return NSLocalizedString("checkin.message.cancelled", comment: "Check-In Cancelled")
        case .warning:
            return NSLocalizedString("checkin.message.warning", comment: "Check-In Warning")
        case .emergency:
            return NSLocalizedString("checkin.message.emergency", comment: "Emergency Alert")
        }
    }

    var displayIcon: String {
        switch type {
        case .started: return "shield.checkered"
        case .update: return "location.fill"
        case .arrived: return "checkmark.shield.fill"
        case .extended: return "clock.arrow.circlepath"
        case .cancelled: return "xmark.shield"
        case .warning: return "exclamationmark.triangle.fill"
        case .emergency: return "sos"
        }
    }

    var displayColor: String {
        switch type {
        case .started: return "blue"
        case .update: return "gray"
        case .arrived: return "green"
        case .extended: return "orange"
        case .cancelled: return "gray"
        case .warning: return "yellow"
        case .emergency: return "red"
        }
    }
}
