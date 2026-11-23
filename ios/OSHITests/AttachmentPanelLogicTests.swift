import XCTest
import Photos
@testable import OSHI

/// __ATTACH_PANEL_2027_2026_11_17__ The rules of the attachment panel, pinned
/// the same way Android pins `AttachmentPanelLogic.kt`.
final class AttachmentPanelLogicTests: XCTestCase {

    // MARK: Selection & numbering

    func testToggleAddsInOrderAndNumbersFromOne() {
        var s: [String] = []
        for id in ["a", "b", "c"] {
            let r = AttachmentPanelLogic.toggle(s, id)
            XCTAssertEqual(r.outcome, .added)
            s = r.selection
        }
        XCTAssertEqual(s, ["a", "b", "c"])
        XCTAssertEqual(AttachmentPanelLogic.badgeNumber(s, "a"), 1)
        XCTAssertEqual(AttachmentPanelLogic.badgeNumber(s, "c"), 3)
        XCTAssertNil(AttachmentPanelLogic.badgeNumber(s, "z"))
    }

    func testDeselectRenumbersTheRest() {
        let r = AttachmentPanelLogic.toggle(["a", "b", "c", "d"], "b")
        XCTAssertEqual(r.outcome, .removed)
        XCTAssertEqual(r.selection, ["a", "c", "d"])
        XCTAssertEqual(AttachmentPanelLogic.badgeNumber(r.selection, "c"), 2)
        XCTAssertEqual(AttachmentPanelLogic.badgeNumber(r.selection, "d"), 3)
    }

    func testCapRefusesTheEleventhButStillAllowsDeselect() {
        let ten = (1...10).map { "id\($0)" }
        XCTAssertEqual(AttachmentPanelLogic.maxSelection, 10)
        let over = AttachmentPanelLogic.toggle(ten, "id11")
        XCTAssertEqual(over.outcome, .limit)
        XCTAssertEqual(over.selection, ten, "a refused pick must leave the selection untouched")
        let off = AttachmentPanelLogic.toggle(ten, "id5")
        XCTAssertEqual(off.outcome, .removed)
        XCTAssertEqual(off.selection.count, 9)
        XCTAssertEqual(AttachmentPanelLogic.toggle(off.selection, "id11").outcome, .added)
    }

    func testReselectGoesToTheEnd() {
        var s = AttachmentPanelLogic.toggle(["a", "b"], "a").selection
        s = AttachmentPanelLogic.toggle(s, "a").selection
        XCTAssertEqual(s, ["b", "a"])
    }

    func testPruneDropsItemsGoneFromTheLibrary() {
        XCTAssertEqual(AttachmentPanelLogic.prune(["a", "b", "c"], present: ["a", "c", "x"]), ["a", "c"])
        XCTAssertEqual(AttachmentPanelLogic.prune([], present: ["a"]), [])
    }

    // MARK: Permission state

    func testAccessFromPhotoKitRawStatus() {
        XCTAssertEqual(GalleryAccess(photoKitRawStatus: 0), .notDetermined)
        XCTAssertEqual(GalleryAccess(photoKitRawStatus: 1), .denied)   // restricted
        XCTAssertEqual(GalleryAccess(photoKitRawStatus: 2), .denied)
        XCTAssertEqual(GalleryAccess(photoKitRawStatus: 3), .full)
        XCTAssertEqual(GalleryAccess(photoKitRawStatus: 4), .limited)
        XCTAssertEqual(GalleryAccess(photoKitRawStatus: 99), .notDetermined)
    }

    func testRawValuesMatchPhotoKit() {
        // The raw mapping above is only right while PhotoKit keeps these values.
        XCTAssertEqual(GalleryAccess(photoKitRawStatus: PHAuthorizationStatusRaw.notDetermined), .notDetermined)
        XCTAssertEqual(GalleryAccess(photoKitRawStatus: PHAuthorizationStatusRaw.restricted), .denied)
        XCTAssertEqual(GalleryAccess(photoKitRawStatus: PHAuthorizationStatusRaw.denied), .denied)
        XCTAssertEqual(GalleryAccess(photoKitRawStatus: PHAuthorizationStatusRaw.authorized), .full)
        XCTAssertEqual(GalleryAccess(photoKitRawStatus: PHAuthorizationStatusRaw.limited), .limited)
    }

    func testPermissionCardPerAccess() {
        XCTAssertEqual(GalleryPermissionCard(.full), .none)
        XCTAssertEqual(GalleryPermissionCard(.limited), .limitedBanner)
        XCTAssertEqual(GalleryPermissionCard(.notDetermined), .askAccess)
        XCTAssertEqual(GalleryPermissionCard(.denied), .openSettings,
                       "after a refusal the button must open Settings, never ask into the void")
        XCTAssertTrue(GalleryAccess.full.canRead)
        XCTAssertTrue(GalleryAccess.limited.canRead)
        XCTAssertFalse(GalleryAccess.notDetermined.canRead)
        XCTAssertFalse(GalleryAccess.denied.canRead)
    }

