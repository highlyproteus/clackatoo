// SPDX-License-Identifier: GPL-3.0-only

import com.android.build.api.variant.BuildConfigField

// "Clackatoo Models": the companion app that downloads the pinned model files for the Clackatoo keyboard. It is the only
// part of Clackatoo with the INTERNET permission, so the keyboard itself stays offline. It has no code of its own beyond
// the download, no dependencies besides :catalog, and talks to the keyboard only through a signature permission.
plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.highlyproteus.clackatoo.models"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.highlyproteus.clackatoo.models"
        minSdk = 21
        targetSdk = 37
        // the release workflow requires this to equal the keyboard's versionName (both APKs carry the release tag)
        versionCode = 5
        versionName = "1.2.3"
        // the release values; debug build types override them below (androidComponents)
        manifestPlaceholders["modelsPermission"] = "io.github.highlyproteus.clackatoo.permission.MODELS"
        manifestPlaceholders["keyboardApplicationId"] = "io.github.highlyproteus.clackatoo"
    }

    buildTypes {
        // the same build types as the keyboard, so that matching variants of the two apps use the same key and permission name
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("nouserlib") { // what the keyboard releases are built as; identical to release here
            matchingFallbacks += "release"
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
        create("runTests") {
            matchingFallbacks += "debug"
        }
        create("debugNoMinify") {
            matchingFallbacks += "debug"
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        abortOnError = true
    }
}

androidComponents {
    // both apps declare one signature permission (a debug pair has its own name, see app/build.gradle.kts)
    onVariants { variant ->
        val suffix = if (variant.buildType == "debug" || variant.buildType == "debugNoMinify") ".debug" else ""
        val keyboard = "io.github.highlyproteus.clackatoo$suffix"
        variant.manifestPlaceholders.put("modelsPermission", "io.github.highlyproteus.clackatoo$suffix.permission.MODELS")
        variant.manifestPlaceholders.put("keyboardApplicationId", keyboard)
        variant.buildConfigFields?.put(
            "KEYBOARD_APPLICATION_ID", BuildConfigField("String", "\"$keyboard\"", "package name of the Clackatoo keyboard"),
        )
        variant.outputs.forEach { output ->
            if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                output.outputFileName = "ClackatooModels_${android.defaultConfig.versionName}-${variant.buildType}.apk"
            }
        }
    }
}

dependencies {
    implementation(project(":catalog"))

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
}
