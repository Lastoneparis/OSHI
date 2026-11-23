//
//  PeerVersionCompatTests.swift
//  OSHI — la comparaison de versions, et la compatibilite de fil de la charge
//  de profil.
//
//  DEUX CHOSES SE JOUENT ICI, ET LES DEUX ONT DEJA COUTE.
//
//  1. "1.0.9" est PLUS RECENT que "1.0.43" en comparaison de chaines, parce que
//     '9' > '4'. Ce projet a deja livre exactement ce bug. Chaque assertion
//     ci-dessous existe pour qu'il ne revienne pas.
//
//  2. Ajouter un champ a une charge que 521 appareils sur 535 lisent avec un
//     decodeur plus ancien. Le test qui compte n'est pas « la nouvelle build
//     relit sa propre charge » — c'est « la build 1.0.43 relit la charge de la
//     1.0.44 ». `LegacyProfileUpdatePayload` rejoue ce decodeur-la.
//

import XCTest
@testable import OSHI

final class PeerVersionCompatTests: XCTestCase {

    // MARK: - 1. Comparaison NUMERIQUE

    /// LE cas. En chaines, "1.0.9" > "1.0.43". En entiers, l'inverse.
    func testNineIsOlderThanFortyThree() {
        XCTAssertEqual(AppVersion.compare("1.0.9", "1.0.43"), .orderedAscending,
                       "1.0.9 doit etre ANTERIEURE a 1.0.43 — la comparaison de chaines dit le contraire")
        XCTAssertEqual(AppVersion.compare("1.0.43", "1.0.9"), .orderedDescending)
        XCTAssertEqual(AppVersion.isOlder("1.0.9", than: "1.0.43"), true)
        XCTAssertEqual(AppVersion.isOlder("1.0.43", than: "1.0.9"), false)
    }

    func testEqualVersions() {
        XCTAssertEqual(AppVersion.compare("1.0.43", "1.0.43"), .orderedSame)
        XCTAssertEqual(AppVersion.isOlder("1.0.43", than: "1.0.43"), false,
                       "la version exigee elle-meme ne doit pas etre signalee comme en retard")
        // Longueurs differentes: les composants manquants valent zero.
        XCTAssertEqual(AppVersion.compare("1.0", "1.0.0"), .orderedSame)
        XCTAssertEqual(AppVersion.compare("1", "1.0.0"), .orderedSame)
        XCTAssertEqual(AppVersion.compare("1.0", "1.0.1"), .orderedAscending)
    }

    func testOrderingAcrossComponents() {
        XCTAssertEqual(AppVersion.compare("0.9.99", "1.0.0"), .orderedAscending)
        XCTAssertEqual(AppVersion.compare("2.0.0", "1.99.99"), .orderedDescending)
        XCTAssertEqual(AppVersion.compare("1.10.0", "1.9.0"), .orderedDescending,
                       "10 > 9 en entiers; en chaines '1' < '9'")
        XCTAssertEqual(AppVersion.components("1.0.43"), [1, 0, 43])
        XCTAssertEqual(AppVersion.components("007.1"), [7, 1])
    }

    /// Une chaine illisible doit rendre « je ne sais pas », JAMAIS « ancienne ».
    /// Une banniere qui accuse quelqu'un a jour d'etre en retard coute plus cher
    /// qu'un silence.
    func testMalformedVersionsAreUnknownNotOld() {
        for bad in ["", "   ", "abc", "1.0.43-beta", "v1.0.43", "1..3", "1.0.", ".1.0",
                    "1,0,43", "1.0.-3", "99999999999999999999999"] {
            XCTAssertNil(AppVersion.components(bad), "\(bad) devrait etre illisible")
            XCTAssertNil(AppVersion.compare(bad, "1.0.44"), "\(bad) devrait etre illisible")
            XCTAssertNil(AppVersion.compare("1.0.44", bad), "\(bad) devrait etre illisible")
            XCTAssertNil(AppVersion.isOlder(bad, than: "1.0.44"))
        }
    }

    // MARK: - Le seuil d'interoperabilite du ratchet

