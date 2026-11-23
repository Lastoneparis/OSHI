/*
  OSHI V2 — i18n
  Lightweight key/value translation map. No build step, no framework.
  Detects navigator.language on first load, persists to localStorage.
  Swap any element by setting data-i18n="key.path" in HTML.
*/

const TRANSLATIONS = {
  en: {
    nav: { manifesto: "Manifesto", features: "Features", compare: "Comparison", trust: "Trust", cta: "Get the app →" },
    hero: {
      eyebrow: "End-to-end encrypted · Mesh-capable · iOS + Android",
      title_a: "A messenger that works ",
      title_em: "when nothing else does.",
      sub: "OSHI is the encrypted messenger built for the world after the cloud. No phone number. Zero metadata. Speaks Bluetooth and Wi-Fi mesh when the internet's down, hides messages in plain sight when it isn't.",
      cta_ios: "Download for iOS",
      cta_android: "Download for Android",
      cta_manifesto: "Read the manifesto",
      stat1_label: "phone numbers required",
      stat2_label: "covert channels",
      stat3_label: "regions offline-ready",
      stat4_label: "languages",
      scroll: "Scroll",
    },
    manifesto: {
      label: "Our manifesto",
      title_a: "Privacy isn't a feature. ",
      title_em: "It's the product.",
      p1: "Every other messenger you've used logs your friends, harvests your contact graph, and stores your messages on someone else's server in a country you've never visited. They call it security; we call it surveillance with a nice icon.",
      p2: "OSHI is different by design. There is no central server holding your conversations. No phone number tying your identity to a SIM card. No cloud backups leaking your photos. Your messages are end-to-end encrypted with X25519 + ChaCha20-Poly1305 and never decrypted anywhere but on your device.",
      p3: "When the internet works, we use it — but invisibly, with steganographic covert channels that look like ordinary HTTPS. When it doesn't, OSHI speaks Bluetooth LE and Wi-Fi Direct mesh, hop-routes through nearby devices, and keeps you connected through blackouts, festivals, deserts, and demonstrations.",
      p4: "Open source. Zero metadata. No telemetry. No ads. The only entity that knows what you said is you.",
    },
    features: {
      eyebrow: "What's inside",
      title_a: "Everything you'd expect from a messenger. ",
      title_em: "And the part nobody else built.",
      f1_h: "Bluetooth + Wi-Fi mesh",
      f1_p: "When there's no cell signal and no internet, OSHI keeps working. Messages hop-route through nearby devices, encrypted end-to-end at every leg, so you stay in touch through blackouts, festivals, and natural disasters.",
      f1_l1: "Multi-hop relay (TTL 7)",
      f1_l2: "Encrypted at every hop",
      f1_l3: "Reaches ~300 m per hop in dense networks",
      f1_l4: "Battery-aware: ~3 % per hour idle",
      f2_h: "Zero metadata",
      f2_p: "No phone number, no email, no friend graph stored anywhere. Identity is a public key pair generated on your device.",
      f3_h: "Steganographic covert channels",
      f3_p: "6 ways to pass messages through inspection: HTTPS look-alike, audio modem, image LSB, DNS, NTP, ICMP.",
      f4_h: "Offline maps for 180+ regions",
      f4_p: "Download a country once and navigate it forever. Tiles, road graph, addresses, and POIs — all stored on-device. Turn-by-turn routing without sending a byte to Google or Apple. Even searches like \"12 rue de Rivoli\" or \"pharmacy\" stay local.",
      f4_b1: "120 GB of OSM data hosted",
      f4_b2: "House-number aware search",
      f4_b3: "A* offline routing",
      f5_h: "On-device AI",
      f5_p: "Apple Intelligence + Gemini Nano locally. Smart replies, summarisation, translation — never your tokens, never your data.",
      f6_h: "Bot API",
      f6_p: "Build encrypted bots that talk over mesh. Webhook-compatible. No central server required.",
      f7_h: "Open source",
      f7_p: "Crypto audited, code on GitHub, builds reproducible. Verify everything we say with the source.",
    },
    compare: {
      eyebrow: "How we stack up",
      title: "The honest comparison.",
      sub: "Other messengers protect your messages. We refuse to know they ever existed.",
      r1: "End-to-end encrypted",
      r2: "Works without internet",
      r3: "No phone number required",
      r4: "Zero metadata logged",
      r5: "Steganographic transport",
      r6: "On-device AI",
      r7: "Offline maps + navigation",
      r8: "Open source & reproducible",
      r9: "No central server can be subpoenaed",
    },
    trust: {
      eyebrow: "Security in depth",
      title_a: "You don't have to trust us. ",
      title_em: "That's the point.",
      t1_h: "Identity is a key, not an account",
      t1_p: "Your handle is the SHA-256 fingerprint of a public key generated on first launch. Nothing to forget, nothing to phish, nothing to legally compel from us — we never had it.",
      t2_h: "X25519 + ChaCha20-Poly1305",
      t2_p: "Industry-standard end-to-end encryption. Double-ratchet forward secrecy on every conversation. No backdoor, no key-escrow, no plaintext shadow copy on our infrastructure.",
      t3_h: "Zero-knowledge by construction",
      t3_p: "OSHI's servers see encrypted blobs and routing hints — never plaintext, never the recipient's name. The minimum architecture for the maximum privacy.",
      t4_h: "Offline by design",
      t4_p: "The decryption keys, the maps, the AI inference — everything that touches your data runs on your hardware. The cloud is optional, not assumed.",
    },
    usecase: {
      eyebrow: "Built for the moments that matter",
      title: "The messenger you reach for when the others stop working.",
      u1_h: "Journalists in hostile networks",
      u1_p: "Source protection isn't a checkbox. OSHI's steganographic channels let you publish to a contact through inspection. The metadata that's not collected can't be subpoenaed.",
      u2_h: "Travellers off-grid",
      u2_p: "Hiking a national park, sailing the Med, driving the Sahara. The maps are loaded, the routing is local, and your group chat works peer-to-peer over mesh.",
      u3_h: "Crowds & demonstrations",
      u3_p: "Carrier networks fail. OSHI's mesh thrives where there are bodies. Hop through 1 000 phones to reach the friend three blocks away.",
      u4_h: "Privacy-aware professionals",
      u4_p: "Lawyers, doctors, founders. Conversations that shouldn't ever appear in a discovery request. Zero metadata leaves no paper trail to subpoena.",
    },
    cta: {
      title: "Try the messenger they don't want you to have.",
      sub: "Free. Open source. No phone number, no signup. Install in 30 seconds, encrypted in 30 milliseconds.",
      btn_ios: "Download for iOS",
      btn_android: "Download for Android",
      btn_github: "Source on GitHub",
      foot: "Android beta opens July 2026. <a href=\"#\">Get notified →</a>",
    },
    footer: {
      tag: "A messenger that works when nothing else does.",
      col1_h: "Product", col1_a: "Features", col1_b: "Comparison", col1_c: "Bot API", col1_d: "iOS app",
      col2_h: "Trust", col2_a: "Privacy policy", col2_b: "Terms", col2_c: "License", col2_d: "Security",
      col3_h: "Company", col3_a: "About", col3_b: "Support", col3_c: "Investors", col3_d: "Contact",
      copyright: "© 2026 OSHI Messenger. The minimum architecture for the maximum privacy.",
      status: "All systems operational",
    },
  },

  fr: {
    nav: { manifesto: "Manifeste", features: "Fonctionnalités", compare: "Comparaison", trust: "Confiance", cta: "Télécharger →" },
    hero: {
      eyebrow: "Chiffré de bout en bout · Mesh · iOS + Android",
      title_a: "Une messagerie qui marche ",
      title_em: "quand rien d'autre ne marche.",
      sub: "OSHI est la messagerie chiffrée pensée pour le monde d'après le cloud. Sans numéro de téléphone. Zéro métadonnée. Parle en mesh Bluetooth et Wi-Fi quand internet tombe, cache vos messages en pleine vue quand il ne tombe pas.",
      cta_ios: "Télécharger sur iOS",
      cta_android: "Télécharger sur Android",
      cta_manifesto: "Lire le manifeste",
      stat1_label: "numéros de téléphone requis",
      stat2_label: "canaux furtifs",
      stat3_label: "régions hors-ligne",
      stat4_label: "langues",
      scroll: "Faire défiler",
    },
    manifesto: {
      label: "Notre manifeste",
      title_a: "La vie privée n'est pas une option. ",
      title_em: "C'est le produit.",
      p1: "Toutes les autres messageries que vous avez utilisées enregistrent vos amis, moissonnent votre graphe de contacts et stockent vos messages sur le serveur de quelqu'un d'autre dans un pays que vous n'avez jamais visité. Ils appellent ça la sécurité ; nous appelons ça de la surveillance avec une jolie icône.",
      p2: "OSHI est différent par conception. Aucun serveur central ne détient vos conversations. Aucun numéro de téléphone ne lie votre identité à une carte SIM. Aucune sauvegarde cloud ne fuite vos photos. Vos messages sont chiffrés de bout en bout avec X25519 + ChaCha20-Poly1305 et ne sont jamais déchiffrés ailleurs que sur votre appareil.",
      p3: "Quand internet marche, on l'utilise — mais invisiblement, via des canaux furtifs qui ressemblent à du HTTPS ordinaire. Quand il ne marche pas, OSHI parle Bluetooth LE et Wi-Fi Direct, route en sauts à travers les appareils voisins, et vous garde connecté en cas de blackout, festival, désert ou manifestation.",
      p4: "Open source. Zéro métadonnée. Pas de télémétrie. Pas de pub. La seule entité qui sait ce que vous avez dit, c'est vous.",
    },
    features: {
      eyebrow: "Ce qu'il y a dedans",
      title_a: "Tout ce qu'on attend d'une messagerie. ",
      title_em: "Et ce que personne d'autre n'a construit.",
      f1_h: "Mesh Bluetooth + Wi-Fi",
      f1_p: "Sans réseau cellulaire, sans internet, OSHI continue. Les messages sautent d'un appareil à l'autre, chiffrés à chaque relais, pour vous garder en contact pendant blackouts, festivals et catastrophes naturelles.",
      f1_l1: "Relais multi-sauts (TTL 7)",
      f1_l2: "Chiffré à chaque saut",
      f1_l3: "~300 m par saut en réseau dense",
      f1_l4: "Économe : ~3 % par heure en veille",
      f2_h: "Zéro métadonnée",
      f2_p: "Pas de numéro, pas d'email, pas de graphe d'amis stocké quelque part. L'identité est une paire de clés générée sur votre appareil.",
      f3_h: "Canaux furtifs stéganographiques",
      f3_p: "6 façons de faire passer des messages à travers une inspection : HTTPS clone, modem audio, image LSB, DNS, NTP, ICMP.",
      f4_h: "Cartes hors ligne pour 180+ régions",
      f4_p: "Téléchargez un pays une fois et naviguez-le pour toujours. Tuiles, graphe routier, adresses, POIs — tout sur l'appareil. Itinéraire turn-by-turn sans envoyer un octet à Google ou Apple. Même \"12 rue de Rivoli\" ou \"pharmacie\" reste local.",
      f4_b1: "120 Go d'OSM hébergés",
      f4_b2: "Recherche avec numéro de rue",
      f4_b3: "Routage A* hors ligne",
      f5_h: "IA sur appareil",
      f5_p: "Apple Intelligence + Gemini Nano localement. Réponses intelligentes, résumés, traduction — jamais vos tokens, jamais vos données.",
      f6_h: "API Bot",
      f6_p: "Construisez des bots chiffrés qui parlent en mesh. Compatible webhook. Sans serveur central.",
      f7_h: "Open source",
      f7_p: "Crypto auditée, code sur GitHub, builds reproductibles. Vérifiez tout ce que nous disons via la source.",
    },
    compare: {
      eyebrow: "Comment on se compare",
      title: "La comparaison honnête.",
      sub: "Les autres messageries protègent vos messages. Nous refusons de savoir qu'ils ont existé.",
      r1: "Chiffrement de bout en bout",
      r2: "Marche sans internet",
      r3: "Sans numéro de téléphone",
      r4: "Zéro métadonnée loggée",
      r5: "Transport stéganographique",
      r6: "IA sur appareil",
      r7: "Cartes hors ligne + navigation",
      r8: "Open source & reproductible",
      r9: "Aucun serveur central convocable",
    },
    trust: {
      eyebrow: "Sécurité en profondeur",
      title_a: "Vous n'avez pas à nous faire confiance. ",
      title_em: "C'est l'idée.",
      t1_h: "L'identité est une clé, pas un compte",
      t1_p: "Votre identifiant est l'empreinte SHA-256 d'une clé publique générée au premier lancement. Rien à oublier, rien à phisher, rien à nous demander légalement — nous ne l'avons jamais eu.",
      t2_h: "X25519 + ChaCha20-Poly1305",
      t2_p: "Chiffrement E2E standard de l'industrie. Forward secrecy double-ratchet sur chaque conversation. Pas de backdoor, pas de key-escrow, pas de copie en clair sur notre infrastructure.",
      t3_h: "Zero-knowledge par construction",
      t3_p: "Les serveurs d'OSHI voient des blobs chiffrés et des indices de routage — jamais le clair, jamais le nom du destinataire. L'architecture minimale pour le maximum de vie privée.",
      t4_h: "Hors ligne par conception",
      t4_p: "Les clés de déchiffrement, les cartes, l'inférence IA — tout ce qui touche à vos données tourne sur votre matériel. Le cloud est optionnel, pas supposé.",
    },
    usecase: {
      eyebrow: "Conçu pour les moments qui comptent",
      title: "La messagerie qu'on sort quand les autres ne marchent plus.",
      u1_h: "Journalistes en réseau hostile",
      u1_p: "La protection des sources n'est pas une case à cocher. Les canaux furtifs d'OSHI vous permettent de publier vers un contact à travers une inspection. La métadonnée non collectée ne peut pas être convoquée.",
      u2_h: "Voyageurs hors réseau",
      u2_p: "Randonnée en parc national, voile en Méditerranée, traversée du Sahara. Cartes chargées, routage local, et le groupe chat marche en peer-to-peer via mesh.",
      u3_h: "Foules & manifestations",
      u3_p: "Les opérateurs lâchent. Le mesh d'OSHI prospère là où il y a des corps. Sautez à travers 1 000 téléphones pour atteindre l'ami à trois rues.",
      u4_h: "Pros sensibles à la vie privée",
      u4_p: "Avocats, médecins, fondateurs. Des conversations qui ne devraient jamais apparaître dans une discovery. Zéro métadonnée ne laisse aucune trace à convoquer.",
    },
    cta: {
      title: "Essayez la messagerie qu'ils ne veulent pas que vous ayez.",
      sub: "Gratuit. Open source. Pas de numéro, pas de signup. Installé en 30 secondes, chiffré en 30 millisecondes.",
      btn_ios: "Télécharger sur iOS",
      btn_android: "Télécharger sur Android",
      btn_github: "Source sur GitHub",
      foot: "La bêta Android ouvre en juillet 2026. <a href=\"#\">Être notifié →</a>",
    },
    footer: {
      tag: "Une messagerie qui marche quand rien d'autre ne marche.",
      col1_h: "Produit", col1_a: "Fonctionnalités", col1_b: "Comparaison", col1_c: "API Bot", col1_d: "App iOS",
      col2_h: "Confiance", col2_a: "Politique de confidentialité", col2_b: "Conditions", col2_c: "Licence", col2_d: "Sécurité",
      col3_h: "Entreprise", col3_a: "À propos", col3_b: "Support", col3_c: "Investisseurs", col3_d: "Contact",
      copyright: "© 2026 OSHI Messenger. L'architecture minimale pour le maximum de vie privée.",
      status: "Tous les systèmes opérationnels",
    },
  },

  es: {
    nav: { manifesto: "Manifiesto", features: "Funciones", compare: "Comparación", trust: "Confianza", cta: "Descargar →" },
    hero: {
      eyebrow: "Cifrado de extremo a extremo · Mesh · iOS + Android",
      title_a: "Una mensajería que funciona ",
      title_em: "cuando nada más lo hace.",
      sub: "OSHI es la mensajería cifrada pensada para el mundo después de la nube. Sin número de teléfono. Cero metadatos. Habla mesh Bluetooth y Wi-Fi cuando se cae internet, esconde mensajes a plena vista cuando no.",
      cta_ios: "Descargar para iOS",
      cta_android: "Descargar para Android",
      cta_manifesto: "Leer el manifiesto",
      stat1_label: "números requeridos",
      stat2_label: "canales encubiertos",
      stat3_label: "regiones offline",
      stat4_label: "idiomas",
      scroll: "Desplazar",
    },
    manifesto: {
      label: "Nuestro manifiesto",
      title_a: "La privacidad no es una función. ",
      title_em: "Es el producto.",
      p1: "Todas las demás mensajerías que has usado registran a tus amigos, cosechan tu grafo de contactos y guardan tus mensajes en el servidor de otro en un país que nunca visitaste. Lo llaman seguridad; nosotros lo llamamos vigilancia con un icono bonito.",
      p2: "OSHI es diferente por diseño. No hay servidor central que tenga tus conversaciones. Ningún número de teléfono atando tu identidad a una SIM. Ningún backup en la nube filtrando tus fotos. Tus mensajes están cifrados E2E con X25519 + ChaCha20-Poly1305 y nunca se descifran en ningún sitio salvo tu dispositivo.",
      p3: "Cuando internet funciona, lo usamos — pero invisiblemente, con canales encubiertos que parecen HTTPS normal. Cuando no, OSHI habla Bluetooth LE y Wi-Fi Direct mesh, salta entre dispositivos cercanos, y te mantiene conectado en blackouts, festivales, desiertos y manifestaciones.",
      p4: "Código abierto. Cero metadatos. Sin telemetría. Sin anuncios. La única entidad que sabe lo que dijiste eres tú.",
    },
    features: {
      eyebrow: "Lo que hay dentro",
      title_a: "Todo lo que esperas de una mensajería. ",
      title_em: "Y lo que nadie más construyó.",
      f1_h: "Mesh Bluetooth + Wi-Fi", f1_p: "Sin señal celular y sin internet, OSHI sigue funcionando. Los mensajes saltan entre dispositivos cercanos, cifrados E2E en cada salto.",
      f1_l1: "Relé multi-salto (TTL 7)", f1_l2: "Cifrado en cada salto", f1_l3: "~300 m por salto en redes densas", f1_l4: "Batería ~3 % por hora en reposo",
      f2_h: "Cero metadatos", f2_p: "Sin teléfono, sin email, sin grafo de amigos guardado. La identidad es un par de claves generado en tu dispositivo.",
      f3_h: "Canales encubiertos esteganográficos", f3_p: "6 formas de pasar mensajes por inspección: clon HTTPS, módem de audio, imagen LSB, DNS, NTP, ICMP.",
      f4_h: "Mapas offline para 180+ regiones", f4_p: "Descarga un país una vez y navégalo para siempre. Tiles, grafo viario, direcciones y POIs — todo en el dispositivo. Navegación turn-by-turn sin enviar un byte a Google o Apple.",
      f4_b1: "120 GB de OSM alojados", f4_b2: "Búsqueda con número de calle", f4_b3: "Routing A* offline",
      f5_h: "IA en el dispositivo", f5_p: "Apple Intelligence + Gemini Nano localmente. Respuestas inteligentes, resúmenes, traducción — nunca tus tokens, nunca tus datos.",
      f6_h: "API Bot", f6_p: "Crea bots cifrados que hablan por mesh. Compatible con webhook. Sin servidor central.",
      f7_h: "Código abierto", f7_p: "Cripto auditada, código en GitHub, builds reproducibles. Verifica todo lo que decimos en el código.",
    },
    compare: {
      eyebrow: "Cómo nos comparamos", title: "La comparación honesta.",
      sub: "Otras mensajerías protegen tus mensajes. Nosotros nos negamos a saber que existieron.",
      r1: "Cifrado E2E", r2: "Funciona sin internet", r3: "Sin número de teléfono", r4: "Cero metadatos registrados", r5: "Transporte esteganográfico",
      r6: "IA en el dispositivo", r7: "Mapas offline + navegación", r8: "Código abierto y reproducible", r9: "Ningún servidor central convocable",
    },
    trust: {
      eyebrow: "Seguridad en profundidad",
      title_a: "No tienes que confiar en nosotros. ", title_em: "Esa es la idea.",
      t1_h: "La identidad es una clave, no una cuenta", t1_p: "Tu identificador es el SHA-256 de una clave pública generada en el primer arranque. Nada que olvidar, nada que phishar, nada que pedirnos legalmente — nunca lo tuvimos.",
      t2_h: "X25519 + ChaCha20-Poly1305", t2_p: "Cifrado E2E estándar de la industria. Forward secrecy con double-ratchet en cada conversación. Sin backdoor, sin key-escrow, sin copia en claro en nuestra infraestructura.",
      t3_h: "Zero-knowledge por construcción", t3_p: "Los servidores de OSHI ven blobs cifrados y pistas de routing — nunca el claro, nunca el nombre del destinatario.",
      t4_h: "Offline por diseño", t4_p: "Las claves, los mapas, la inferencia IA — todo lo que toca tus datos corre en tu hardware. La nube es opcional, no asumida.",
    },
    usecase: {
      eyebrow: "Construido para los momentos que importan", title: "La mensajería a la que recurres cuando las demás dejan de funcionar.",
      u1_h: "Periodistas en redes hostiles", u1_p: "La protección de fuentes no es una casilla. Los canales encubiertos de OSHI te permiten publicar a un contacto a través de inspección.",
      u2_h: "Viajeros fuera de red", u2_p: "Senderismo en parque nacional, navegación por el Mediterráneo, conducir el Sáhara. Los mapas cargados, el routing local, y el chat de grupo funciona P2P por mesh.",
      u3_h: "Multitudes y manifestaciones", u3_p: "Las redes de operadora fallan. El mesh de OSHI prospera donde hay cuerpos. Salta a través de 1 000 teléfonos para llegar al amigo a tres manzanas.",
      u4_h: "Profesionales conscientes de privacidad", u4_p: "Abogados, médicos, fundadores. Conversaciones que nunca deberían aparecer en una discovery. Cero metadatos no deja rastro que convocar.",
    },
    cta: {
      title: "Prueba la mensajería que no quieren que tengas.",
      sub: "Gratis. Código abierto. Sin número, sin registro. Instálalo en 30 segundos, cifrado en 30 milisegundos.",
      btn_ios: "Descargar para iOS", btn_android: "Descargar para Android", btn_github: "Código en GitHub",
      foot: "La beta de Android abre en julio 2026. <a href=\"#\">Notificarme →</a>",
    },
    footer: {
      tag: "Una mensajería que funciona cuando nada más lo hace.",
      col1_h: "Producto", col1_a: "Funciones", col1_b: "Comparación", col1_c: "API Bot", col1_d: "App iOS",
      col2_h: "Confianza", col2_a: "Política de privacidad", col2_b: "Términos", col2_c: "Licencia", col2_d: "Seguridad",
      col3_h: "Empresa", col3_a: "Sobre", col3_b: "Soporte", col3_c: "Inversores", col3_d: "Contacto",
      copyright: "© 2026 OSHI Messenger. La arquitectura mínima para la máxima privacidad.",
      status: "Todos los sistemas operativos",
    },
  },

  de: {
    nav: { manifesto: "Manifest", features: "Funktionen", compare: "Vergleich", trust: "Vertrauen", cta: "App holen →" },
    hero: {
      eyebrow: "Ende-zu-Ende-verschlüsselt · Mesh · iOS + Android",
      title_a: "Ein Messenger, der funktioniert, ",
      title_em: "wenn nichts anderes funktioniert.",
      sub: "OSHI ist der verschlüsselte Messenger für die Welt nach der Cloud. Keine Telefonnummer. Null Metadaten. Spricht Bluetooth- und Wi-Fi-Mesh, wenn das Internet ausfällt, versteckt Nachrichten in Sichtweite, wenn nicht.",
      cta_ios: "Für iOS laden", cta_android: "Für Android laden", cta_manifesto: "Manifest lesen",
      stat1_label: "Telefonnummern nötig", stat2_label: "verdeckte Kanäle", stat3_label: "Regionen offline", stat4_label: "Sprachen", scroll: "Scrollen",
    },
    manifesto: {
      label: "Unser Manifest",
      title_a: "Privatsphäre ist keine Funktion. ", title_em: "Sie ist das Produkt.",
      p1: "Jeder andere Messenger, den du benutzt hast, protokolliert deine Freunde, erntet deinen Kontaktgraphen und speichert deine Nachrichten auf dem Server eines Fremden in einem Land, das du nie besucht hast. Sie nennen es Sicherheit; wir nennen es Überwachung mit hübschem Icon.",
      p2: "OSHI ist anders konstruiert. Kein zentraler Server hält deine Gespräche. Keine Telefonnummer bindet deine Identität an eine SIM. Keine Cloud-Backups lecken deine Fotos. Deine Nachrichten sind Ende-zu-Ende verschlüsselt mit X25519 + ChaCha20-Poly1305 und werden nirgends entschlüsselt außer auf deinem Gerät.",
      p3: "Wenn das Internet funktioniert, nutzen wir es — aber unsichtbar, mit verdeckten Kanälen, die wie gewöhnliches HTTPS aussehen. Wenn nicht, spricht OSHI Bluetooth LE und Wi-Fi Direct Mesh.",
      p4: "Open Source. Null Metadaten. Keine Telemetrie. Keine Werbung. Die einzige Instanz, die weiß, was du gesagt hast, bist du.",
    },
    features: {
      eyebrow: "Was drin ist", title_a: "Alles, was du von einem Messenger erwartest. ", title_em: "Plus das, was niemand sonst gebaut hat.",
      f1_h: "Bluetooth + Wi-Fi Mesh", f1_p: "Ohne Mobilfunk und Internet funktioniert OSHI weiter. Nachrichten hüpfen durch nahe Geräte, E2E-verschlüsselt auf jeder Strecke.",
      f1_l1: "Multi-Hop-Relay (TTL 7)", f1_l2: "Verschlüsselt auf jedem Hop", f1_l3: "~300 m pro Hop in dichten Netzen", f1_l4: "Sparsam: ~3 % pro Stunde im Standby",
      f2_h: "Null Metadaten", f2_p: "Keine Nummer, keine E-Mail, kein Freundesgraph irgendwo gespeichert. Identität ist ein Schlüsselpaar auf deinem Gerät.",
      f3_h: "Steganographische verdeckte Kanäle", f3_p: "6 Wege, Nachrichten durch Inspektion zu schmuggeln: HTTPS-Klon, Audio-Modem, Bild-LSB, DNS, NTP, ICMP.",
      f4_h: "Offline-Karten für 180+ Regionen", f4_p: "Land einmal laden, ewig navigieren. Tiles, Straßengraph, Adressen und POIs — alles auf dem Gerät. Turn-by-turn ohne ein Byte an Google oder Apple zu senden.",
      f4_b1: "120 GB OSM gehostet", f4_b2: "Suche mit Hausnummer", f4_b3: "A*-Offline-Routing",
      f5_h: "On-Device-KI", f5_p: "Apple Intelligence + Gemini Nano lokal. Smart Replies, Zusammenfassungen, Übersetzung — nie deine Tokens, nie deine Daten.",
      f6_h: "Bot-API", f6_p: "Baue verschlüsselte Bots, die über Mesh sprechen. Webhook-kompatibel. Kein zentraler Server nötig.",
      f7_h: "Open Source", f7_p: "Krypto auditiert, Code auf GitHub, reproduzierbare Builds. Verifiziere alles, was wir sagen.",
    },
    compare: {
      eyebrow: "Wie wir uns vergleichen", title: "Der ehrliche Vergleich.", sub: "Andere Messenger schützen deine Nachrichten. Wir weigern uns zu wissen, dass sie je existiert haben.",
      r1: "Ende-zu-Ende-verschlüsselt", r2: "Funktioniert ohne Internet", r3: "Keine Telefonnummer", r4: "Null Metadaten geloggt", r5: "Steganographischer Transport",
      r6: "On-Device-KI", r7: "Offline-Karten + Navigation", r8: "Open Source & reproduzierbar", r9: "Kein zentraler Server vorladbar",
    },
    trust: {
      eyebrow: "Tiefgreifende Sicherheit", title_a: "Du musst uns nicht vertrauen. ", title_em: "Genau das ist der Punkt.",
      t1_h: "Identität ist ein Schlüssel, kein Konto", t1_p: "Dein Handle ist der SHA-256 eines beim Erststart erzeugten Public Keys. Nichts zu vergessen, nichts zu phishen, nichts uns rechtlich abzuverlangen — wir hatten es nie.",
      t2_h: "X25519 + ChaCha20-Poly1305", t2_p: "Industrie-Standard E2E. Double-Ratchet Forward Secrecy in jeder Konversation. Kein Backdoor, kein Key-Escrow, keine Klartext-Schattenkopie.",
      t3_h: "Zero-Knowledge by Construction", t3_p: "OSHIs Server sehen verschlüsselte Blobs und Routing-Hinweise — nie Klartext, nie den Empfängernamen.",
      t4_h: "Offline by Design", t4_p: "Schlüssel, Karten, KI-Inferenz — alles, was deine Daten berührt, läuft auf deiner Hardware. Die Cloud ist optional, nicht vorausgesetzt.",
    },
    usecase: {
      eyebrow: "Gebaut für die Momente, die zählen", title: "Der Messenger, zu dem du greifst, wenn die anderen aufhören zu funktionieren.",
      u1_h: "Journalisten in feindlichen Netzen", u1_p: "Quellenschutz ist keine Checkbox. OSHIs verdeckte Kanäle lassen dich an einen Kontakt durch Inspektion publizieren.",
      u2_h: "Reisende abseits des Netzes", u2_p: "Wandern im Nationalpark, Segeln im Mittelmeer, Durchquerung der Sahara. Karten geladen, Routing lokal, Gruppenchat P2P über Mesh.",
      u3_h: "Menschenmengen & Demos", u3_p: "Mobilfunknetze versagen. OSHIs Mesh gedeiht, wo Körper sind. Springe durch 1 000 Telefone zum Freund drei Blocks weiter.",
      u4_h: "Datenschutz-bewusste Profis", u4_p: "Anwälte, Ärzte, Gründer. Gespräche, die nie in einer Discovery auftauchen sollten. Null Metadaten — keine Spur zu vorladen.",
    },
    cta: {
      title: "Probier den Messenger, den sie dir nicht geben wollen.",
      sub: "Kostenlos. Open Source. Keine Telefonnummer, keine Anmeldung. Installiert in 30 Sekunden, verschlüsselt in 30 Millisekunden.",
      btn_ios: "Für iOS laden", btn_android: "Für Android laden", btn_github: "Quelle auf GitHub",
      foot: "Android-Beta öffnet im Juli 2026. <a href=\"#\">Benachrichtigen →</a>",
    },
    footer: {
      tag: "Ein Messenger, der funktioniert, wenn nichts anderes funktioniert.",
      col1_h: "Produkt", col1_a: "Funktionen", col1_b: "Vergleich", col1_c: "Bot-API", col1_d: "iOS-App",
      col2_h: "Vertrauen", col2_a: "Datenschutz", col2_b: "AGB", col2_c: "Lizenz", col2_d: "Sicherheit",
      col3_h: "Unternehmen", col3_a: "Über uns", col3_b: "Support", col3_c: "Investoren", col3_d: "Kontakt",
      copyright: "© 2026 OSHI Messenger. Die minimale Architektur für maximale Privatsphäre.",
      status: "Alle Systeme einsatzbereit",
    },
  },

  it: {
    nav: { manifesto: "Manifesto", features: "Funzioni", compare: "Confronto", trust: "Fiducia", cta: "Scarica →" },
    hero: {
      eyebrow: "Cifratura end-to-end · Mesh · iOS + Android",
      title_a: "Una messaggistica che funziona ",
      title_em: "quando nulla altro funziona.",
      sub: "OSHI è la messaggistica cifrata pensata per il mondo dopo il cloud. Niente numero. Zero metadati. Parla mesh Bluetooth e Wi-Fi quando cade internet, nasconde i messaggi in piena vista quando no.",
      cta_ios: "Scarica per iOS", cta_android: "Scarica per Android", cta_manifesto: "Leggi il manifesto",
      stat1_label: "numeri richiesti", stat2_label: "canali nascosti", stat3_label: "regioni offline", stat4_label: "lingue", scroll: "Scorri",
    },
    manifesto: {
      label: "Il nostro manifesto", title_a: "La privacy non è una funzione. ", title_em: "È il prodotto.",
      p1: "Ogni altra messaggistica che hai usato registra i tuoi amici, raccoglie il tuo grafo di contatti, e archivia i tuoi messaggi sul server di qualcun altro in un paese che non hai mai visitato. Loro la chiamano sicurezza; noi la chiamiamo sorveglianza con un'icona carina.",
      p2: "OSHI è diverso per design. Nessun server centrale che tiene le tue conversazioni. Nessun numero che lega la tua identità a una SIM. Nessun backup cloud che fa trapelare le tue foto. I tuoi messaggi sono cifrati E2E con X25519 + ChaCha20-Poly1305 e non vengono mai decifrati altrove che sul tuo dispositivo.",
      p3: "Quando internet funziona, lo usiamo — ma invisibilmente, con canali nascosti che sembrano HTTPS normale. Quando non funziona, OSHI parla mesh Bluetooth LE e Wi-Fi Direct.",
      p4: "Open source. Zero metadati. Niente telemetria. Niente pubblicità. L'unica entità che sa cosa hai detto sei tu.",
    },
    features: {
      eyebrow: "Cosa c'è dentro", title_a: "Tutto ciò che ti aspetti da una messaggistica. ", title_em: "E ciò che nessun altro ha costruito.",
      f1_h: "Mesh Bluetooth + Wi-Fi", f1_p: "Senza segnale e senza internet, OSHI continua a funzionare. I messaggi saltano tra dispositivi vicini, cifrati E2E a ogni tappa.",
      f1_l1: "Relay multi-hop (TTL 7)", f1_l2: "Cifrato a ogni hop", f1_l3: "~300 m per hop in reti dense", f1_l4: "Risparmio: ~3 % all'ora in idle",
      f2_h: "Zero metadati", f2_p: "Niente numero, niente email, nessun grafo di amici archiviato. L'identità è una coppia di chiavi generata sul tuo dispositivo.",
      f3_h: "Canali nascosti steganografici", f3_p: "6 modi per passare messaggi attraverso ispezione: clone HTTPS, modem audio, immagine LSB, DNS, NTP, ICMP.",
      f4_h: "Mappe offline per 180+ regioni", f4_p: "Scarica un paese una volta e naviga per sempre. Tile, grafo stradale, indirizzi e POI — tutto sul dispositivo. Navigazione turn-by-turn senza inviare un byte a Google o Apple.",
      f4_b1: "120 GB di OSM ospitati", f4_b2: "Ricerca con numero civico", f4_b3: "Routing A* offline",
      f5_h: "IA on-device", f5_p: "Apple Intelligence + Gemini Nano localmente. Risposte intelligenti, riassunti, traduzione — mai i tuoi token, mai i tuoi dati.",
      f6_h: "Bot API", f6_p: "Costruisci bot cifrati che parlano via mesh. Webhook-compatibile. Senza server centrale.",
      f7_h: "Open source", f7_p: "Crittografia auditata, codice su GitHub, build riproducibili. Verifica tutto ciò che diciamo nel codice.",
    },
    compare: {
      eyebrow: "Come ci confrontiamo", title: "Il confronto onesto.", sub: "Le altre messaggistiche proteggono i tuoi messaggi. Noi ci rifiutiamo di sapere che sono esistiti.",
      r1: "Cifratura E2E", r2: "Funziona senza internet", r3: "Senza numero", r4: "Zero metadati loggati", r5: "Trasporto steganografico",
      r6: "IA on-device", r7: "Mappe offline + navigazione", r8: "Open source e riproducibile", r9: "Nessun server centrale citabile",
    },
    trust: {
      eyebrow: "Sicurezza in profondità", title_a: "Non devi fidarti di noi. ", title_em: "È proprio questo il punto.",
      t1_h: "L'identità è una chiave, non un account", t1_p: "Il tuo handle è l'impronta SHA-256 di una chiave pubblica generata al primo avvio. Niente da dimenticare, niente da phishare, niente da chiederci legalmente — non l'abbiamo mai avuto.",
      t2_h: "X25519 + ChaCha20-Poly1305", t2_p: "E2E standard del settore. Forward secrecy double-ratchet su ogni conversazione. Nessuna backdoor, nessun key-escrow, nessuna copia in chiaro.",
      t3_h: "Zero-knowledge per costruzione", t3_p: "I server di OSHI vedono blob cifrati e suggerimenti di routing — mai testo in chiaro, mai il nome del destinatario.",
      t4_h: "Offline per design", t4_p: "Chiavi, mappe, inferenza IA — tutto ciò che tocca i tuoi dati gira sul tuo hardware. Il cloud è opzionale, non scontato.",
    },
    usecase: {
      eyebrow: "Costruito per i momenti che contano", title: "La messaggistica a cui ricorri quando le altre smettono di funzionare.",
      u1_h: "Giornalisti in reti ostili", u1_p: "La protezione delle fonti non è un checkbox. I canali nascosti di OSHI ti permettono di pubblicare verso un contatto attraverso l'ispezione.",
      u2_h: "Viaggiatori off-grid", u2_p: "Trekking in un parco nazionale, vela nel Mediterraneo, attraversamento del Sahara. Mappe caricate, routing locale, chat di gruppo P2P via mesh.",
      u3_h: "Folla e manifestazioni", u3_p: "Le reti operatori falliscono. Il mesh di OSHI prospera dove ci sono corpi. Salta tra 1 000 telefoni per raggiungere l'amico tre isolati più in là.",
      u4_h: "Professionisti consapevoli della privacy", u4_p: "Avvocati, medici, fondatori. Conversazioni che non dovrebbero mai apparire in una discovery. Zero metadati non lascia traccia da citare.",
    },
    cta: {
      title: "Prova la messaggistica che non vogliono tu abbia.",
      sub: "Gratis. Open source. Niente numero, niente registrazione. Installato in 30 secondi, cifrato in 30 millisecondi.",
      btn_ios: "Scarica per iOS", btn_android: "Scarica per Android", btn_github: "Codice su GitHub",
      foot: "La beta Android apre a luglio 2026. <a href=\"#\">Avvisami →</a>",
    },
    footer: {
      tag: "Una messaggistica che funziona quando nulla altro funziona.",
      col1_h: "Prodotto", col1_a: "Funzioni", col1_b: "Confronto", col1_c: "Bot API", col1_d: "App iOS",
      col2_h: "Fiducia", col2_a: "Privacy", col2_b: "Termini", col2_c: "Licenza", col2_d: "Sicurezza",
      col3_h: "Azienda", col3_a: "Chi siamo", col3_b: "Supporto", col3_c: "Investitori", col3_d: "Contatti",
      copyright: "© 2026 OSHI Messenger. L'architettura minima per la massima privacy.",
      status: "Tutti i sistemi operativi",
    },
  },

  pt: {
    nav: { manifesto: "Manifesto", features: "Funcionalidades", compare: "Comparação", trust: "Confiança", cta: "Baixar →" },
    hero: {
      eyebrow: "Criptografia ponta-a-ponta · Mesh · iOS + Android",
      title_a: "Um mensageiro que funciona ",
      title_em: "quando nada mais funciona.",
      sub: "OSHI é o mensageiro criptografado pensado para o mundo depois da nuvem. Sem número de telefone. Zero metadados.",
      cta_ios: "Baixar para iOS", cta_android: "Baixar para Android", cta_manifesto: "Ler o manifesto",
      stat1_label: "números necessários", stat2_label: "canais ocultos", stat3_label: "regiões offline", stat4_label: "idiomas", scroll: "Rolar",
    },
    manifesto: {
      label: "Nosso manifesto", title_a: "Privacidade não é um recurso. ", title_em: "É o produto.",
      p1: "Todos os outros mensageiros que você usou registram seus amigos, colhem seu grafo de contatos e guardam suas mensagens no servidor de outra pessoa em um país que você nunca visitou. Eles chamam isso de segurança; nós chamamos de vigilância com um ícone bonito.",
      p2: "OSHI é diferente por design. Nenhum servidor central detém suas conversas. Nenhum número de telefone amarra sua identidade a um SIM. Nenhum backup na nuvem vaza suas fotos. Suas mensagens são criptografadas E2E com X25519 + ChaCha20-Poly1305.",
      p3: "Quando a internet funciona, nós a usamos — mas invisivelmente, com canais ocultos que parecem HTTPS comum. Quando não funciona, OSHI fala mesh Bluetooth LE e Wi-Fi Direct.",
      p4: "Código aberto. Zero metadados. Sem telemetria. Sem anúncios. A única entidade que sabe o que você disse é você.",
    },
    features: {
      eyebrow: "O que tem dentro", title_a: "Tudo que você espera de um mensageiro. ", title_em: "E o que ninguém mais construiu.",
      f1_h: "Mesh Bluetooth + Wi-Fi", f1_p: "Sem sinal de celular e sem internet, OSHI continua funcionando. Mensagens saltam entre dispositivos próximos, criptografadas em cada salto.",
      f1_l1: "Relay multi-salto (TTL 7)", f1_l2: "Criptografado em cada salto", f1_l3: "~300 m por salto em redes densas", f1_l4: "Bateria ~3 % por hora ocioso",
      f2_h: "Zero metadados", f2_p: "Sem telefone, sem email, sem grafo de amigos armazenado. A identidade é um par de chaves no seu dispositivo.",
      f3_h: "Canais ocultos esteganográficos", f3_p: "6 maneiras de passar mensagens por inspeção: clone HTTPS, modem de áudio, imagem LSB, DNS, NTP, ICMP.",
      f4_h: "Mapas offline para 180+ regiões", f4_p: "Baixe um país uma vez e navegue para sempre. Tiles, grafo viário, endereços e POIs — tudo no dispositivo.",
      f4_b1: "120 GB de OSM hospedados", f4_b2: "Busca com número de rua", f4_b3: "Roteamento A* offline",
      f5_h: "IA no dispositivo", f5_p: "Apple Intelligence + Gemini Nano localmente. Respostas inteligentes, resumos, tradução — nunca seus tokens, nunca seus dados.",
      f6_h: "Bot API", f6_p: "Crie bots criptografados que falam via mesh. Compatível com webhook. Sem servidor central.",
      f7_h: "Código aberto", f7_p: "Cripto auditada, código no GitHub, builds reprodutíveis. Verifique tudo o que dizemos na fonte.",
    },
    compare: {
      eyebrow: "Como nos comparamos", title: "A comparação honesta.", sub: "Outros mensageiros protegem suas mensagens. Nós nos recusamos a saber que elas existiram.",
      r1: "Criptografia E2E", r2: "Funciona sem internet", r3: "Sem telefone", r4: "Zero metadados logados", r5: "Transporte esteganográfico",
      r6: "IA no dispositivo", r7: "Mapas offline + navegação", r8: "Código aberto e reprodutível", r9: "Nenhum servidor central intimável",
    },
    trust: {
      eyebrow: "Segurança em profundidade", title_a: "Você não precisa confiar em nós. ", title_em: "Esse é o ponto.",
      t1_h: "Identidade é uma chave, não uma conta", t1_p: "Seu identificador é o SHA-256 de uma chave pública gerada na primeira inicialização. Nada para esquecer, nada para phishing, nada para nos exigir legalmente — nunca tivemos.",
      t2_h: "X25519 + ChaCha20-Poly1305", t2_p: "E2E padrão da indústria. Forward secrecy double-ratchet em cada conversa.",
      t3_h: "Zero-knowledge por construção", t3_p: "Servidores do OSHI veem blobs criptografados e dicas de roteamento — nunca texto claro, nunca o nome do destinatário.",
      t4_h: "Offline por design", t4_p: "Chaves, mapas, inferência de IA — tudo que toca seus dados roda no seu hardware. A nuvem é opcional, não pressuposta.",
    },
    usecase: {
      eyebrow: "Construído para os momentos que importam", title: "O mensageiro que você usa quando os outros param de funcionar.",
      u1_h: "Jornalistas em redes hostis", u1_p: "Proteção de fontes não é um checkbox. Canais ocultos do OSHI permitem publicar para um contato através da inspeção.",
      u2_h: "Viajantes fora da rede", u2_p: "Trilha em parque nacional, vela no Mediterrâneo, atravessando o Saara. Mapas carregados, roteamento local, chat de grupo P2P via mesh.",
      u3_h: "Multidões e manifestações", u3_p: "Redes operadoras falham. O mesh do OSHI prospera onde há corpos. Salte por 1 000 telefones para alcançar o amigo a três quadras.",
      u4_h: "Profissionais conscientes de privacidade", u4_p: "Advogados, médicos, fundadores. Conversas que nunca deveriam aparecer em uma discovery.",
    },
    cta: {
      title: "Experimente o mensageiro que eles não querem que você tenha.",
      sub: "Grátis. Código aberto. Sem telefone, sem cadastro. Instalado em 30 segundos, criptografado em 30 milissegundos.",
      btn_ios: "Baixar para iOS", btn_android: "Baixar para Android", btn_github: "Código no GitHub",
      foot: "A beta Android abre em julho 2026. <a href=\"#\">Me avise →</a>",
    },
    footer: {
      tag: "Um mensageiro que funciona quando nada mais funciona.",
      col1_h: "Produto", col1_a: "Funcionalidades", col1_b: "Comparação", col1_c: "Bot API", col1_d: "App iOS",
      col2_h: "Confiança", col2_a: "Privacidade", col2_b: "Termos", col2_c: "Licença", col2_d: "Segurança",
      col3_h: "Empresa", col3_a: "Sobre", col3_b: "Suporte", col3_c: "Investidores", col3_d: "Contato",
      copyright: "© 2026 OSHI Messenger. A arquitetura mínima para a máxima privacidade.",
      status: "Todos os sistemas operacionais",
    },
  },

  zh: {
    nav: { manifesto: "宣言", features: "功能", compare: "对比", trust: "信任", cta: "下载 →" },
    hero: {
      eyebrow: "端到端加密 · Mesh · iOS + Android",
      title_a: "一款在",
      title_em: "其他一切都不行时仍然能用的即时通讯。",
      sub: "OSHI 是为云之后的世界打造的加密即时通讯。不要电话号码。零元数据。互联网中断时通过蓝牙和 Wi-Fi 网状网络通信,在线时把消息隐藏在普通流量中。",
      cta_ios: "下载 iOS 版", cta_android: "下载 Android 版", cta_manifesto: "阅读宣言",
      stat1_label: "需要的电话号码", stat2_label: "隐蔽信道", stat3_label: "可离线地区", stat4_label: "种语言", scroll: "向下滚动",
    },
    manifesto: {
      label: "我们的宣言", title_a: "隐私不是功能。", title_em: "它就是产品。",
      p1: "你用过的每一款其他即时通讯都会记录你的朋友、收集你的联系人图谱,并把你的消息存储在你从未访问过的国家的别人的服务器上。他们称之为安全;我们称之为带漂亮图标的监控。",
      p2: "OSHI 在设计上就不同。没有中心服务器持有你的对话。没有电话号码把你的身份绑定到 SIM 卡。没有云备份泄露你的照片。你的消息使用 X25519 + ChaCha20-Poly1305 端到端加密,除你的设备外永不解密。",
      p3: "互联网可用时,我们使用它——但是隐形地,通过看起来像普通 HTTPS 的隐蔽信道。不可用时,OSHI 通过蓝牙 LE 和 Wi-Fi Direct 网状网络通信。",
      p4: "开源。零元数据。无遥测。无广告。唯一知道你说了什么的人,是你自己。",
    },
    features: {
      eyebrow: "里面有什么", title_a: "即时通讯应有的一切。", title_em: "加上别人没造的部分。",
      f1_h: "蓝牙 + Wi-Fi 网状网络", f1_p: "没有信号、没有互联网,OSHI 仍然工作。消息在附近设备间跳转,每一段都是端到端加密。",
      f1_l1: "多跳中继 (TTL 7)", f1_l2: "每跳都加密", f1_l3: "密集网络中每跳约 300 米", f1_l4: "省电:闲置约每小时 3%",
      f2_h: "零元数据", f2_p: "没有电话、没有邮箱、没有任何地方存储朋友图谱。身份是你设备上生成的密钥对。",
      f3_h: "隐写式隐蔽信道", f3_p: "6 种方式让消息穿过审查:HTTPS 仿冒、音频调制解调器、图像 LSB、DNS、NTP、ICMP。",
      f4_h: "180+ 地区离线地图", f4_p: "下载一次,永久导航。瓦片、道路图、地址和兴趣点——全部存储在设备上。逐路口导航,不向 Google 或 Apple 发送一个字节。",
      f4_b1: "托管 120 GB OSM 数据", f4_b2: "门牌号感知搜索", f4_b3: "A* 离线路径规划",
      f5_h: "设备端 AI", f5_p: "本地 Apple Intelligence + Gemini Nano。智能回复、摘要、翻译——永不发送你的 token 或数据。",
      f6_h: "Bot API", f6_p: "构建通过 mesh 通信的加密机器人。兼容 webhook。无需中心服务器。",
      f7_h: "开源", f7_p: "加密已审计,代码在 GitHub 上,构建可复现。源码可以验证我们说的一切。",
    },
    compare: {
      eyebrow: "我们如何比较", title: "诚实的对比。", sub: "其他即时通讯保护你的消息。我们拒绝知道它们曾经存在。",
      r1: "端到端加密", r2: "无需互联网即可工作", r3: "无需电话号码", r4: "零元数据记录", r5: "隐写式传输",
      r6: "设备端 AI", r7: "离线地图 + 导航", r8: "开源且可复现", r9: "无中心服务器可被传唤",
    },
    trust: {
      eyebrow: "纵深安全", title_a: "你不需要信任我们。", title_em: "这就是重点。",
      t1_h: "身份是密钥,不是账户", t1_p: "你的标识是首次启动时生成的公钥的 SHA-256 指纹。无需记住、无法钓鱼、无法依法要求我们交出——我们从未拥有。",
      t2_h: "X25519 + ChaCha20-Poly1305", t2_p: "行业标准 E2E。每个对话都有双棘轮前向保密。无后门、无密钥托管、我们的基础设施上无明文影子副本。",
      t3_h: "构造上的零知识", t3_p: "OSHI 的服务器看到的是加密的二进制块和路由提示——从不是明文,从不是收件人姓名。",
      t4_h: "设计上离线", t4_p: "解密密钥、地图、AI 推理——所有接触你数据的东西都在你的硬件上运行。云是可选的,不是预设。",
    },
    usecase: {
      eyebrow: "为重要时刻而造", title: "当其他停止工作时,你会拿出来的即时通讯。",
      u1_h: "在敌对网络中的记者", u1_p: "消息源保护不是复选框。OSHI 的隐蔽信道让你在审查下向联系人发布。",
      u2_h: "网外旅行者", u2_p: "在国家公园徒步、在地中海航行、穿越撒哈拉。地图已加载,路径规划本地化,群聊通过 mesh 点对点工作。",
      u3_h: "人群与示威", u3_p: "运营商网络失效。OSHI 的 mesh 在有人体的地方繁荣。穿越 1 000 部手机,联系到三个街区外的朋友。",
      u4_h: "注重隐私的专业人士", u4_p: "律师、医生、创始人。永远不该出现在调查请求中的对话。零元数据不留下可传唤的纸质痕迹。",
    },
    cta: {
      title: "试试他们不想让你拥有的即时通讯。",
      sub: "免费。开源。无需电话号码,无需注册。30 秒安装,30 毫秒加密。",
      btn_ios: "下载 iOS 版", btn_android: "下载 Android 版", btn_github: "GitHub 上的源代码",
      foot: "Android 测试版将于 2026 年 7 月开放。<a href=\"#\">通知我 →</a>",
    },
    footer: {
      tag: "一款在其他一切都不行时仍然能用的即时通讯。",
      col1_h: "产品", col1_a: "功能", col1_b: "对比", col1_c: "Bot API", col1_d: "iOS 应用",
      col2_h: "信任", col2_a: "隐私政策", col2_b: "条款", col2_c: "许可证", col2_d: "安全",
      col3_h: "公司", col3_a: "关于", col3_b: "支持", col3_c: "投资者", col3_d: "联系",
      copyright: "© 2026 OSHI Messenger。最少架构,最大隐私。",
      status: "所有系统正常运行",
    },
  },
};

