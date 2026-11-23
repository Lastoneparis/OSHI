//
//  OnboardingView.swift
//  OSHI - First Time User Guide
//
//  Shows comprehensive guide when app is first installed
//

import SwiftUI

// MARK: - Brand Color
private let appBrandColor = Theme.brand

struct OnboardingView: View {
    @AppStorage("hasSeenOnboarding") private var hasSeenOnboarding = false
    @Binding var isPresented: Bool
    #if DEBUG
    // DEBUG-only: `-onboardingStartPage N` launch arg opens onboarding on page N,
    // so each redesigned page can be screenshotted headlessly (no UI tapping).
    @State private var currentPage = max(0, min(7, UserDefaults.standard.integer(forKey: "onboardingStartPage")))
    #else
    @State private var currentPage = 0
    #endif
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    
    // Device detection - works for iPhone, iPad, and macOS
    private var isCompactDevice: Bool {
        // iPhone in portrait or compact width
        horizontalSizeClass == .compact
    }
    
    private var isLargeScreen: Bool {
        // iPad or macOS (regular width)
        #if targetEnvironment(macCatalyst)
        return true  // Always large on Mac
        #else
        return horizontalSizeClass == .regular || UIDevice.current.userInterfaceIdiom == .pad
        #endif
    }
    
    private var maxContentWidth: CGFloat {
        isLargeScreen ? 600 : .infinity
    }
    
    private var indicatorSize: CGFloat {
        isLargeScreen ? 12 : 8
    }
    
    private var indicatorSpacing: CGFloat {
        isLargeScreen ? 12 : 8
    }
    
    private var topPadding: CGFloat {
        isLargeScreen ? 40 : 60
    }

    /// Number of pages in the guide.
    ///
    /// One constant so the indicator dots, the `TabView` tags and the Mac
    /// Back/Next bounds can never disagree about where the guide ends.
    private static let pageCount = 8

    var body: some View {
        GeometryReader { geometry in
            #if targetEnvironment(macCatalyst)
            // Mac (Catalyst): the content column is sized to the window and the
            // artwork is a BACKGROUND, which by definition cannot change the
            // layout of what it sits behind.
            //
            // It had to. Measured on a real Mac: `Image.aspectRatio(.fill)`
            // reports the size it needs to COVER what it is offered, and a
            // ZStack takes the union of its children, so on a wide Mac window
            // this portrait artwork inflated the guide's container to ~2000 pt
            // inside a ~1200 pt window. Everything below the middle of the
            // guide — the welcome page's own feature badges, and the Back/Next
            // bar — was laid out past the bottom edge of the window: present in
            // the accessibility tree at y≈1655 against a window bottom of 1097,
            // invisible and unclickable on screen.
            contentColumn(geometry: geometry)
                .frame(width: geometry.size.width, height: geometry.size.height)
                .background { backgroundLayers }
            #else
            // 🔴 __ARTWORK_MUST_NOT_SIZE_THE_PAGE_2026_08_19__ App Review, guideline 4.
            //
            // iOS used to be the `ZStack` below, and it had EXACTLY the bug the
            // Catalyst branch above spent a paragraph explaining — we simply had no
            // device wide enough to show it. On an iPhone 17 Pro Max the portrait
            // artwork's `.aspectRatio(.fill)` reports a width larger than the
            // screen, the ZStack takes the UNION of its children, and the content
            // column is then centred inside a container wider than the display: the
            // title rendered as "…ue su", the subtitle as "e, Privé", and all three
            // feature cards ran off the right edge. Reproduced on a 17 Pro Max
            // simulator, and photographed on the real device.
            //
            // A background CANNOT change the layout of what sits in front of it.
            // That is the whole fix, and it is the same one already proven on Mac.
            contentColumn(geometry: geometry)
                .frame(width: geometry.size.width, height: geometry.size.height)
                .background { backgroundLayers }
            #endif
        }
        #if !targetEnvironment(macCatalyst)
        // iOS/iPadOS: unchanged — the guide bleeds to the screen edges.
        .ignoresSafeArea()
        #else
        // Mac (Catalyst): deliberately NOT ignored. The content must stay inside
        // the window; `backgroundLayers` still bleeds under the title bar on its
        // own, so the wash is unbroken either way.
        #endif
    }

