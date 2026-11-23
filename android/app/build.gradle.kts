import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("com.google.gms.google-services")  // Firebase
    id("com.google.devtools.ksp")  // KSP (replaces kapt for Hilt + Room)
}

android {
    testOptions {
        unitTests {
            // android.util.Log and android.util.Base64 are THROWING stubs in a
            // plain JVM unit test, so any production line that merely logs was
            // untestable: ResumableArtifactDownloadTest failed three ways on
            // "Method w in android.util.Log not mocked" alone. Returning
            // defaults instead makes those calls inert.
            //
            // Caveat worth knowing before relying on it: this makes
            // Base64.decode return null rather than throw, so decodePolyline
            // would yield emptyList() for ANY input and a round-trip assertion
            // would pass vacuously. Do not test the Base64 codec this way.
            isReturnDefaultValues = true
        }
    }

    // __A_PARITY_TEST_THAT_NEVER_RERUNS_IS_GREEN_FOREVER_2026_11_07__
    //
    // Several unit tests read the iOS sources to keep the two platforms in
    // step: PollParityTest reads OSHI/PollMessage.swift, MeshReconnectParityTest
    // reads OSHI/CrossPlatformMesh.swift. Gradle knows nothing about those
    // files, so after a Swift-only change the test task is UP-TO-DATE and the
    // guard simply does not run. Measured on 2026-11-07: a deliberate drift in
    // the Swift backoff ladder was reported as a pass, and only `--rerun-tasks`
    // caught it. Declaring the directory as an input makes a Swift edit
    // invalidate the task, which is the whole point of the guards.
    tasks.withType<Test>().configureEach {
        val iosSources = rootProject.file("../OSHI")
        if (iosSources.isDirectory) {
            inputs.dir(iosSources)
                .withPropertyName("iosSourcesForParityTests")
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }
        // __THE_LAUNCHER_ICON_WAS_TOO_TIGHT_2026_11_07__ The SAME trap, caught a
        // second time on the same day. `LauncherIconFramingTest` reads the
        // launcher PNGs off the filesystem; Gradle treats a unit test's inputs
        // as its CLASSES, not as whatever the test happens to open. So after an
        // icon-only change the task was UP-TO-DATE and the guard did not run —
        // measured: restoring the old, too-tight icons PASSED without
        // `--rerun-tasks` and FAILED with it.
        //
        // Resources are an obvious thing for a guard to read and a non-obvious
        // thing for Gradle to track, which is exactly the shape that produces a
        // test that is green for ever.
        // Cross-platform golden vectors read at run time (CoverHardeningTest and
        // others under docs/fixtures): declared so an edit to a vector re-runs them.
        val sharedFixtures = rootProject.file("../docs/fixtures")
        if (sharedFixtures.isDirectory) {
            inputs.dir(sharedFixtures)
                .withPropertyName("crossPlatformFixtures")
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }
        val launcherArt = project.file("src/main/res")
        if (launcherArt.isDirectory) {
            inputs.dir(launcherArt)
                .withPropertyName("resourcesReadByAssetGuards")
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }
    }
    namespace = "com.oshi.messenger"
    compileSdk = 36

    // NDK r28 emits 16 KB-aligned ELF LOAD segments by default. Android 16
    // ships devices with 16 KB memory pages, and Play requires 16 KB support
    // for anything targeting API 35+; r26 (the AGP default) would silently
    // produce a 4 KB-aligned liboshi_codec.so that cannot be dlopen'd there.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.oshi.messenger"
        minSdk = 26
        targetSdk = 36
        versionCode = 141
        versionName = "1.7.0"

        // Include arm64-v8a native libs for llama.cpp
        ndk {
            abiFilters += "arm64-v8a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        // VPS Configuration (same as iOS)
        buildConfigField("String", "VPS_URL", "\"https://oshi-messenger.com\"")
        buildConfigField("String", "PINATA_GATEWAY", "\"https://gateway.pinata.cloud\"")

        // TURN credentials for the call-relay server. Pulled from
        // keystore.properties (gitignored) or env var at build time so we
        // never check secrets into source. Empty string in unconfigured
        // builds — the call code falls back to STUN-only if the credential
        // is missing.
        run {
            val tcpKsFile = rootProject.file("keystore.properties")
            val tcpProps = Properties().also {
                if (tcpKsFile.exists()) tcpKsFile.inputStream().use { s -> it.load(s) }
            }
            // __NO_STATIC_TURN_2026_09_25__ Static TURN credentials are no longer baked into the
            // APK: coturn rejects them (use-auth-secret) and they were extractable. Calls use
            // ephemeral creds from /voip/turn-creds. Kept as empty fields for source compat.
            val turnUser = ""
            val turnPass = ""
            buildConfigField("String", "TURN_USERNAME", "\"$turnUser\"")
            buildConfigField("String", "TURN_PASSWORD", "\"$turnPass\"")
        }

        // OshiCodec native build (liboshi_codec.so) — Call v2 Component A.
        // This compiles the proprietary audio codec into a sibling .so alongside
        // the prebuilt libllama_jni.so (which remains untouched under jniLibs/).
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            // Credentials are read from keystore.properties (gitignored) or
            // env vars set by CI. They are NEVER committed.
            val keystorePropsFile = rootProject.file("keystore.properties")
            val keystoreProps = Properties().also {
                if (keystorePropsFile.exists()) {
                    keystorePropsFile.inputStream().use { stream -> it.load(stream) }
                }
            }
            val ksPassword: String =
                keystoreProps.getProperty("storePassword")
                    ?: System.getenv("OSHI_KEYSTORE_PASSWORD")
                    ?: ""
            val ksKeyAlias: String =
                keystoreProps.getProperty("keyAlias")
                    ?: System.getenv("OSHI_KEY_ALIAS")
                    ?: "oshi"
            val ksKeyPassword: String =
                keystoreProps.getProperty("keyPassword")
                    ?: System.getenv("OSHI_KEY_PASSWORD")
                    ?: ksPassword

            storeFile = file("../oshi-release.keystore")
            storePassword = ksPassword
            keyAlias = ksKeyAlias
            keyPassword = ksKeyPassword
        }
    }

    buildTypes {
        release {
            // Production default is a minified R8 build. Passing -PnoMinify produces
            // a functionally-identical signed release that skips R8 — used when the
            // build host lacks the heap for R8 (R8 needs several GB; a memory-starved
            // host thrashes indefinitely). Nothing is stripped, so it is strictly safe
            // at runtime; the APK is just larger and unobfuscated.
            val minify = !project.hasProperty("noMinify")
            isMinifyEnabled = minify
            isShrinkResources = minify
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    // __OPUS_CODEC_2026_09_23__ Concentus 1.0.2 — the pure-Java Opus port — vendored as SOURCE
    // at the monorepo root (pinned release, sha256 + PGP verified, see Vendor/README-OSHI.md)
    // and compiled here. Same source as Desktop; no prebuilt jar, no system codec.
    sourceSets {
        getByName("main") {
            java.srcDir("../../Vendor/concentus/src/main/java")
            // __WEAR_BRIDGE_2026_11_17__ The phone<->watch wire format and the
            // pure avatar palette, compiled into BOTH :app and :wear.
            java.srcDir("../wear-bridge/src/main/java")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Ensure native llama.cpp libs are included in AAB
        jniLibs {
            // NOTE: `true` means the LEGACY behaviour — the .so are stored
            // COMPRESSED in the APK and extracted to the data directory at
            // install time (`extractNativeLibs="true"`). It is the opposite of
            // what the old comment here claimed ("uncompressed for faster
            // load"). Kept as-is because that is the configuration llama.cpp's
            // chain of NEEDED libraries has always been shipped and tested
            // with; it costs install size, not correctness.
            //
            // It does not affect 16 KB page support: because the libraries are
            // extracted to the filesystem, what the loader cares about is the
            // ELF LOAD alignment (now 0x4000 for all seven — see
            // `llama.cpp/jni/CMakeLists.txt`), not their offset inside the zip.
            useLegacyPackaging = true
            // libc++_shared.so is shipped both by prebuilt jniLibs (for llama.cpp)
            // and by the NDK (for oshi_codec). Keep the first copy encountered —
            // the prebuilt version is pinned to the NDK libllama_jni was built with.
            pickFirsts += "**/libc++_shared.so"
        }
    }
}

dependencies {
    // Google Play In-App Review
    implementation("com.google.android.play:review:2.0.2")
    implementation("com.google.android.play:review-ktx:2.0.2")

    // __FORCED_UPDATE_ANDROID_2026_10_13__ Google Play In-App Updates. Carries
    // the IMMEDIATE (blocking) flow, whose screen Play draws and localises, so
    // forcing an update adds no strings to 36 locales.
    implementation("com.google.android.play:app-update:2.1.0")
    implementation("com.google.android.play:app-update-ktx:2.1.0")

    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-process:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.5")

    // Lifecycle & ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")

    // Hilt for Dependency Injection (KSP instead of kapt)
    implementation("com.google.dagger:hilt-android:2.57.1")
    ksp("com.google.dagger:hilt-compiler:2.57.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    // Room Database (KSP instead of kapt)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // SQLCipher (CSPN data-at-rest DB encryption). The androidx variant
    // `net.zetetic:sqlcipher-android` — NOT the legacy `android-database-sqlcipher`
    // — because only 4.6.0+ ships 16 KB-page-aligned .so, which targetSdk 36 /
    // Play require (the same alignment gate that removed WebRTC above). Its
    // `SupportOpenHelperFactory` plugs straight into Room's openHelperFactory.
    // Pinned. Currently wired behind DatabaseEncryption.ENABLED (default-off).
    implementation("net.zetetic:sqlcipher-android:4.9.0")
    implementation("androidx.sqlite:sqlite:2.4.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Cryptography (for iOS-compatible encryption)
    // 1.81, pas 1.76: X-Wing (ML-KEM-768 + X25519) n'existe pas avant 1.81, et
    // c'est le KEM hybride qu'iOS utilise via CryptoKit. Le ML-KEM de FIPS 203
    // n'est pas non plus le Kyber round-3 des versions antérieures — prendre le
    // mauvais donnerait deux implémentations qui ne se parlent pas.
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // NO WebRTC. `io.getstream:stream-webrtc-android:1.0.2` was declared here
    // and never used: `grep -rn org.webrtc app/src` returns zero hits, and the
    // ICE_CANDIDATE names in EnhancedCallManager are OSHI's own signalling
    // enum, not `org.webrtc.IceCandidate`. Calls run on the hand-rolled UDP/P2P
    // path selector plus liboshi_codec, which is what iOS does too.
    //
    // Removing it matters for more than tidiness. R8 strips unused *classes*;
    // it does not strip a `.so`, so every user was downloading ~30 MB of
    // `libjingle_peerconnection_so.so` that never loaded — and that library was
    // the last 4 KB-aligned artifact in the bundle, so it alone would have
    // failed Play's 16 KB page-size requirement for targetSdk 36. (For the
    // record, should WebRTC ever be needed: 1.2.3 is the earliest release that
    // ships 16 KB-aligned; 1.0.2 through 1.2.2 are not.)
    //
    // The AAR contributed only INTERNET and ACCESS_NETWORK_STATE to the merged
    // manifest, both already declared in AndroidManifest.xml:13-14.

    // WiFi Direct & Nearby Connections (for mesh)
    implementation("com.google.android.gms:play-services-nearby:19.0.0")

    // __WEAR_BRIDGE_2026_11_17__ Wearable Data Layer (MessageClient / DataClient)
    // for the opt-in Smartwatch bridge. No permission is added by this library.
    implementation("com.google.android.gms:play-services-wearable:19.0.0")

    // Google Play Services Location (for location sharing)
    implementation("com.google.android.gms:play-services-location:21.0.1")

    // QR Code
    implementation("com.google.zxing:core:3.5.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Image Loading
    implementation("io.coil-kt:coil-compose:2.5.0")
    // __LOCAL_GIF_LIBRARY_2026_09_13__ Coil ne décode PAS les GIF animés sans ceci:
    // il en affiche la première image. La bibliothèque livrée avec l'app fait 2736
    // GIFs et la fonctionnalité entière est l'animation — un décodeur en moins et
    // c'est une grille d'images fixes. Enregistré dans `BubbleMedia.imageLoader`,
    // qui sert aussi bien les bulles que le sélecteur.
    implementation("io.coil-kt:coil-gif:2.5.0")

    // Media3 ExoPlayer (video playback in chat)
    implementation("androidx.media3:media3-exoplayer:1.2.0")
    implementation("androidx.media3:media3-ui:1.2.0")
    // __VIDEO_NOTE_2026_09_24__ Round video notes (docs/VIDEO_NOTE_SPEC.md): the square
    // crop + 480² H.264/AAC encode. Same Media3 release as the player above — mixing
    // Media3 versions is unsupported. `VideoTranscoder` has no GL stage, so it can scale
    // but not crop; Transformer's `Presentation` effect does both in one pass.
    implementation("androidx.media3:media3-transformer:1.2.0")
    implementation("androidx.media3:media3-effect:1.2.0")
    // CameraX for the compact circular recorder (Preview + VideoCapture<Recorder>).
    // __CAMERAX_WAS_THE_LAST_4KB_LIB_2026_10_19__ 1.3.4 ships
    // `libimage_processing_util_jni.so` aligned to 4096, and Play refuses any
    // targetSdk 35+ update whose bundle is not 16 KB-page-aligned — the same
    // gate that removed WebRTC and forced sqlcipher 4.9.0 above. Measured on
    // the shipped 1.6.42 bundle: 13 of its 14 native libraries were already
    // 16384; this one was the only hold-out, and one is enough to block every
    // future release. 1.4.2 is the first CameraX line with aligned natives.
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-video:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")

    // Biometrics
    implementation("androidx.biometric:biometric:1.1.0")

    // Work Manager (background tasks)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Firebase Cloud Messaging (push notifications)
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    // ML Kit - Selfie Segmentation (video background blur)
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")

    // ML Kit - Text Recognition (OCR for AI document/image analysis)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // ML Kit - Language Identification (offline AI language detection)
    implementation("com.google.mlkit:language-id:17.0.5")

    // ML Kit - Translation (on-device, 59 languages, ~30MB per model)
    implementation("com.google.mlkit:translate:17.0.3")

    // ML Kit GenAI - Gemini Nano on-device (Google equivalent of Apple Intelligence)
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta1")
    implementation("com.google.mlkit:genai-proofreading:1.0.0-beta1")
    implementation("com.google.mlkit:genai-rewriting:1.0.0-beta1")

    // OSMDroid (OpenStreetMap for offline maps)
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    // Gson (for Room type converters - BotPermissions JSON serialization)
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Testing
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (android.jar's org.json is a throwing stub),
    // so wire-format round-trip tests (e.g. CheckInMessage epoch conversion) can run.
    testImplementation("org.json:json:20231013")
    // Pins the Range/If-Range resume behaviour of ResumableArtifactDownload
    // against a real HTTP server. Matches the okhttp already used at runtime
    // (4.12.0); test-only, nothing ships with it.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // `androidx.test.runner.AndroidJUnitRunner` is named as the
    // testInstrumentationRunner above but was never on the test classpath —
    // there was no androidTest source set for it to be missing from. Everything
    // here is already in the offline Gradle cache; this machine builds offline.
    androidTestImplementation("androidx.test:runner:1.5.2")
    // Declared explicitly rather than leaned on transitively: the instrumented
    // tests open Room databases and block on suspend DAOs directly.
    androidTestImplementation("androidx.room:room-runtime:2.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    //
    // NOT `androidx.room:room-testing`. Its `MigrationTestHelper` builds the old
    // database from the EXPORTED SCHEMA JSON, and `AppDatabase` is declared
    // `exportSchema = false`, so there is nothing for it to read — and it is the
    // one artifact of this set that is absent from the offline cache, so adding
    // it would break an offline build for a helper that cannot work here anyway.
    // `RoomMigrationInstrumentedTest` builds the v10 database itself and relies on
    // Room's own on-open schema validation instead; see its KDoc.
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
