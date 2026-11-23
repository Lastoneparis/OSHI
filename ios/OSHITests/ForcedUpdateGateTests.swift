//
//  ForcedUpdateGateTests.swift
//  OSHI — le levier qui peut murer toute une flotte.
//
//  Ce test existe pour une raison précise: `shouldRequireUpdate` est la seule
//  fonction de l'app dont une mauvaise valeur, poussée depuis un fichier de
//  config, rend l'application INUTILISABLE pour tout le monde d'un coup — et il
//  faudrait alors une livraison App Store pour libérer les gens. Ses propriétés
//  de sûreté valent plus que sa fonctionnalité.
//

import XCTest
@testable import OSHI

final class ForcedUpdateGateTests: XCTestCase {

    // MARK: Échouer OUVERT

    /// Le cas le plus fréquent, et le plus important: le serveur ne dit rien.
    /// Une panne réseau, un fichier de config absent ou un premier lancement ne
    /// doivent JAMAIS murer une app qui marche.
    func testSaysNothingMeansNoBlock() {
        XCTAssertFalse(V2FeatureFlag.shouldRequireUpdate(currentBuild: 1, minimumSupported: nil))
        XCTAssertFalse(V2FeatureFlag.shouldRequireUpdate(currentBuild: 1, minimumSupported: 0))
        XCTAssertFalse(V2FeatureFlag.shouldRequireUpdate(currentBuild: 1, minimumSupported: -5))
    }

    // MARK: Le comportement attendu

    func testOlderBuildIsBlocked() {
        XCTAssertTrue(V2FeatureFlag.shouldRequireUpdate(currentBuild: 110, minimumSupported: 111))
    }

    /// La build exigée elle-même ne doit pas être bloquée — sinon la mise à jour
    /// ne débloque rien et l'utilisateur reste muré après avoir obéi.
    func testTheRequiredBuildItselfPasses() {
        XCTAssertFalse(V2FeatureFlag.shouldRequireUpdate(currentBuild: 111, minimumSupported: 111))
        XCTAssertFalse(V2FeatureFlag.shouldRequireUpdate(currentBuild: 112, minimumSupported: 111))
    }

    // MARK: Le garde-fou contre l'erreur d'exploitation

    /// Un zéro de trop dans le fichier de config. Sans cette garde, TOUS les
    /// appareils sont murés — y compris ceux qui viennent d'installer la
    /// dernière version — et il faut une livraison App Store pour les libérer.
    /// LE cas concret: la build vaut 111, on tape 1110. C'est ce test qui a
    /// montré que la marge d'origine (build + 1000) laissait passer précisément
    /// cette coquille — 1110 ≤ 1111 — et murait donc toute la flotte. La marge
    /// doit être plus PETITE que l'erreur qu'elle attrape.
    func testAbsurdMinimumIsIgnored() {
        XCTAssertFalse(V2FeatureFlag.shouldRequireUpdate(currentBuild: 111, minimumSupported: 1110),
                       "un zéro de trop doit être ignoré, pas appliqué")
        XCTAssertFalse(V2FeatureFlag.shouldRequireUpdate(currentBuild: 111, minimumSupported: 999_999))
    }

    /// Mais la garde ne doit pas avaler un saut LÉGITIME: on doit pouvoir exiger
    /// une build sensiblement plus récente sans que le garde-fou l'annule.
    /// Un écart réaliste: `min_supported_build` se règle sur la build qu'on
    /// vient de livrer, donc la distance avec un appareil en retard se compte en
    /// dizaines, pas en milliers.
    func testALegitimateJumpStillBlocks() {
        XCTAssertTrue(V2FeatureFlag.shouldRequireUpdate(currentBuild: 111, minimumSupported: 120))
        XCTAssertTrue(V2FeatureFlag.shouldRequireUpdate(currentBuild: 95, minimumSupported: 111))
        // Et un appareil vraiment ancien doit quand même pouvoir être rattrapé.
        XCTAssertTrue(V2FeatureFlag.shouldRequireUpdate(currentBuild: 12, minimumSupported: 111))
    }

    // MARK: La distinction qui a failli coûter cher

    /// `min_build` et `min_supported_build` sont DEUX leviers différents, et les
    /// confondre est le piège de ce fichier: `min_build` rend `enabled == false`
    /// — il COUPE la messagerie v2 des anciens clients, en silence, sans rien
    /// leur dire. S'en servir pour forcer une mise à jour laisserait les gens
    /// muets sans comprendre pourquoi.
    ///
    /// Ce test garde la séparation: un `min_build` seul ne doit jamais bloquer
    /// l'app, et un `min_supported_build` seul ne doit jamais couper v2.
    func testTheTwoLeversStayIndependent() throws {
        let onlyMinBuild = #"{"v2_enabled":true,"rollout_percent":100,"min_build":999}"#
        let decoded = try JSONDecoder().decode(V2FeatureFlag.Remote.self,
                                               from: Data(onlyMinBuild.utf8))
        XCTAssertNil(decoded.min_supported_build,
                     "min_build ne doit pas être lu comme une exigence de mise à jour")
        XCTAssertFalse(V2FeatureFlag.shouldRequireUpdate(currentBuild: 1,
                                                        minimumSupported: decoded.min_supported_build))
    }

    /// La charge que le serveur renvoie AUJOURD'HUI ne porte pas le nouveau
    /// champ. Si son décodage échouait, `storeRemote` cesserait de mettre la
    /// config en cache — et le drapeau v2 retomberait silencieusement sur ses
    /// valeurs de démarrage. D'où le champ optionnel, et ce test.
    func testTodaysServerPayloadStillDecodes() throws {
        let current = #"{"v2_enabled":true,"rollout_percent":100,"min_build":0}"#
        let decoded = try JSONDecoder().decode(V2FeatureFlag.Remote.self,
                                               from: Data(current.utf8))
        XCTAssertTrue(decoded.v2_enabled)
        XCTAssertEqual(decoded.rollout_percent, 100)
        XCTAssertNil(decoded.min_supported_build)
    }
}