    /// Background: brand wash, then the mesh artwork over it.
    ///
    /// The artwork is GENERATED (tools/… — see OnboardingArt in the asset
    /// catalogue), not a stock download, so there is no licence to honour and no
    /// provenance to reconstruct in a year.
    ///
    /// It sits UNDER every word on these pages, so it is deliberately quiet: the
    /// gradient still carries the colour, and the mesh is held back far enough
    /// that it reads as texture. If you notice it before you read the text, it is
    /// turned up too high.
    @ViewBuilder
    private var backgroundLayers: some View {
        LinearGradient(
            colors: [
                Color(red: 0.02, green: 0.08, blue: 0.12),
                Color(red: 0.05, green: 0.15, blue: 0.25)
            ],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
        .ignoresSafeArea()

        Image("OnboardingArt")
            .resizable()
            .aspectRatio(contentMode: .fill)
            .opacity(0.55)
            .ignoresSafeArea()
            .allowsHitTesting(false)
            // Decorative: VoiceOver must not stop on it before the copy.
            .accessibilityHidden(true)
    }

    /// Dots, the current page, and — on the Mac — the Back/Next bar.
    private func contentColumn(geometry: GeometryProxy) -> some View {
        VStack(spacing: 0) {
            // ✅ Adaptive page indicators
            pageIndicators
                .frame(maxWidth: .infinity)
                .padding(.top, geometry.safeAreaInsets.top + topPadding)
                .padding(.bottom, 16)

            // Content - centered and constrained on large screens
            pageContent
                .frame(maxWidth: isLargeScreen ? 700 : .infinity)
                // Cap Dynamic Type so the fixed-size onboarding copy still
                // scales for low-vision users without overflowing the
                // gradient pages on the smallest iPhone.
                .dynamicTypeSize(...DynamicTypeSize.accessibility2)

            #if targetEnvironment(macCatalyst)
            // The Mac's page turn. See MacPageNavigationBar for why the
            // swipe pager alone leaves this guide stuck on page 1 here.
            MacPageNavigationBar(currentPage: $currentPage,
                                 pageCount: Self.pageCount)
                .frame(maxWidth: 700)
                .dynamicTypeSize(...DynamicTypeSize.accessibility2)
            #endif
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Paging

    /// The page at `index`.
    ///
    /// Single source of truth: the swipe pager (iOS/iPadOS) and the button pager
    /// (Mac Catalyst) both build their pages from here, so a page added to one
    /// can never go missing from the other.
    @ViewBuilder
    private func page(at index: Int) -> some View {
        switch index {
        case 0:
            WelcomePage(isIPad: isLargeScreen, maxWidth: maxContentWidth)
        case 1:
            IdentityPage(isIPad: isLargeScreen, maxWidth: maxContentWidth)
        case 2:
            SendMessagePage(isIPad: isLargeScreen, maxWidth: maxContentWidth)
        case 3:
            // How OSHI reaches people: online vs offline mesh + ranges.
            ConnectivityPage(isIPad: isLargeScreen, maxWidth: maxContentWidth)
        case 4:
            FeaturesPage(isIPad: isLargeScreen, maxWidth: maxContentWidth)
        case 5:
            GoodConductPage(isIPad: isLargeScreen, maxWidth: maxContentWidth)
        case 6:
            DisclaimerPage(isIPad: isLargeScreen, maxWidth: maxContentWidth)
        default:
            GetStartedPage(isPresented: $isPresented,
                           hasSeenOnboarding: $hasSeenOnboarding,
                           isIPad: isLargeScreen,
                           maxWidth: maxContentWidth)
        }
    }

    /// The pager itself, which is NOT the same control on both platforms.
    ///
    /// iOS/iPadOS keeps the paging `TabView` — the swipe is the interaction the
    /// design is built around and nothing here changes it.
    ///
    /// Mac Catalyst does not: `PageTabViewStyle` is backed by a
    /// `UIPageViewController` whose only page-turn is a swipe, and a Mac window
    /// has none — a mouse cannot swipe at all, and the style does not paginate
    /// from a trackpad scroll under Catalyst either. The result on a real Mac
    /// was a guide that opened on page 1 and stayed there forever, with the
    /// "Start Using OSHI" button on page 8 unreachable, so a first-install user
    /// could not even dismiss it. Catalyst therefore renders one page at a time
    /// and turns it from the buttons/dots/arrow keys instead of asking the
    /// window for a gesture it cannot make.
    @ViewBuilder
    private var pageContent: some View {
        #if targetEnvironment(macCatalyst)
        ZStack {
            page(at: currentPage)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                // New identity per page so each page's `onAppear` animations
                // run when it is turned to, as they do after a swipe on iOS.
                .id(currentPage)
                .transition(.opacity)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        #else
        TabView(selection: $currentPage) {
            page(at: 0).tag(0)
            page(at: 1).tag(1)
            page(at: 2).tag(2)
            page(at: 3).tag(3)
            page(at: 4).tag(4)
            page(at: 5).tag(5)
            page(at: 6).tag(6)
            page(at: 7).tag(7)
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        #endif
    }

    /// The row of dots. On the Mac they are also the page-turn control, so each
    /// one is a real button with a click target far larger than the dot.
    @ViewBuilder
    private var pageIndicators: some View {
        #if targetEnvironment(macCatalyst)
        // Children are buttons here — collapsing them would hide the control.
        indicatorDots
        #else
        indicatorDots
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(String(format: "onboarding.page.indicator".localized,
                                       currentPage + 1, Self.pageCount))
        #endif
    }

    private var indicatorDots: some View {
        HStack(spacing: indicatorSpacing) {
            ForEach(0..<Self.pageCount, id: \.self) { index in
                #if targetEnvironment(macCatalyst)
                Button {
                    withAnimation(.easeInOut(duration: 0.25)) { currentPage = index }
                } label: {
                    indicatorDot(at: index)
                        // A 12 pt dot is not a mouse target. The dot stays 12 pt;
                        // the clickable area around it does not.
                        .padding(8)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(format: "onboarding.page.indicator".localized,
                                           index + 1, Self.pageCount))
                #else
                indicatorDot(at: index)
                #endif
            }
        }
    }

    private func indicatorDot(at index: Int) -> some View {
        Circle()
            .fill(currentPage == index ? appBrandColor : Color.gray.opacity(0.3))
            .frame(width: indicatorSize, height: indicatorSize)
            .scaleEffect(currentPage == index ? 1.2 : 1.0)
            .animation(.spring(response: 0.3), value: currentPage)
    }
}

#if targetEnvironment(macCatalyst)
// MARK: - Mac (Catalyst) page navigation

/// Visible Back / Next controls for the onboarding guide, Mac Catalyst only.
///
/// Gated on `targetEnvironment(macCatalyst)` rather than `os(macOS)`: this app
/// is a Catalyst build, so `os(macOS)` is FALSE in it (Catalyst compiles as
/// `os(iOS)`) and an `#if os(macOS)` here would compile to nothing and ship the
/// same stuck guide.
///
/// Strings are the existing `onboarding.next` / `onboarding.previous` keys —
/// both already translated in all 34 `.lproj`s and, until now, unused.
private struct MacPageNavigationBar: View {
    @Binding var currentPage: Int
    let pageCount: Int

    private var isFirst: Bool { currentPage <= 0 }
    private var isLast: Bool { currentPage >= pageCount - 1 }

    var body: some View {
        HStack(spacing: 16) {
            Button { turn(by: -1) } label: {
                HStack(spacing: 8) {
                    Image(systemName: "chevron.left")
                    Text("onboarding.previous".localized)
                }
                .modifier(MacNavButtonChrome(prominent: false))
            }
            .buttonStyle(.plain)
            .keyboardShortcut(.leftArrow, modifiers: [])
            .disabled(isFirst)
            .opacity(isFirst ? 0.35 : 1)

            Spacer(minLength: 12)

            Button { turn(by: 1) } label: {
                HStack(spacing: 8) {
                    Text("onboarding.next".localized)
                    Image(systemName: "chevron.right")
                }
                .modifier(MacNavButtonChrome(prominent: true))
            }
            .buttonStyle(.plain)
            .keyboardShortcut(.rightArrow, modifiers: [])
            // The last page carries its own "Start Using OSHI" button; Next has
            // nowhere left to go, and is dimmed rather than removed so the bar
            // does not reflow under the pointer.
            .disabled(isLast)
            .opacity(isLast ? 0.35 : 1)
        }
        .padding(.horizontal, 30)
        .padding(.top, 8)
        .padding(.bottom, 24)
    }

    private func turn(by delta: Int) {
        let target = min(max(currentPage + delta, 0), pageCount - 1)
        guard target != currentPage else { return }
        withAnimation(.easeInOut(duration: 0.25)) { currentPage = target }
    }
}

/// Shared chrome for the two Mac page buttons.
///
/// No fixed font size (`Font.scaled` keeps Dynamic Type working) and no fixed
/// height — `minHeight` gives the 44 pt pointer target at the default size and
/// lets the button grow instead of clipping its label at accessibility sizes.
private struct MacNavButtonChrome: ViewModifier {
    let prominent: Bool

    func body(content: Content) -> some View {
        content
            .font(.scaled(16, weight: .semibold))
            .lineLimit(1)
            .minimumScaleFactor(0.7)
            // White reads 7.7:1 on Theme.brand and better still on the dark
            // wash behind the secondary button. The onboarding gradient is the
            // same in light and dark appearance, so one ink colour is correct
            // in both.
            .foregroundColor(.white)
            .padding(.horizontal, 24)
            .padding(.vertical, 12)
            .frame(minHeight: 44)
            .background {
                if prominent {
                    Capsule().fill(
                        LinearGradient(colors: [Theme.brand, Theme.brand.opacity(0.8)],
                                       startPoint: .leading, endPoint: .trailing)
                    )
                } else {
                    Capsule().fill(Color.white.opacity(0.14))
                }
            }
            .overlay(
                Capsule().stroke(Color.white.opacity(prominent ? 0.0 : 0.28), lineWidth: 1)
            )
            .contentShape(Capsule())
    }
}
#endif

// MARK: - Page 1: Welcome
struct WelcomePage: View {
    var isIPad: Bool = false
    var maxWidth: CGFloat = .infinity
    
    var body: some View {
        VStack(spacing: isIPad ? 36 : 26) {
            Spacer()

            // Interactive, royalty-free hero (procedural — tap it, it responds).
            VStack(spacing: 20) {
                PulseHero(systemName: "lock.shield.fill", size: isIPad ? 118 : 94)

                Text("onboarding_welcome_title".localized)
                    .font(.system(size: isIPad ? 42 : 34, weight: .bold))
                    .foregroundColor(.white)
                    .multilineTextAlignment(.center)

                Text("onboarding_welcome_subtitle".localized)
                    .font(.system(size: isIPad ? 22 : 18))
                    .foregroundColor(.white.opacity(0.8))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 40)
            }
            .frame(maxWidth: maxWidth)

            Spacer()

            VStack(spacing: 15) {
                FeatureBadge(icon: "lock.fill", text: "onboarding_feature_encrypted".localized)
                FeatureBadge(icon: "antenna.radiowaves.left.and.right", text: "onboarding_feature_mesh".localized)
                // Truthful capability: large files, and uploads resume where they
                // left off (backed by the resumable chunked uploader).
                FeatureBadge(icon: "arrow.up.doc.fill", text: "onboarding_feature_bigfiles".localized)
            }
            .padding(.horizontal, 30)

            Spacer()
        }
        .padding()
    }
}

// MARK: - Page 2: Your Identity
struct IdentityPage: View {
    var isIPad: Bool = false
    var maxWidth: CGFloat = .infinity
    
    var body: some View {
        VStack(spacing: isIPad ? 30 : 24) {
            Spacer()

            // Animated QR card with a sweeping scan line — "your identity is a
            // code you share", shown instead of explained.
            QRScanHero(size: isIPad ? 220 : 176)

            Text("onboarding_identity_title".localized)
                .font(.system(size: isIPad ? 34 : 28, weight: .bold))
                .foregroundColor(.white)
                .multilineTextAlignment(.center)

            Text("onboarding_identity_description".localized)
                .font(.system(size: isIPad ? 20 : 16))
                .foregroundColor(.white.opacity(0.8))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)

            // One clear pointer to where the code lives — no numbered steps.
            Label("onboarding_qr_location".localized, systemImage: "gearshape.fill")
                .font(.system(size: isIPad ? 16 : 14, weight: .semibold))
                // __PILL_CONTRAST_2026_08_22__ voir LocationSharingView. Celle-ci
                // est vue au PREMIER lancement: c'est le plus mauvais endroit où
                // laisser un texte que la moitié des utilisateurs ne lit pas.
                .foregroundColor(.primary)
                .padding(.horizontal, 18)
                .padding(.vertical, 10)
                .background(Capsule().fill(appBrandColor.opacity(0.22)))

            Spacer()
        }
        .padding()
        .frame(maxWidth: maxWidth)
        .frame(maxWidth: .infinity)
    }
}

// MARK: - Page 3: Send First Message
struct SendMessagePage: View {
    var isIPad: Bool = false
    var maxWidth: CGFloat = .infinity
    
    var body: some View {
        VStack(spacing: isIPad ? 30 : 24) {
            Spacer()

            // Kinetic "send" hero — floating plane with a dashed motion trail.
            PlaneHero(size: isIPad ? 116 : 92)

            Text("onboarding_message_title".localized)
                .font(.system(size: isIPad ? 34 : 28, weight: .bold))
                .foregroundColor(.white)
                .multilineTextAlignment(.center)

            Text("onboarding_message_description".localized)
                .font(.system(size: isIPad ? 20 : 16))
                .foregroundColor(.white.opacity(0.8))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)

            // Two simple ways to add someone — icon + label, tappable. No steps.
            HStack(spacing: 16) {
                MethodChip(icon: "qrcode.viewfinder", title: "onboarding_method_qr_title".localized)
                MethodChip(icon: "doc.on.clipboard", title: "onboarding_method_paste_title".localized)
            }
            .frame(maxWidth: 420)
            .padding(.horizontal, 24)

            Spacer()
        }
        .padding()
        .frame(maxWidth: maxWidth)
        .frame(maxWidth: .infinity)
    }
}

// MARK: - Page: How OSHI reaches people (online + offline mesh ranges)

/// Noob-friendly explanation that OSHI works two ways — over the internet
/// (online) and directly device-to-device with no internet (offline mesh) —
/// plus the three offline ranges. Uses an animated concentric-range diagram so
/// the distances are felt, not just read.
struct ConnectivityPage: View {
    var isIPad: Bool = false
    var maxWidth: CGFloat = .infinity
    @State private var animate = false

    var body: some View {
        ScrollView {
            VStack(spacing: isIPad ? 28 : 20) {
                Text("onboarding.reach.title".localized)
                    .font(.system(size: isIPad ? 34 : 27, weight: .bold))
                    .foregroundColor(.white)
                    .multilineTextAlignment(.center)
                    .padding(.top, isIPad ? 20 : 8)

                Text("onboarding.reach.subtitle".localized)
                    .font(.system(size: isIPad ? 19 : 16))
                    .foregroundColor(.white.opacity(0.8))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 28)

                // Two simple modes.
                VStack(spacing: 12) {
                    ReachModeRow(icon: "globe", tint: .cyan,
                                 title: "onboarding.reach.online.title".localized,
                                 detail: "onboarding.reach.online.body".localized)
                    ReachModeRow(icon: "wifi.slash", tint: .orange,
                                 title: "onboarding.reach.offline.title".localized,
                                 detail: "onboarding.reach.offline.body".localized)
                }
                .padding(.horizontal, 22)

                // Animated range diagram.
                RangeDiagram(animate: animate)
                    .frame(height: isIPad ? 220 : 190)
                    .padding(.vertical, 4)

                // The three offline ranges.
                VStack(spacing: 10) {
                    RangeRow(icon: "iphone.gen3", color: .green,
                             distance: "onboarding.reach.range1.dist".localized,
                             label: "onboarding.reach.range1.label".localized)
                    RangeRow(icon: "point.3.connected.trianglepath.dotted", color: .yellow,
                             distance: "onboarding.reach.range2.dist".localized,
                             label: "onboarding.reach.range2.label".localized)
                    RangeRow(icon: "antenna.radiowaves.left.and.right", color: .orange,
                             distance: "onboarding.reach.range3.dist".localized,
                             label: "onboarding.reach.range3.label".localized)
                }
                .padding(.horizontal, 22)

                // The one thing to remember.
                HStack(spacing: 10) {
                    Image(systemName: "lightbulb.fill").foregroundColor(.yellow)
                    Text("onboarding.reach.tip".localized)
                        .font(.system(size: isIPad ? 16 : 14, weight: .medium))
                        .foregroundColor(.white.opacity(0.9))
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(14)
                .background(RoundedRectangle(cornerRadius: 14).fill(Color.white.opacity(0.08)))
                .padding(.horizontal, 22)

                // The limits, stated before the user relies on the feature. The range rows
                // above read as capabilities; without this a user assumes offline delivery is
                // always available. It is not: both devices must have OSHI in the foreground
                // (the built plist has no bluetooth-central/bluetooth-peripheral background
                // mode, and MultipeerConnectivity stops advertising/browsing on resign-active,
                // MeshNetworkManager.swift:618-621), the direct hop is ~100 m line of sight and
                // far less through walls, and with nobody in range the message silently takes
                // the internet path instead.
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundColor(.orange)
                    Text("onboarding.reach.disclaimer".localized)
                        .font(.system(size: isIPad ? 15 : 13))
                        .foregroundColor(.white.opacity(0.75))
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(14)
                .background(RoundedRectangle(cornerRadius: 14).fill(Color.orange.opacity(0.10)))
                .padding(.horizontal, 22)
                .padding(.bottom, 30)
            }
            .frame(maxWidth: maxWidth)
            .frame(maxWidth: .infinity)
        }
        .onAppear { withAnimation(.easeOut(duration: 1.2)) { animate = true } }
    }
}

/// One "mode" row (online / offline) with an icon, title and one-line body.
private struct ReachModeRow: View {
    let icon: String, tint: Color, title: String, detail: String
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 22, weight: .semibold))
                .foregroundColor(tint)
                .frame(width: 34)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.scaled(16, weight: .bold)).foregroundColor(.white)
                Text(detail).font(.scaled(14)).foregroundColor(.white.opacity(0.75))
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
    }
}

