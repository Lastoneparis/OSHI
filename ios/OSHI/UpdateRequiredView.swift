//
//  UpdateRequiredView.swift
//  OSHI — l'écran qui s'affiche quand la build installée est trop ancienne.
//
//  CE QUE CE MÉCANISME PEUT, ET CE QU'IL NE PEUT PAS
//  iOS n'offre AUCUN moyen de forcer une app déjà installée à se mettre à jour.
//  Aucune API ne le permet, et aucune n'est prévue. Ce qu'une app peut faire,
//  c'est REFUSER DE FONCTIONNER et renvoyer vers l'App Store.
//
//  Ça implique une chose qu'il faut dire nettement: le test doit déjà être DANS
//  la build installée. Une version livrée avant ce fichier l'ignorera toujours,
//  quoi qu'on mette dans la config du serveur. Ce levier vaut pour l'AVENIR.
//
//  Pour les versions déjà dans la nature, le seul mécanisme qui existe est la
//  mise à jour automatique de l'App Store, activée par défaut sur la plupart des
//  appareils. Elle ne se pilote pas depuis ici.
//
//  POURQUOI UN ÉCRAN, ET PAS UNE ALERTE QU'ON PEUT ÉCARTER
//  Le seul cas où l'on coupe l'usage d'une app qui marche, c'est quand continuer
//  est PIRE que s'arrêter: un défaut de protocole qui corrompt les sessions des
//  correspondants, une faille. Dans ce cas une alerte optionnelle ne suffit pas.
//  Hors de ce cas, on ne s'en sert pas: murer quelqu'un qui voulait juste
//  envoyer un message est une décision grave, et elle se paie en désinstallation.
//
//  L'écran garde donc une issue: le bouton mène à l'App Store, et le texte dit
//  POURQUOI. Un mur sans explication est lu comme une panne.
//

import SwiftUI

struct UpdateRequiredView: View {
    /// Fiche App Store d'OSHI. En dur, et c'est voulu: cet écran s'affiche
    /// précisément quand on ne peut plus faire confiance à ce que le serveur
    /// nous a dit de la version, et une URL pilotée à distance sur un écran
    /// bloquant serait un moyen d'emmener les gens ailleurs.
    private static let storeURL = URL(string: "https://apps.apple.com/app/oshi-mesh/id6753926350")!

    var body: some View {
        VStack(spacing: 24) {
            Spacer()

            Image(systemName: "arrow.down.circle.fill")
                .font(.system(size: 56))
                .foregroundStyle(.tint)
                .accessibilityHidden(true)

            Text("update.required.title")
                .font(.title2.bold())
                .multilineTextAlignment(.center)

            Text("update.required.body")
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 420)

            Link(destination: Self.storeURL) {
                Text("update.required.action")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
            }
            .buttonStyle(.borderedProminent)
            .padding(.horizontal, 32)

            Spacer()

            // Les messages déjà reçus restent sur l'appareil: cet écran bloque
            // l'usage, il n'efface rien. Le dire évite la panique — et évite
            // surtout la désinstallation, qui elle, effacerait tout.
            Text("update.required.data_safe")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
                .padding(.bottom, 24)
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemBackground))
    }
}
