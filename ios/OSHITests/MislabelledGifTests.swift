//
//  MislabelledGifTests.swift
//  OSHITests
//
//  Un GIF envoyé par un pair Android SUR LA MAILLE LOCALE arrive étiqueté
//  `image`: son chemin d'envoi LAN lit son énumération au lieu de renifler les
//  octets, là où son chemin internet, lui, renifle. Rendu par l'étiquette, ce
//  GIF s'affiche figé sur sa première image et s'exporte en `.jpg`.
//
//  L'étiquette est corrigée côté Android, mais ce test garde le côté RÉCEPTION,
//  et c'est le côté qui compte: les versions déjà installées sur les téléphones
//  ne seront jamais recorrigées, et n'importe quel autre client peut refaire la
//  même erreur. Les octets, eux, ne mentent pas — l'en-tête `GIF8` survit à
//  tout, y compris à un pair que cette version n'a jamais rencontré.
//

import XCTest
@testable import OSHI

final class MislabelledGifTests: XCTestCase {

    /// Le plus petit GIF valide qui soit: en-tête GIF89a et un pixel.
    private var gifBytes: Data {
        Data([0x47, 0x49, 0x46, 0x38, 0x39, 0x61,   // "GIF89a"
              0x01, 0x00, 0x01, 0x00, 0x80, 0x00, 0x00,
              0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF,
              0x21, 0xF9, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00,
              0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
              0x02, 0x02, 0x44, 0x01, 0x00, 0x3B])
    }

    private var jpegBytes: Data {
        Data([0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00])
    }

    private func attachment(type: MediaManager.MediaType,
                            data: Data,
                            name: String) -> MediaManager.MediaAttachment {
        MediaManager.MediaAttachment(id: UUID().uuidString, type: type, data: data,
                                     thumbnailData: nil, fileName: name,
                                     fileSize: data.count, timestamp: Date(),
                                     originalSize: nil, compressionRatio: nil,
                                     isCompressed: false)
    }

    /// LE cas signalé: étiqueté `image`, mais ce sont des octets de GIF.
    func testGifBytesLabelledAsImageStillRenderAsGif() {
        let a = attachment(type: .image, data: gifBytes, name: "photo.jpg")
        XCTAssertTrue(a.isAnimatedGIF)
        XCTAssertEqual(a.renderType, .gif,
                       "un GIF étiqueté image s'afficherait figé sur sa première image")
    }

    /// Et l'inverse ne doit PAS arriver: une vraie image reste une image.
    /// Sans ça, on aurait remplacé une bulle figée par un rendu animé sur des
    /// octets qui n'animent pas.
    func testARealImageIsLeftAlone() {
        let a = attachment(type: .image, data: jpegBytes, name: "photo.jpg")
        XCTAssertFalse(a.isAnimatedGIF)
        XCTAssertEqual(a.renderType, .image)
    }

    /// Une étiquette déjà correcte n'est pas touchée.
    func testAnHonestGifKeepsItsType() {
        XCTAssertEqual(attachment(type: .gif, data: gifBytes, name: "x.gif").renderType, .gif)
    }

    /// Le redressement vise UNIQUEMENT `image`. Une vidéo, un son ou un document
    /// ne doivent jamais basculer, même si leurs octets commençaient par « GIF8 »
    /// — ce serait alors un fichier mal formé, pas un GIF à animer.
    func testOnlyTheImageLabelIsSecondGuessed() {
        for type in [MediaManager.MediaType.video, .audio, .document] {
            XCTAssertEqual(attachment(type: type, data: gifBytes, name: "x").renderType, type,
                           "\(type.rawValue) ne doit pas être requalifié en gif")
        }
    }

    /// GIF87a comme GIF89a: les deux en-têtes circulent encore.
    func testBothGifHeadersAreRecognised() {
        var old = gifBytes
        old.replaceSubrange(0..<6, with: Data("GIF87a".utf8))
        XCTAssertEqual(attachment(type: .image, data: old, name: "x").renderType, .gif)
    }

    /// Des octets trop courts pour porter un en-tête ne doivent pas faire
    /// déborder la lecture.
    func testTruncatedBytesAreSafe() {
        for n in 0...6 {
            let a = attachment(type: .image, data: Data(gifBytes.prefix(n)), name: "x")
            XCTAssertEqual(a.renderType, n >= 6 ? .gif : .image)
        }
    }
}