/// One offline range row: icon, big distance, plain-language label.
private struct RangeRow: View {
    let icon: String, color: Color, distance: String, label: String
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 20, weight: .semibold))
                .foregroundColor(color)
                .frame(width: 30)
            Text(distance)
                .font(.scaled(17, weight: .bold, design: .rounded))
                .foregroundColor(.white)
                .frame(width: 78, alignment: .leading)
            Text(label)
                .font(.scaled(14))
                .foregroundColor(.white.opacity(0.8))
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
    }
}

/// Concentric rings that expand on appear, with a phone at the centre and range
/// icons on each ring — a "felt" sense of 100 m → 500 m → 30 km.
private struct RangeDiagram: View {
    let animate: Bool
    var body: some View {
        GeometryReader { geo in
            let side = min(geo.size.width, geo.size.height)
            ZStack {
                ForEach(Array([1.0, 0.66, 0.36].enumerated()), id: \.offset) { i, frac in
                    Circle()
                        .stroke(
                            [Color.orange, Color.yellow, Color.green][i].opacity(0.55),
                            style: StrokeStyle(lineWidth: 1.5, dash: [4, 4])
                        )
                        .frame(width: side * frac, height: side * frac)
                        .scaleEffect(animate ? 1 : 0.2)
                        .opacity(animate ? 1 : 0)
                        .animation(.easeOut(duration: 0.8).delay(Double(2 - i) * 0.2), value: animate)
                }
                Image(systemName: "iphone.gen3")
                    .font(.system(size: side * 0.12, weight: .semibold))
                    .foregroundColor(.white)
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
        .accessibilityHidden(true)
    }
}

// MARK: - Page 4: Features
struct FeaturesPage: View {
    var isIPad: Bool = false
    var maxWidth: CGFloat = .infinity
    
    // __ONBOARDING_FEATURES_2026_08_25__ Deux fonctionnalités ajoutées, et les
    // icônes revues.
    //
    // Le changement de voix et la vue unique sont deux choses que l'app sait
    // faire et que rien n'annonçait à l'arrivée. Elles réutilisent des clés DÉJÀ
    // traduites — `audio.voice_effect` et `media_picker.view_once`, présentes
    // toutes deux dans les 34 `.lproj`. Inventer deux clés neuves aurait voulu
    // dire 34 traductions, ou la clé affichée en clair dans 33 langues.
    //
    // Icônes: des symboles SF, pas des images. Ils sont vectoriels, suivent le
    // poids et la taille du texte (donc la Dynamic Type), s'adaptent au thème,
    // ne pèsent rien dans le binaire et ne posent AUCUNE question de droits —
    // ce qui, sur une app qui vient de se faire rejeter pour une image générée
    // par IA embarquée comme icône, n'est pas un détail.
    //
    // Trois remplacements, tous pour lever une ambiguïté:
    //   waveform → mic.fill        le message vocal, c'est le micro; `waveform`
    //                              sert maintenant à l'effet de voix, où il dit
    //                              vraiment quelque chose.
    //   arrow.up.doc.fill → doc.fill   la flèche montante disait « téléverser »,
    //                              alors qu'il s'agit d'envoyer un document.
    //   person.2 → person.3.fill   deux silhouettes lisent « contact »,
    //                              trois lisent « groupe ».
    //   camera.metering.none → eye.slash.fill   personne ne décode le premier;
    //                              l'œil barré dit « on ne peut pas capturer ».
    //
    // `1.circle.fill` pour la vue unique est exactement le badge que la bulle
    // média affiche déjà (MediaMessageView) — l'écran d'accueil apprend le signe
    // que l'utilisateur retrouvera ensuite.
    private let features: [(icon: String, key: String)] = [
        ("mic.fill", "onboarding_feature_voice_title"),
        ("waveform", "audio.voice_effect"),
        ("photo.on.rectangle", "onboarding_feature_media_title"),
        ("1.circle.fill", "media_picker.view_once"),
        ("doc.fill", "onboarding_feature_documents_title"),
        ("person.3.fill", "onboarding_feature_groups_title"),
        ("eye.slash.fill", "onboarding_feature_screenshot_title")
    ]

    private var columns: [GridItem] {
        Array(repeating: GridItem(.flexible(), spacing: 16), count: isIPad ? 3 : 2)
    }

    var body: some View {
        // __ONBOARDING_FEATURES_2026_08_25__ Défilable.
        //
        // Sept tuiles sur deux colonnes font QUATRE rangées au lieu de trois, et
        // cette page n'avait aucun défilement: sur un petit écran, ou dès que
        // l'utilisateur grossit le texte (les libellés sont en `.scaled`), la
        // dernière rangée passait sous le bord sans que rien ne l'indique.
        // `GoodConductPage` est déjà un `ScrollView`; la page s'aligne dessus.
        ScrollView {
        VStack(spacing: isIPad ? 30 : 24) {
            Spacer(minLength: isIPad ? 16 : 8)

            PulseHero(systemName: "sparkles", size: isIPad ? 92 : 74, ringCount: 2)

            Text("onboarding_features_title".localized)
                .font(.system(size: isIPad ? 34 : 28, weight: .bold))
                .foregroundColor(.white)
                .multilineTextAlignment(.center)

            // Icons over one-word labels — no descriptions to read.
            LazyVGrid(columns: columns, spacing: 16) {
                ForEach(Array(features.enumerated()), id: \.offset) { index, feature in
                    FeatureTile(icon: feature.icon, title: feature.key.localized, index: index)
                }
            }
            .frame(maxWidth: 520)
            .padding(.horizontal, 24)

            Spacer(minLength: isIPad ? 16 : 8)
        }
        .padding(.vertical)
        .frame(maxWidth: maxWidth)
        .frame(maxWidth: .infinity)
        }
        .scrollBounceBehavior(.basedOnSize)
    }
}

// MARK: - Page 5: Good Conduct
struct GoodConductPage: View {
    var isIPad: Bool = false
    var maxWidth: CGFloat = .infinity
    
    var body: some View {
        ScrollView {
            VStack(spacing: isIPad ? 40 : 30) {
                Image(systemName: "hand.raised.fill")
                    .font(.system(size: isIPad ? 80 : 60))
                    .foregroundColor(.orange)
                    .padding(.top, 40)
                    .accessibilityHidden(true)

                Text("onboarding_conduct_title".localized)
                    .font(.system(size: isIPad ? 34 : 28, weight: .bold))
                    .foregroundColor(.white)
                    .multilineTextAlignment(.center)
                
                Text("onboarding_conduct_subtitle".localized)
                    .font(.system(size: isIPad ? 20 : 16))
                    .foregroundColor(.white.opacity(0.8))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 30)
                
                VStack(spacing: 15) {
                    RuleRow(icon: "checkmark.circle.fill", text: "onboarding_rule_respect".localized, color: .green)
                    RuleRow(icon: "checkmark.circle.fill", text: "onboarding_rule_privacy".localized, color: .green)
                    RuleRow(icon: "checkmark.circle.fill", text: "onboarding_rule_secure".localized, color: .green)
                    RuleRow(icon: "checkmark.circle.fill", text: "onboarding_rule_backup".localized, color: .green)
                    
                    Divider()
                        .background(Color.white.opacity(0.3))
                        .padding(.vertical, 10)
                    
                    RuleRow(icon: "xmark.circle.fill", text: "onboarding_rule_no_spam".localized, color: .red)
                    RuleRow(icon: "xmark.circle.fill", text: "onboarding_rule_no_abuse".localized, color: .red)
                    RuleRow(icon: "xmark.circle.fill", text: "onboarding_rule_no_illegal".localized, color: .red)
                }
                .padding(.horizontal, 20)
                
                Spacer(minLength: 40)
            }
            .frame(maxWidth: maxWidth)
            .frame(maxWidth: .infinity)
        }
    }
}

// MARK: - Page 6: Disclaimer
struct DisclaimerPage: View {
    var isIPad: Bool = false
    var maxWidth: CGFloat = .infinity
    
