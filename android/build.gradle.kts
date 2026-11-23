// Top-level build file for OSHI Android
// Compatible with iOS OSHI for cross-platform mesh messaging

plugins {
    id("com.android.application") version "8.13.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("com.google.dagger.hilt.android") version "2.57.1" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0" apply false
    id("com.google.gms.google-services") version "4.4.4" apply false  // Firebase
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false  // KSP (replaces kapt)
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
