// SPDX-License-Identifier: GPL-3.0-only

// The list of downloadable models (id, file, size, SHA-256, pinned URL): plain Kotlin data with no code of its own, shared
// by the keyboard (which checks every file against it) and the "Clackatoo Models" companion app (which downloads from
// it). One source of truth: neither app takes a URL, size or digest from anywhere else.
plugins {
    id("com.android.library")
}

android {
    namespace = "helium314.keyboard.clackatoo.catalog"
    compileSdk = 37

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
}