    // MARK: Chips, paging, captions

    func testDurationChip() {
        XCTAssertEqual(AttachmentPanelLogic.formatDuration(7.2), "0:07")
        XCTAssertEqual(AttachmentPanelLogic.formatDuration(225), "3:45")
        XCTAssertEqual(AttachmentPanelLogic.formatDuration(3723), "1:02:03")
        XCTAssertEqual(AttachmentPanelLogic.formatDuration(-3), "0:00")
        XCTAssertEqual(AttachmentPanelLogic.formatDuration(.nan), "0:00")
    }

    func testPagingGrowsNearTheEndOnly() {
        let p = AttachmentPanelLogic.pageSize
        XCTAssertFalse(AttachmentPanelLogic.shouldLoadMore(appearedIndex: 0, loaded: p, total: 1000))
        XCTAssertTrue(AttachmentPanelLogic.shouldLoadMore(appearedIndex: p - 5, loaded: p, total: 1000))
        XCTAssertFalse(AttachmentPanelLogic.shouldLoadMore(appearedIndex: p - 1, loaded: p, total: p),
                       "nothing left to load")
        XCTAssertEqual(AttachmentPanelLogic.nextWindow(loaded: p, total: 1000), 2 * p)
        XCTAssertEqual(AttachmentPanelLogic.nextWindow(loaded: p, total: p + 3), p + 3)
    }

    func testCaptionRidesTheFirstItemOnly() {
        XCTAssertEqual(AttachmentPanelLogic.captions(count: 3, typed: "  hello  "), ["hello", "", ""])
        XCTAssertEqual(AttachmentPanelLogic.captions(count: 2, typed: "   "), ["", ""])
        XCTAssertEqual(AttachmentPanelLogic.captions(count: 0, typed: "x"), [])
    }

    // MARK: Options per chat

    func testGroupHasEveryDirectOptionPlusScheduleCall() {
        let direct = AttachmentPanelLogic.options(for: .direct(cashu: true))
        let group = AttachmentPanelLogic.options(for: .group)
        XCTAssertEqual(Set(group).subtracting(direct), [.scheduleCall])
        // "Disguise as photo" encrypts for ONE contact: 1:1 only.
        XCTAssertEqual(Set(direct).subtracting(group), [.disguisePhoto])
        XCTAssertFalse(group.contains(.disguisePhoto))
        XCTAssertEqual(group.first, .gallery)
        XCTAssertEqual(direct.prefix(4), [.gallery, .camera, .file, .gif])
        XCTAssertTrue(group.contains(.gif) && group.contains(.contact))
    }

    func testDirectCashuOnlyWhenOffered() {
        XCTAssertFalse(AttachmentPanelLogic.options(for: .direct(cashu: false)).contains(.cashu))
        XCTAssertTrue(AttachmentPanelLogic.options(for: .direct(cashu: true)).contains(.cashu))
    }

    func testSendOptionsAreDirectOnlyAndRevealIsOnePhoto() {
        XCTAssertTrue(AttachmentPanelLogic.supportsSendOptions(.direct(cashu: false)))
        XCTAssertFalse(AttachmentPanelLogic.supportsSendOptions(.group))
        XCTAssertTrue(AttachmentPanelLogic.revealAvailable(selectedCount: 1, firstIsPhoto: true))
        XCTAssertFalse(AttachmentPanelLogic.revealAvailable(selectedCount: 2, firstIsPhoto: true))
        XCTAssertFalse(AttachmentPanelLogic.revealAvailable(selectedCount: 1, firstIsPhoto: false))
    }

    func testViewOnceAndRevealStayExclusive() {
        var o = MediaSendOptions.none
        o.setViewOnce(true)
        o.toggleReveal(60)
        XCTAssertFalse(o.isViewOnce)
        XCTAssertEqual(o.revealDuration, 60)
        o.setViewOnce(true)
        XCTAssertNil(o.revealDuration)
    }
}

/// PhotoKit's raw values, read from the SDK so a renumbering would fail here.
private enum PHAuthorizationStatusRaw {
    static let notDetermined = PHAuthorizationStatus.notDetermined.rawValue
    static let restricted = PHAuthorizationStatus.restricted.rawValue
    static let denied = PHAuthorizationStatus.denied.rawValue
    static let authorized = PHAuthorizationStatus.authorized.rawValue
    static let limited = PHAuthorizationStatus.limited.rawValue
}