    func testPeerIsOutdatedOnlyWhenWeActuallyKnow() {
        // Ce que la banniere doit attraper: le parc iOS reste en 1.0.43 et avant.
        XCTAssertTrue(RatchetInterop.peerIsOutdated("1.0.43", platform: "ios"))
        XCTAssertTrue(RatchetInterop.peerIsOutdated("1.0.9", platform: "ios"),
                      "1.0.9 est ANTERIEURE a 1.0.44, malgre le '9'")
        XCTAssertTrue(RatchetInterop.peerIsOutdated("0.9.0", platform: "ios"))

        // Ce qu'elle ne doit jamais attraper.
        XCTAssertFalse(RatchetInterop.peerIsOutdated("1.0.44", platform: "ios"),
                       "la version seuil elle-meme est bonne")
        XCTAssertFalse(RatchetInterop.peerIsOutdated("1.0.45", platform: "ios"))
        XCTAssertFalse(RatchetInterop.peerIsOutdated("2.0.0", platform: "ios"))
        XCTAssertFalse(RatchetInterop.peerIsOutdated(nil, platform: "ios"),
                       "un pair muet n'est pas un pair en retard")
        XCTAssertFalse(RatchetInterop.peerIsOutdated("who knows", platform: "ios"),
                       "illisible n'est pas ancien")
        XCTAssertFalse(RatchetInterop.peerIsOutdated("", platform: "ios"))
    }

    // MARK: - Le seuil est un numero iOS, et il ne juge que des iOS

    /// LE defaut que ce champ repare, vu des deux cotes de la barriere.
    ///
    /// Android numerote 1.6.x. Sans le tag, un "1.6.13" — reellement anterieur
    /// au changement de calendrier cote Android — passait pour tres superieur a
    /// 1.0.44 et n'etait jamais signale; et le symetrique cote Kotlin etiquetait
    /// CHAQUE iPhone « en retard » pour toujours. Avec le tag, chaque numero est
    /// compare a l'echelle qui est la sienne, et les deux cas deviennent justes.
    func testAndroidPeersAreJudgedAgainstTheAndroidLadder() {
        // Anterieurs au changement de calendrier COTE ANDROID: a signaler.
        XCTAssertTrue(RatchetInterop.peerIsOutdated("1.6.13", platform: "android"))
        XCTAssertTrue(RatchetInterop.peerIsOutdated("1.6.9", platform: "android"),
                      "1.6.9 est ANTERIEURE a 1.6.14, malgre le '9'")
        XCTAssertTrue(RatchetInterop.peerIsOutdated("1.5.99", platform: "android"))

        // A partir du plancher Android: rien a dire.
        XCTAssertFalse(RatchetInterop.peerIsOutdated("1.6.14", platform: "android"),
                       "1.6.14 est la premiere build Android au nouveau calendrier")
        XCTAssertFalse(RatchetInterop.peerIsOutdated("1.6.15", platform: "android"))
        XCTAssertFalse(RatchetInterop.peerIsOutdated("2.0.0", platform: "android"))

        // Et surtout: un numero iOS ne doit JAMAIS etre mesure a l'echelle
        // Android, ni l'inverse. C'est tout l'objet du tag.
        XCTAssertFalse(RatchetInterop.peerIsOutdated("1.0.44", platform: "ios"))
        XCTAssertTrue(RatchetInterop.peerIsOutdated("1.0.44", platform: "android"),
                      "1.0.44 lu comme un numero Android est bien anterieur a 1.6.14")
    }

    /// Les deux planchers designent LA MEME rupture de fil, et chacun doit etre
    /// la premiere version de sa plateforme a la porter.
    func testTheTwoFloorsNameTheSameWireChange() {
        XCTAssertEqual(RatchetInterop.minimumVersionByPlatform["ios"], "1.0.44")
        XCTAssertEqual(RatchetInterop.minimumVersionByPlatform["android"], "1.6.14",
                       "doit rester egal a AppVersions.MIN_KEY_SCHEDULE_VERSION cote Kotlin")
        XCTAssertEqual(RatchetInterop.minimumVersionByPlatform.count, 2,
                       "une plateforme ajoutee ici doit l'etre aussi cote Kotlin")
    }

