// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// The release key lives outside this repository, in Steven's home folder, and never in git:
// ~/steven-piano-keystore.properties names the keystore (storeFile, storePassword, keyAlias,
// keyPassword). Without it the release build stops with a message; it never falls back to the
// debug key. Debug builds and unit tests do not need it. See README > Security.
val releaseSigningFile = File(System.getProperty("user.home"), "steven-piano-keystore.properties")
val releaseSigning: Properties? = releaseSigningFile.takeIf { it.isFile }
    ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }
    ?.takeIf { props -> listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !props.getProperty(it).isNullOrBlank() } }

android {
    namespace = "dev.stevenjin.stevenpiano"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.stevenjin.stevenpiano"
        minSdk = 26
        targetSdk = 34
        // -PversionCodeOverride=14 builds a copy that reads as newer than the one installed, for the
        // updater's emulator test (README > Updates); every real build takes the number below.
        versionCode = providers.gradleProperty("versionCodeOverride").orNull?.toIntOrNull() ?: 13
        versionName = "1.6.2"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Steven's release key (above); the debug build keeps the debug key.
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.all { test ->
            // `./gradlew testDebugUnitTest -Pcorpus` also parses every file under ../midi/
            // (or `-Pcorpus=/some/dir`). Without the property the corpus test is skipped.
            val corpus = providers.gradleProperty("corpus")
            if (corpus.isPresent) {
                val dir = corpus.get().ifBlank { rootProject.file("../midi").path }
                test.systemProperty("stevenpiano.corpus", dir)
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

// A release build without the release key stops here, before anything is compiled for it.
val checkReleaseSigning = tasks.register("checkReleaseSigning") {
    val path = releaseSigningFile.path
    val present = releaseSigning != null
    doLast {
        if (!present) {
            throw GradleException(
                "Release signing: $path is missing or incomplete (storeFile, storePassword, keyAlias, keyPassword). " +
                    "The release APK is signed only with Steven Piano's release key, never the debug key; see README > Security.",
            )
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkReleaseSigning) }

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.windowsizeclass)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.media)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.haze)
    implementation(libs.nanohttpd)
    implementation(libs.nanohttpd.websocket)
    implementation(libs.qrcode.kotlin)
    implementation(libs.eddsa)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.zxing.core)
}