    var body: some View {
        ScrollView {
            VStack(spacing: isIPad ? 30 : 25) {
                // Warning icon
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: isIPad ? 80 : 60))
                    .foregroundColor(.orange)
                    .padding(.top, 40)
                    .accessibilityHidden(true)

                Text("onboarding_disclaimer_title".localized)
                    .font(.system(size: isIPad ? 34 : 28, weight: .bold))
                    .foregroundColor(.white)
                    .multilineTextAlignment(.center)
                
                // Main disclaimer text
                Text("onboarding_disclaimer_subtitle".localized)
                    .font(.system(size: isIPad ? 20 : 16))
                    .foregroundColor(.white.opacity(0.8))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 30)
                
                // Disclaimer cards - grid on iPad
                VStack {
                    if isIPad {
                        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 20) {
                            DisclaimerCard(
                                icon: "shield.slash",
                                iconColor: .orange,
                                title: "onboarding_disclaimer_audit_title".localized,
                                description: "onboarding_disclaimer_audit_desc".localized
                            )
                            
                            DisclaimerCard(
                                icon: "eye.slash.fill",
                                iconColor: appBrandColor,
                                title: "onboarding_disclaimer_anonymous_title".localized,
                                description: "onboarding_disclaimer_anonymous_desc".localized
                            )
                            
                            DisclaimerCard(
                                icon: "bell.slash.fill",
                                iconColor: .purple,
                                title: "onboarding_disclaimer_notifications_title".localized,
                                description: "onboarding_disclaimer_notifications_desc".localized
                            )
                            
                            DisclaimerCard(
                                icon: "antenna.radiowaves.left.and.right",
                                iconColor: .green,
                                title: "onboarding_disclaimer_mesh_title".localized,
                                description: "onboarding_disclaimer_mesh_desc".localized
                            )
                            
                            DisclaimerCard(
                                icon: "cloud.fill",
                                iconColor: .cyan,
                                title: "onboarding_disclaimer_cloud_title".localized,
                                description: "onboarding_disclaimer_cloud_desc".localized
                            )
                            
                            DisclaimerCard(
                                icon: "globe.europe.africa",
                                iconColor: .red,
                                title: "onboarding_disclaimer_critical_title".localized,
                                description: "onboarding_disclaimer_critical_desc".localized
                            )
                        }
                    } else {
                        VStack(spacing: 20) {
                            DisclaimerCard(
                                icon: "shield.slash",
                                iconColor: .orange,
                                title: "onboarding_disclaimer_audit_title".localized,
                                description: "onboarding_disclaimer_audit_desc".localized
                            )
                            
                            DisclaimerCard(
                                icon: "eye.slash.fill",
                                iconColor: appBrandColor,
                                title: "onboarding_disclaimer_anonymous_title".localized,
                                description: "onboarding_disclaimer_anonymous_desc".localized
                            )
                            
                            DisclaimerCard(
                                icon: "bell.slash.fill",
                                iconColor: .purple,
                                title: "onboarding_disclaimer_notifications_title".localized,
                                description: "onboarding_disclaimer_notifications_desc".localized
                            )
                            
                            DisclaimerCard(
                                icon: "antenna.radiowaves.left.and.right",
                                iconColor: .green,
                                title: "onboarding_disclaimer_mesh_title".localized,
                                description: "onboarding_disclaimer_mesh_desc".localized
                            )
                            
                            DisclaimerCard(
                                icon: "cloud.fill",
                                iconColor: .cyan,
                                title: "onboarding_disclaimer_cloud_title".localized,
                                description: "onboarding_disclaimer_cloud_desc".localized
                            )
                            
                            DisclaimerCard(
                                icon: "globe.europe.africa",
                                iconColor: .red,
                                title: "onboarding_disclaimer_critical_title".localized,
                                description: "onboarding_disclaimer_critical_desc".localized
                            )
                        }
                    }
                }
                .padding(.horizontal, 20)
                