const LANG_LIST = [
  { code: 'en', label: 'English' },
  { code: 'fr', label: 'Français' },
  { code: 'es', label: 'Español' },
  { code: 'de', label: 'Deutsch' },
  { code: 'it', label: 'Italiano' },
  { code: 'pt', label: 'Português' },
  { code: 'zh', label: '中文' },
];

function pickInitialLang() {
  const stored = localStorage.getItem('oshi-lang');
  if (stored && TRANSLATIONS[stored]) return stored;
  const nav = (navigator.language || 'en').slice(0, 2).toLowerCase();
  return TRANSLATIONS[nav] ? nav : 'en';
}

function getPath(obj, path) {
  return path.split('.').reduce((o, k) => (o && o[k] != null ? o[k] : null), obj);
}

function applyLang(lang) {
  const dict = TRANSLATIONS[lang] || TRANSLATIONS.en;
  document.documentElement.lang = lang;
  document.querySelectorAll('[data-i18n]').forEach(el => {
    const path = el.getAttribute('data-i18n');
    const v = getPath(dict, path);
    if (v == null) return;
    if (el.hasAttribute('data-i18n-html')) el.innerHTML = v;
    else el.textContent = v;
  });
  localStorage.setItem('oshi-lang', lang);
  // Reflect in the picker
  document.querySelectorAll('.lang-current').forEach(el => {
    const found = LANG_LIST.find(l => l.code === lang);
    if (found) el.textContent = found.code.toUpperCase();
  });
}

