import java.util.Properties

// __WEAR_BRIDGE_2026_11_17__ OSHI for Wear OS.
//
// A REMOTE VIEW of the phone app: no identity key, no server, no crypto here.
// Everything is asked of the phone over the Wearable Data Layer (see
// ../wear-bridge, compiled into both modules). Same applicationId as the
// phone, which is what lets Play ship it under the same listing and what lets
// the Data Layer route messages between the two (same package + same cert).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
}

android {
    namespace = "com.oshi.messenger.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.oshi.messenger"
        minSdk = 30
        targetSdk = 36
        // Wear APKs share the phone's version-code space on Play. The phone is
        // in the hundreds; the watch lives in its own band so the two can never
        // collide (and the phone versionCode is never touched by the watch).
        versionCode = 2_000_004
        versionName = "1.7.4-wear"
        // Real data by default; the debug-only sample data is opted into with
        // `-PwearFakeData` (screenshots, emulator without a paired phone).
        buildConfigField("boolean", "FAKE_DATA", "false")
    }

    signingConfigs {
        create("release") {
            // Same keystore as the phone: the Data Layer only connects the two
            // apps when package AND signing certificate match.
            val propsFile = rootProject.file("keystore.properties")
            val props = Properties().also {
                if (propsFile.exists()) propsFile.inputStream().use { s -> it.load(s) }
            }
            val ksPassword: String = props.getProperty("storePassword") ?: System.getenv("OSHI_KEYSTORE_PASSWORD") ?: ""
            storeFile = file("../oshi-release.keystore")
            storePassword = ksPassword
            keyAlias = props.getProperty("keyAlias") ?: System.getenv("OSHI_KEY_ALIAS") ?: "oshi"
            keyPassword = props.getProperty("keyPassword") ?: System.getenv("OSHI_KEY_PASSWORD") ?: ksPassword
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "FAKE_DATA", (project.hasProperty("wearFakeData")).toString())
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    sourceSets {
        getByName("main") { java.srcDir("../wear-bridge/src/main/java") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            // Robolectric screen tests read the real resources (strings, drawables).
            isIncludeAndroidResources = true
        }
    }

    // Cross-platform avatar vectors are read from ../docs at test time.
    tasks.withType<Test>().configureEach {
        val spec = rootProject.file("../docs/avatar-palette.json")
        if (spec.exists()) inputs.file(spec).withPropertyName("avatarPaletteSpec").withPathSensitivity(PathSensitivity.RELATIVE)
        // `-PwearShots` writes the screen PNGs (build/outputs/roborazzi); without it the
        // screen tests still render and assert, they just save nothing.
        if (project.hasProperty("wearShots")) systemProperty("roborazzi.test.record", "true")
    }
}

dependencies {
    val wearCompose = "1.5.6"
    implementation("androidx.wear.compose:compose-material3:$wearCompose")
    implementation("androidx.wear.compose:compose-foundation:$wearCompose")
    implementation("androidx.wear.compose:compose-navigation:$wearCompose")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.core:core-ktx:1.16.0")

    // Data Layer (MessageClient / DataClient) to the phone.
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    // play-services-base/basement and watchface-complications (via preference/appcompat 1.1.0)
    // pull androidx.fragment 1.1.0, which Play flags as outdated (Console, 18/11/2026).
    // Pin a current fragment so Gradle resolves every edge to it.
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")

    // Voice dictation / keyboard through the system RemoteInput activity.
    implementation("androidx.wear:wear-input:1.2.0")

    // Tile + complication.
    implementation("androidx.wear.tiles:tiles:1.5.0")
    implementation("androidx.wear.protolayout:protolayout:1.3.0")
    implementation("androidx.wear.protolayout:protolayout-expression:1.3.0")
    // Material 3 Expressive tile layouts (primaryLayout, edge button, avatar buttons).
    implementation("androidx.wear.protolayout:protolayout-material3:1.3.0")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")

    testImplementation("junit:junit:4.13.2")
    // Screenshot / UI tests of the screens on round 192 dp and 227 dp watches (Robolectric).
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.compose.ui:ui-test-junit4:1.8.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.8.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.46.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.46.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.json:json:20231013")
}