                // Bottom note
                Text("onboarding_disclaimer_note".localized)
                    .font(.system(size: isIPad ? 15 : 13, weight: .medium))
                    .foregroundColor(.orange.opacity(0.9))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 30)
                    .padding(.top, 10)
                
                Spacer(minLength: 40)
            }
            .frame(maxWidth: maxWidth)
            .frame(maxWidth: .infinity)
        }
    }
}

struct DisclaimerCard: View {
    let icon: String
    let iconColor: Color
    let title: String
    let description: String
    
    var body: some View {
        HStack(alignment: .top, spacing: 15) {
            Image(systemName: icon)
                .font(.system(size: 28))
                .foregroundColor(iconColor)
                .frame(width: 40)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 6) {
                Text(title)
                    .font(.scaled(16, weight: .semibold))
                    .foregroundColor(.white)

                Text(description)
                    .font(.scaled(14))
                    .foregroundColor(.white.opacity(0.7))
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer()
        }
        .padding()
        .background(Color.white.opacity(0.08))
        .cornerRadius(12)
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(iconColor.opacity(0.3), lineWidth: 1)
        )
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Page 7: Get Started
struct GetStartedPage: View {
    @Binding var isPresented: Bool
    @Binding var hasSeenOnboarding: Bool
    var isIPad: Bool = false
    var maxWidth: CGFloat = .infinity
    