function buildLangPicker() {
  const root = document.getElementById('langPicker');
  if (!root) return;
  const current = pickInitialLang();

  const btn = document.createElement('button');
  btn.className = 'lang-btn';
  btn.type = 'button';
  btn.setAttribute('aria-label', 'Language');
  btn.innerHTML = `
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"/></svg>
    <span class="lang-current">${current.toUpperCase()}</span>
    <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M6 9l6 6 6-6"/></svg>`;
  const menu = document.createElement('div');
  menu.className = 'lang-menu';
  LANG_LIST.forEach(l => {
    const item = document.createElement('button');
    item.type = 'button';
    item.className = 'lang-item' + (l.code === current ? ' active' : '');
    item.textContent = l.label;
    item.addEventListener('click', () => {
      applyLang(l.code);
      root.classList.remove('open');
      // Highlight
      menu.querySelectorAll('.lang-item').forEach(i => i.classList.toggle('active', i.textContent === l.label));
    });
    menu.appendChild(item);
  });
  btn.addEventListener('click', e => {
    e.stopPropagation();
    root.classList.toggle('open');
  });
  document.addEventListener('click', e => {
    if (!root.contains(e.target)) root.classList.remove('open');
  });
  root.appendChild(btn);
  root.appendChild(menu);
}

// Boot
document.addEventListener('DOMContentLoaded', () => {
  buildLangPicker();
  applyLang(pickInitialLang());
});
