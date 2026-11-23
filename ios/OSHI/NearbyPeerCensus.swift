//
//  NearbyPeerCensus.swift
//  OSHI — how many distinct phones are near this one, counted once.
//
//  __NEARBY_LISTED_EVERY_PHONE_TWICE_2026_11_02__ Reported from the field, in
//  one sentence that is really two bugs: "some devices in Nearby appear twice,
//  and the little badge on the first page no longer shows the number of nearby
//  devices."
//
//  Both come from the same place. OSHI discovers the room over TWO transports
//  at once, on purpose:
//
//    * `MeshNetworkManager`  — MultipeerConnectivity, iPhone to iPhone.
//      Peers are `MCPeerID`s whose `displayName` is `UIDevice.current.name`
//      plus four hex digits: "iPhone de Lou-9F3A".
//    * `CrossPlatformMesh`   — BLE + Bonjour, the lane Android speaks.
//      Peers are `CrossPlatformPeer`s whose `displayName` is the OSHI profile
//      name: "Lou". They also carry the peer's public key.
//
//  A nearby iPhone running OSHI is normally found by both. Nothing reconciled
//  the two lists, so:
//
//    * the Nearby screen drew each list in its own section and showed that one
//      phone twice — and its "connected" header counted it twice;
//    * the home-screen badge added the two `connectedPeers.count`s, so it also
//      double-counted, AND it counted nothing at all for a phone that was
//      discovered but not yet in a session, which is most of them most of the
//      time. With no connected peer the digits are hidden entirely, which is
//      the "no longer shows the number" half of the report.
//
//  Matching the two lists on name cannot work: the two names are built from
//  different things and will never be equal. The only identifier both sides
//  hold is the OSHI public key — `CrossPlatformPeer.publicKey` directly, and
//  `MeshNetworkManager.publicKey(for:)` for an MPC peer once its key exchange
//  has named it.
//
//  This file is the single place that knows the rule, so the badge and the
//  list cannot drift apart: two independent counters that are supposed to
//  agree are a bug waiting to be reported as "the number is wrong".
//

import Foundation
import MultipeerConnectivity

enum NearbyPeerCensus {

    /// The identity two sightings of one phone must agree on before they are
    /// merged: its public key, or — only when it has not advertised one yet —
    /// its display name.
    ///
    /// Prefixed (`k:` / `n:`) so a key can never collide with a name.
    static func identity(publicKey: String?, displayName: String) -> String {
        let key = (publicKey ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if !key.isEmpty { return "k:" + key }
        return "n:" + displayName.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    static func identity(of peer: CrossPlatformPeer) -> String {
        identity(publicKey: peer.publicKey, displayName: peer.displayName)
    }

    /// Every distinct phone currently in range, counted once across both
    /// transports and both states.
    ///
    /// DISCOVERED COUNTS, not just connected. A peer that has been found but is
    /// not yet in a session is a device "à proximité" in every sense the user
    /// means: it is listed on the Nearby screen, and a message can reach it as
    /// soon as the session comes up. Counting only established sessions is what
    /// left the badge blank in a room full of phones.
    ///
    /// An MPC peer whose public key is not yet known is counted as its own
    /// device rather than merged: it is not PROVEN to be a duplicate, and
    /// briefly over-counting a phone is a smaller lie than hiding one.
    static func distinctNearbyIdentities(
        mcConnected: [MCPeerID],
        mcDiscovered: [MCPeerID],
        crossPlatformConnected: [CrossPlatformPeer],
        crossPlatformDiscovered: [CrossPlatformPeer],
        publicKeyForPeerID: (MCPeerID) -> String?
    ) -> Set<String> {
        var ids = Set<String>()
        for peer in crossPlatformConnected { ids.insert(identity(of: peer)) }
        for peer in crossPlatformDiscovered { ids.insert(identity(of: peer)) }
        for peer in mcConnected + mcDiscovered {
            ids.insert(identity(publicKey: publicKeyForPeerID(peer),
                                displayName: peer.displayName))
        }
        return ids
    }

    /// The number the badge shows.
    static func nearbyCount(
        mesh: MeshNetworkManager,
        crossPlatform: CrossPlatformMesh
    ) -> Int {
        distinctNearbyIdentities(
            mcConnected: mesh.connectedPeers,
            mcDiscovered: mesh.discoveredPeers,
            crossPlatformConnected: crossPlatform.connectedPeers,
            crossPlatformDiscovered: crossPlatform.crossPlatformPeers,
            publicKeyForPeerID: { mesh.publicKey(for: $0) }
        ).count
    }
}

extension CrossPlatformPeer {
    /// The stable identity of the phone behind this sighting — the key `ForEach`
    /// lists by, and the key the census merges on, so a row's identity and its
    /// deduplication rule cannot drift apart.
    ///
    /// NOT `publicKey` on its own, which `ForEach` used before: a peer that has
    /// not advertised a key yet carries `""`, and two of those are the same id as
    /// far as SwiftUI is concerned, which makes it reuse one row's state for the
    /// other.
    var meshIdentity: String { NearbyPeerCensus.identity(of: self) }
}