    var body: some View {
        VStack(spacing: isIPad ? 40 : 30) {
            Spacer()
            
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: isIPad ? 100 : 80))
                .foregroundColor(.green)
                .accessibilityHidden(true)

            Text("onboarding_ready_title".localized)
                .font(.system(size: isIPad ? 40 : 32, weight: .bold))
                .foregroundColor(.white)
                .multilineTextAlignment(.center)

            Text("onboarding_ready_message".localized)
                .font(.system(size: isIPad ? 22 : 18))
                .foregroundColor(.white.opacity(0.8))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)

            Spacer()

            Button(action: {
                #if !targetEnvironment(macCatalyst)
                UINotificationFeedbackGenerator().notificationOccurred(.success)
                #endif
                hasSeenOnboarding = true
                isPresented = false
            }) {
                Text("onboarding_button_start".localized)
                    .font(.system(size: isIPad ? 20 : 18, weight: .semibold))
                    .dynamicTypeSize(...DynamicTypeSize.accessibility1)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .foregroundColor(.white)
                    .frame(maxWidth: isIPad ? 400 : .infinity)
                    .frame(height: isIPad ? 60 : 56)
                    .background(
                        LinearGradient(
                            colors: [appBrandColor, appBrandColor.opacity(0.8)],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                    )
                    .cornerRadius(16)
            }
            .accessibilityHint("onboarding.button.start.hint".localized)
            .padding(.horizontal, 30)
            .padding(.bottom, 40)
        }
        .frame(maxWidth: maxWidth)
        .frame(maxWidth: .infinity)
        .padding()
    }
}