    /// Un pair qui ne dit pas sa plateforme n'est pas juge du tout. Cela couvre
    /// toutes les builds iOS <= 1.0.43 et Android 1.6.14, qui a livre
    /// `appVersion` sans `appPlatform`. Une release de silence, dans le bon sens.
    func testAPeerWithoutAPlatformTagIsNotJudged() {
        XCTAssertFalse(RatchetInterop.peerIsOutdated("1.0.43", platform: nil),
                       "sans echelle, un numero ne veut rien dire")
        XCTAssertFalse(RatchetInterop.peerIsOutdated("1.0.43", platform: ""))
        XCTAssertFalse(RatchetInterop.peerIsOutdated("1.0.43", platform: "   "))
        XCTAssertFalse(RatchetInterop.peerIsOutdated("1.0.43", platform: "windows"),
                       "une plateforme absente de la table n'est pas jugee")
    }

    /// La casse ne doit pas decider si quelqu'un voit une banniere.
    func testThePlatformTagIsComparedCaseInsensitively() {
        for tag in ["ios", "iOS", "IOS", "Ios"] {
            XCTAssertTrue(RatchetInterop.peerIsOutdated("1.0.43", platform: tag),
                          "\(tag) designe la meme plateforme")
        }
    }

    /// La constante emise et la constante attendue sont la MEME. Si elles
    /// divergeaient, cette build ne se reconnaitrait pas elle-meme.
    func testWeSendTheTagWeOurselvesRequire() {
        XCTAssertEqual(RatchetInterop.platform, "ios")
        let payload = ContactPresenceManager.shared.createProfileUpdatePayload()
        XCTAssertEqual(payload.appPlatform, RatchetInterop.platform,
                       "la charge emise doit porter le tag que `peerIsOutdated` exige")
    }

    // MARK: - Le magasin de versions des pairs