// MARK: - Supporting Components

struct FeatureBadge: View {
    let icon: String
    let text: String
    
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 20))
                .foregroundColor(appBrandColor)
                .frame(width: 30)
                .accessibilityHidden(true)

            Text(text)
                .font(.scaled(16))
                .foregroundColor(.white)

            Spacer()
        }
        .padding()
        .background(Color.white.opacity(0.1))
        .cornerRadius(12)
        .accessibilityElement(children: .combine)
    }
}

struct StepRow: View {
    let number: Int
    let text: String
    
    var body: some View {
        HStack(alignment: .top, spacing: 15) {
            ZStack {
                Circle()
                    .fill(appBrandColor)
                    .frame(width: 30, height: 30)
                
                Text("\(number)")
                    .font(.scaled(14, weight: .bold))
                    .foregroundColor(.white)
            }
            
            Text(text)
                .font(.scaled(15))
                .foregroundColor(.white.opacity(0.9))
                .fixedSize(horizontal: false, vertical: true)

            Spacer()
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(String(format: "onboarding.step.label".localized, number, text))
    }
}

struct MethodCard: View {
    let icon: String
    let title: String
    let steps: [String]
    
    var body: some View {
        VStack(alignment: .leading, spacing: 15) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.scaled(24))
                    .foregroundColor(appBrandColor)
                    .accessibilityHidden(true)

                Text(title)
                    .font(.scaled(18, weight: .semibold))
                    .foregroundColor(.white)
            }

            VStack(alignment: .leading, spacing: 10) {
                ForEach(Array(steps.enumerated()), id: \.offset) { index, step in
                    HStack(alignment: .top, spacing: 10) {
                        Text("•")
                            .foregroundColor(appBrandColor)
                            .accessibilityHidden(true)
                        Text(step)
                            .font(.scaled(14))
                            .foregroundColor(.white.opacity(0.8))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
        }
        .padding(20)
        .background(Color.white.opacity(0.1))
        .cornerRadius(16)
        .accessibilityElement(children: .combine)
    }
}

struct FeatureCard: View {
    let icon: String
    let title: String
    let description: String
    
    var body: some View {
        HStack(alignment: .top, spacing: 15) {
            Image(systemName: icon)
                .font(.system(size: 24))
                .foregroundColor(appBrandColor)
                .frame(width: 40)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 5) {
                Text(title)
                    .font(.scaled(16, weight: .semibold))
                    .foregroundColor(.white)

                Text(description)
                    .font(.scaled(14))
                    .foregroundColor(.white.opacity(0.7))
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer()
        }
        .padding()
        .background(Color.white.opacity(0.1))
        .cornerRadius(12)
        .accessibilityElement(children: .combine)
    }
}

struct RuleRow: View {
    let icon: String
    let text: String
    let color: Color
    
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 20))
                .foregroundColor(color)
                .frame(width: 25)
                .accessibilityHidden(true)

            Text(text)
                .font(.scaled(15))
                .foregroundColor(.white.opacity(0.9))
                .fixedSize(horizontal: false, vertical: true)

            Spacer()
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\((icon.contains("checkmark") ? "onboarding.rule.do" : "onboarding.rule.dont").localized): \(text)")
    }
}

// MARK: - Interactive Illustrations (procedural, royalty-free)
// Drawn entirely in code — no bundled artwork, no licensing, crisp at any size,
// and genuinely interactive (tap to respond). All continuous motion is gated on
// Reduce Motion so the onboarding stays calm for motion-sensitive users.

/// Concentric signal/encryption rings pulsing behind a hero glyph; tap bounces it.
private struct PulseHero: View {
    let systemName: String
    var size: CGFloat = 96
    var ringCount: Int = 3
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var animate = false
    @State private var bounce = false

    var body: some View {
        ZStack {
            ForEach(0..<ringCount, id: \.self) { i in
                Circle()
                    .stroke(appBrandColor.opacity(0.45), lineWidth: 2)
                    .frame(width: size, height: size)
                    .scaleEffect(animate ? 2.1 : 1.0)
                    .opacity(animate ? 0.0 : 0.55)
                    .animation(
                        reduceMotion ? nil :
                            .easeOut(duration: 2.6).repeatForever(autoreverses: false).delay(Double(i) * 0.85),
                        value: animate
                    )
            }
            Circle()
                .fill(RadialGradient(colors: [appBrandColor.opacity(0.35), .clear],
                                     center: .center, startRadius: 0, endRadius: size * 0.8))
                .frame(width: size * 1.7, height: size * 1.7)
            Image(systemName: systemName)
                .font(.system(size: size * 0.62, weight: .semibold))
                .foregroundStyle(.white, appBrandColor)
                .scaleEffect(bounce ? 1.14 : 1.0)
        }
        .frame(height: size * 2.1)
        .contentShape(Rectangle())
        .onTapGesture { tap() }
        .onAppear { animate = true }
        .accessibilityHidden(true)
    }

    private func tap() {
        guard !reduceMotion else { return }
        withAnimation(.spring(response: 0.28, dampingFraction: 0.4)) { bounce = true }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.28) {
            withAnimation(.spring(response: 0.4, dampingFraction: 0.6)) { bounce = false }
        }
    }
}