    func testStoreRecordsAndAnswers() {
        let defaults = Self.freshDefaults(#function)
        let store = PeerVersionStore(defaults: defaults)

        XCTAssertNil(store.version(for: "peerA"))
        XCTAssertFalse(store.peerIsOutdated("peerA"), "sans rien savoir, on ne signale rien")

        store.record("1.0.43", platform: "ios", for: "peerA")
        XCTAssertEqual(store.version(for: "peerA"), "1.0.43")
        XCTAssertEqual(store.platform(for: "peerA"), "ios")
        XCTAssertTrue(store.peerIsOutdated("peerA"))

        store.record("1.0.44", platform: "ios", for: "peerA")
        XCTAssertFalse(store.peerIsOutdated("peerA"), "le pair s'est mis a jour")
    }

    /// Le magasin repond avec le COUPLE, pas avec la version seule: un pair
    /// Android en 1.6.13 est enregistre et n'est jamais signale.
    func testStoreJudgesWithThePlatformItStored() {
        let defaults = Self.freshDefaults(#function)
        let store = PeerVersionStore(defaults: defaults)

        // Anterieur au plancher ANDROID: signale, sur son echelle a lui.
        store.record("1.6.13", platform: "android", for: "droid")
        XCTAssertEqual(store.version(for: "droid"), "1.6.13")
        XCTAssertEqual(store.platform(for: "droid"), "android")
        XCTAssertTrue(store.peerIsOutdated("droid"),
                      "1.6.13 est anterieure au calendrier, cote Android")

        // Et au-dessus de ce plancher, rien — alors que 1.6.15 serait « ancienne »
        // si on la comparait betement au seuil iOS 1.0.44... dans l'autre sens.
        store.record("1.6.15", platform: "android", for: "droid2")
        XCTAssertFalse(store.peerIsOutdated("droid2"))

        // Et un pair qui n'a annonce qu'une version reste non juge.
        store.record("1.0.43", platform: nil, for: "silent")
        XCTAssertEqual(store.version(for: "silent"), "1.0.43")
        XCTAssertNil(store.platform(for: "silent"))
        XCTAssertFalse(store.peerIsOutdated("silent"))
    }

    /// Version et plateforme sont ecrites ENSEMBLE. Le cas qui compte: un pair
    /// deja connu envoie une charge sans tag — la plateforme deja lue ne doit
    /// pas disparaitre, sinon la banniere s'eteindrait a la premiere charge
    /// tronquee.
    func testPlatformIsNotErasedByALaterSilence() {
        let defaults = Self.freshDefaults(#function)
        let store = PeerVersionStore(defaults: defaults)

        store.record("1.0.42", platform: "ios", for: "peerP")
        store.record("1.0.43", platform: nil, for: "peerP")
        XCTAssertEqual(store.version(for: "peerP"), "1.0.43", "la version suit")
        XCTAssertEqual(store.platform(for: "peerP"), "ios", "la plateforme ne s'oublie pas")
        XCTAssertTrue(store.peerIsOutdated("peerP"))
    }

    /// La plateforme est normalisee a l'ecriture, pas seulement a la lecture.
    func testStoredPlatformIsLowercased() {
        let defaults = Self.freshDefaults(#function)
        let store = PeerVersionStore(defaults: defaults)
        store.record("1.0.43", platform: "  iOS  ", for: "peerQ")
        XCTAssertEqual(store.platform(for: "peerQ"), "ios")
        XCTAssertTrue(store.peerIsOutdated("peerQ"))
    }

    /// Un `nil` (l'ancienne build n'envoie pas le champ) ne doit pas effacer une
    /// version deja lue: sinon une seule charge de profil d'un autre chemin
    /// ferait clignoter la banniere.
    func testNilDoesNotEraseAKnownVersion() {
        let defaults = Self.freshDefaults(#function)
        let store = PeerVersionStore(defaults: defaults)
        store.record("1.0.43", platform: "ios", for: "peerB")
        store.record(nil, platform: "ios", for: "peerB")
        store.record("   ", platform: "ios", for: "peerB")
        XCTAssertEqual(store.version(for: "peerB"), "1.0.43")
    }

    func testStoreSurvivesRelaunch() {
        let defaults = Self.freshDefaults(#function)
        PeerVersionStore(defaults: defaults).record("1.0.43", platform: "ios", for: "peerC")
        let relaunched = PeerVersionStore(defaults: defaults)
        XCTAssertEqual(relaunched.version(for: "peerC"), "1.0.43")
        XCTAssertEqual(relaunched.platform(for: "peerC"), "ios",
                       "la plateforme survit au relancement, sinon la banniere s'eteint")
        XCTAssertTrue(relaunched.peerIsOutdated("peerC"))
    }

    // MARK: - 2. Compatibilite de fil de la charge de profil

    /// Le decodeur de la 1.0.43, reproduit champ pour champ. Il ne connait pas
    /// `appVersion`. S'il levait sur la charge de la 1.0.44, 521 appareils sur
    /// 535 cesseraient de recevoir les mises a jour de profil — avatar, cle
    /// post-quantique, drapeaux de capacite compris.
    private struct LegacyProfileUpdatePayload: Codable {
        var type: String = "profile_update"
        let profileImageData: String?
        let lastSeen: Double
        var version: Int = 1
        var pqPublicKey: String? = nil
        var groupEnvelopeV3: Bool? = nil
        var ratchetV3: Bool? = nil
    }

    func testNewPayloadDecodesThroughTheOldParser() throws {
        let fresh = ProfileUpdatePayload(profileImageData: "QUJD",
                                         lastSeen: 1_757_000_000,
                                         pqPublicKey: "cHFrZXk=",
                                         groupEnvelopeV3: true,
                                         ratchetV3: true,
                                         appVersion: "1.0.44",
                                         appPlatform: "ios")
        let data = try JSONEncoder().encode(fresh)

        // Les DEUX champs sont bien sur le fil — sinon ce test ne prouverait rien.
        let raw = try XCTUnwrap(String(data: data, encoding: .utf8))
        XCTAssertTrue(raw.contains("\"appVersion\""), "le champ doit etre emis: \(raw)")
        XCTAssertTrue(raw.contains("\"appPlatform\""), "le tag doit etre emis: \(raw)")

        // Et le decodeur de la 1.0.43 le traverse sans broncher.
        let old = try JSONDecoder().decode(LegacyProfileUpdatePayload.self, from: data)
        XCTAssertEqual(old.profileImageData, "QUJD")
        XCTAssertEqual(old.lastSeen, 1_757_000_000)
        XCTAssertEqual(old.pqPublicKey, "cHFrZXk=")
        XCTAssertEqual(old.groupEnvelopeV3, true)
        XCTAssertEqual(old.ratchetV3, true)
        XCTAssertEqual(old.type, "profile_update")
    }

    /// L'autre sens: la charge que la 1.0.43 emet AUJOURD'HUI ne porte pas le
    /// champ, et la 1.0.44 doit la lire sans erreur et sans rien inventer.
    func testOldPayloadStillDecodesOnTheNewBuild() throws {
        let legacy = LegacyProfileUpdatePayload(profileImageData: nil,
                                                lastSeen: 1_756_000_000,
                                                pqPublicKey: nil,
                                                groupEnvelopeV3: true,
                                                ratchetV3: true)
        let data = try JSONEncoder().encode(legacy)
        let new = try JSONDecoder().decode(ProfileUpdatePayload.self, from: data)
        XCTAssertNil(new.appVersion, "aucune version annoncee, donc aucune supposition")
        XCTAssertNil(new.appPlatform, "aucune plateforme annoncee non plus")
        XCTAssertFalse(RatchetInterop.peerIsOutdated(new.appVersion, platform: new.appPlatform),
                       "un silence ne doit pas declencher la banniere")
        XCTAssertEqual(new.ratchetV3, true)
    }

    /// Une charge etrangere qui porte des cles qu'on ne connait pas — le cas
    /// symetrique, pour que le decodeur reste tolerant dans les deux sens.
    func testUnknownKeysAreIgnored() throws {
        let json = """
        {"type":"profile_update","profileImageData":null,"lastSeen":1.0,"version":1,
         "appVersion":"1.0.44","appPlatform":"ios",
         "somethingFromTheFuture":{"a":1},"anotherOne":[1,2,3]}
        """
        let decoded = try JSONDecoder().decode(ProfileUpdatePayload.self, from: Data(json.utf8))
        XCTAssertEqual(decoded.appVersion, "1.0.44")
        XCTAssertEqual(decoded.appPlatform, "ios")
    }

    /// Le cas ANDROID sur le fil, bout en bout: la charge que Kotlin emet
    /// aujourd'hui (`ProfileVersionWire.stamp`) doit se decoder ici et etre jugee
    /// sur l'echelle 1.6.x, jamais sur la notre.
    func testAnAndroidPayloadIsDecodedAndJudgedOnItsOwnLadder() throws {
        func payload(_ version: String) throws -> ProfileUpdatePayload {
            let json = """
            {"type":"profile_update","profileImageData":null,"lastSeen":1.0,"version":1,
             "ratchetV3":true,"groupEnvelopeV3":true,
             "appVersion":"\(version)","appPlatform":"android"}
            """
            return try JSONDecoder().decode(ProfileUpdatePayload.self, from: Data(json.utf8))
        }

        let old = try payload("1.6.13")
        XCTAssertEqual(old.appVersion, "1.6.13")
        XCTAssertEqual(old.appPlatform, "android")
        XCTAssertTrue(RatchetInterop.peerIsOutdated(old.appVersion, platform: old.appPlatform),
                      "1.6.13 est bien anterieure au changement de calendrier, cote Android")

        // Et le cas qui etait LE bug: un numero Android au-dessus de son propre
        // plancher ne doit rien declencher, meme s'il « parait » enorme a cote
        // d'un 1.0.44.
        let current = try payload("1.6.15")
        XCTAssertFalse(RatchetInterop.peerIsOutdated(current.appVersion, platform: current.appPlatform))
    }

    /// Un `nil` ne doit pas ecrire la cle du tout: le fil reste octet pour
    /// octet celui de la 1.0.43 quand il n'y a rien a dire.
    func testAbsentVersionIsNotEvenEmitted() throws {
        let payload = ProfileUpdatePayload(profileImageData: nil, lastSeen: 1)
        let raw = try XCTUnwrap(String(data: try JSONEncoder().encode(payload), encoding: .utf8))
        XCTAssertFalse(raw.contains("appVersion"), "un nil ne doit pas apparaitre: \(raw)")
        XCTAssertFalse(raw.contains("appPlatform"), "ni le tag: \(raw)")
    }

    /// Le decodeur de la 1.0.43 traverse la charge qui porte les DEUX cles
    /// exactement comme celle qui n'en porte aucune. C'est la seule preuve qui
    /// compte: 521 appareils sur 535 lisent cette charge avec ce decodeur-la.
    func testTheLegacyParserSurvivesBothNewKeys() throws {
        let fresh = ProfileUpdatePayload(profileImageData: nil,
                                         lastSeen: 1_757_000_000,
                                         pqPublicKey: nil,
                                         groupEnvelopeV3: true,
                                         ratchetV3: true,
                                         appVersion: "1.0.44",
                                         appPlatform: "ios")
        let data = try JSONEncoder().encode(fresh)
        let old = try JSONDecoder().decode(LegacyProfileUpdatePayload.self, from: data)
        XCTAssertEqual(old.ratchetV3, true)
        XCTAssertEqual(old.groupEnvelopeV3, true)
        XCTAssertEqual(old.lastSeen, 1_757_000_000)
        XCTAssertEqual(old.type, "profile_update")
    }

    // MARK: - Le texte affiche

    private static let bannerKeys = ["chat.peer_outdated.message", "chat.peer_outdated.dismiss",
                                     "update.prompt.title", "update.prompt.body",
                                     "update.prompt.action", "update.prompt.dismiss"]

    /// Deux choses qu'aucun test de logique n'attrape.
    ///
    /// 1. Une cle absente se rend ELLE-MEME: la banniere afficherait
    ///    litteralement « chat.peer_outdated.message » a l'ecran, et la suite
    ///    resterait verte. C'est exactement ce qui s'est produit au premier
    ///    passage de ce test — le simulateur du projet tourne en fr_FR, les
    ///    cles ne sont qu'en `en.lproj`, et `NSLocalizedString` NE retombe PAS
    ///    sur l'anglais. D'ou `LocalizedOrEnglish`, et d'ou ce test.
    /// 2. La consigne d'ecriture. Cette banniere finit en capture d'ecran, et
    ///    une capture qui dit « faille » circule bien plus loin que le fait
    ///    qu'elle decrit. Les mots interdits sont verifies, pas seulement
    ///    demandes.
    func testBannerCopyExistsAndStaysPlain() {
        let banned = ["security", "securite", "sécurité", "vulnerability", "vulnérabilité",
                      "insecure", "broken", "hack", "breach"]

        for key in Self.bannerKeys {
            let text = LocalizedOrEnglish.text(key)
            XCTAssertNotEqual(text, key, "cle absente: l'ecran afficherait « \(key) »")
            XCTAssertFalse(text.isEmpty)
            let lowered = text.lowercased()
            for word in banned {
                XCTAssertFalse(lowered.contains(word),
                               "« \(word) » ne doit pas apparaitre dans \(key): \(text)")
            }
        }
    }

    /// Le repli lui-meme, teste pour ce qu'il est: il ne doit jamais rendre la
    /// cle quand `en.lproj` la porte, et il doit rendre la cle — pas planter —
    /// quand personne ne la porte.
    func testEnglishFallbackNeverShowsARawKey() {
        XCTAssertEqual(LocalizedOrEnglish.text("chat.peer_outdated.message"),
                       "This person is on an older version of OSHI. Ask them to update — some messages may not get through until they do.")
        let missing = "oshi.key.that.does.not.exist.\(UUID().uuidString)"
        XCTAssertEqual(LocalizedOrEnglish.text(missing), missing)
    }

    // MARK: - Outils

    private static func freshDefaults(_ name: String) -> UserDefaults {
        let suite = "PeerVersionCompatTests.\(name).\(UUID().uuidString)"
        UserDefaults().removePersistentDomain(forName: suite)
        return UserDefaults(suiteName: suite)!
    }
}