/// Stylized QR card with a sweeping scan line. Pattern is deterministic (no RNG).
private struct QRScanHero: View {
    var size: CGFloat = 180
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var scan = false
    private let n = 13

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 22).fill(.white)
                .shadow(color: appBrandColor.opacity(0.4), radius: 18)
            Canvas { ctx, sz in
                let cell = sz.width / CGFloat(n)
                for r in 0..<n {
                    for c in 0..<n where Self.filled(r, c, n) {
                        let rect = CGRect(x: CGFloat(c) * cell, y: CGFloat(r) * cell, width: cell, height: cell)
                        ctx.fill(Path(rect.insetBy(dx: cell * 0.06, dy: cell * 0.06)), with: .color(.black))
                    }
                }
            }
            .padding(size * 0.13)
            RoundedRectangle(cornerRadius: 2)
                .fill(LinearGradient(colors: [.clear, appBrandColor, .clear], startPoint: .leading, endPoint: .trailing))
                .frame(height: 3)
                .shadow(color: appBrandColor, radius: 6)
                .padding(.horizontal, size * 0.12)
                .offset(y: scan ? size * 0.34 : -size * 0.34)
                .animation(reduceMotion ? nil : .easeInOut(duration: 1.9).repeatForever(autoreverses: true), value: scan)
        }
        .frame(width: size, height: size)
        .onAppear { scan = true }
        .accessibilityHidden(true)
    }

    /// 3 finder squares + a stable pseudo-pattern fill (deterministic, no Date/RNG).
    static func filled(_ r: Int, _ c: Int, _ n: Int) -> Bool {
        func finder(_ br: Int, _ bc: Int) -> Bool {
            let dr = r - br, dc = c - bc
            guard dr >= 0, dr < 7, dc >= 0, dc < 7 else { return false }
            let ring = max(abs(dr - 3), abs(dc - 3))
            return ring == 3 || ring <= 1
        }
        if finder(0, 0) || finder(0, n - 7) || finder(n - 7, 0) { return true }
        if (r < 8 && c < 8) || (r < 8 && c >= n - 8) || (r >= n - 8 && c < 8) { return false }
        return ((r * 73 + c * 31 + r * c * 17) % 100) < 42
    }
}

/// Floating paper plane with a dashed motion trail; tap bounces it.
private struct PlaneHero: View {
    var size: CGFloat = 96
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var fly = false
    @State private var bounce = false

    var body: some View {
        ZStack {
            Circle()
                .fill(RadialGradient(colors: [appBrandColor.opacity(0.3), .clear],
                                     center: .center, startRadius: 0, endRadius: size))
                .frame(width: size * 1.9, height: size * 1.9)
            TrailShape()
                .trim(from: 0, to: fly ? 1 : 0.02)
                .stroke(style: StrokeStyle(lineWidth: 2.5, lineCap: .round, dash: [3, 7]))
                .foregroundColor(appBrandColor.opacity(0.55))
                .frame(width: size * 1.6, height: size * 0.9)
                .animation(reduceMotion ? nil : .easeInOut(duration: 1.8).repeatForever(autoreverses: false), value: fly)
            Image(systemName: "paperplane.fill")
                .font(.system(size: size * 0.6, weight: .semibold))
                .foregroundColor(appBrandColor)
                .offset(x: fly ? 6 : -6, y: fly ? -6 : 6)
                .scaleEffect(bounce ? 1.14 : 1.0)
                .animation(reduceMotion ? nil : .easeInOut(duration: 1.8).repeatForever(autoreverses: true), value: fly)
        }
        .frame(height: size * 1.9)
        .contentShape(Rectangle())
        .onTapGesture {
            guard !reduceMotion else { return }
            withAnimation(.spring(response: 0.28, dampingFraction: 0.4)) { bounce = true }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.28) {
                withAnimation(.spring(response: 0.4, dampingFraction: 0.6)) { bounce = false }
            }
        }
        .onAppear { fly = true }
        .accessibilityHidden(true)
    }
}

private struct TrailShape: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: rect.minX, y: rect.maxY))
        p.addQuadCurve(to: CGPoint(x: rect.maxX, y: rect.minY),
                       control: CGPoint(x: rect.midX, y: rect.maxY))
        return p
    }
}

/// Compact tappable method chip (Scan / Paste) — icon + one short label.
private struct MethodChip: View {
    let icon: String
    let title: String
    @State private var tapped = false
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: icon)
                .font(.scaled(30, weight: .semibold))
                .foregroundColor(appBrandColor)
            Text(title)
                .font(.scaled(15, weight: .semibold))
                .foregroundColor(.white)
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 22)
        .background(RoundedRectangle(cornerRadius: 18).fill(.white.opacity(0.08)))
        .scaleEffect(tapped ? 0.94 : 1.0)
        .onTapGesture {
            withAnimation(.spring(response: 0.3, dampingFraction: 0.5)) { tapped = true }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.18) {
                withAnimation(.spring(response: 0.4, dampingFraction: 0.6)) { tapped = false }
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(title)
    }
}

/// Animated feature tile — icon + short title only. Staggered idle pulse, tap bounce.
private struct FeatureTile: View {
    let icon: String
    let title: String
    let index: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var pulse = false
    @State private var tapped = false

    var body: some View {
        VStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 18)
                    .fill(appBrandColor.opacity(0.16))
                    .frame(width: 62, height: 62)
                Image(systemName: icon)
                    .font(.scaled(27, weight: .semibold))
                    .foregroundColor(appBrandColor)
                    .scaleEffect(pulse ? 1.08 : 0.96)
                    .animation(
                        reduceMotion ? nil :
                            .easeInOut(duration: 1.7).repeatForever(autoreverses: true).delay(Double(index) * 0.18),
                        value: pulse
                    )
            }
            Text(title)
                .font(.scaled(13, weight: .semibold))
                .foregroundColor(.white)
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .minimumScaleFactor(0.75)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 18)
        .background(RoundedRectangle(cornerRadius: 20).fill(.white.opacity(0.06)))
        .scaleEffect(tapped ? 0.93 : 1.0)
        .onTapGesture {
            withAnimation(.spring(response: 0.3, dampingFraction: 0.5)) { tapped = true }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.18) {
                withAnimation(.spring(response: 0.4, dampingFraction: 0.6)) { tapped = false }
            }
        }
        .onAppear { pulse = true }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(title)
    }
}

// MARK: - Preview
#if DEBUG
struct OnboardingView_Previews: PreviewProvider {
    static var previews: some View {
        OnboardingView(isPresented: .constant(true))
    }
}
#endif
