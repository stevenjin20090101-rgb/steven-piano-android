// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

import com.android.build.api.artifact.SingleArtifact
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory

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
            // `-PstudioModels=<dir>` (a folder holding transcription-v1.onnx; by default $STUDIO_WORK/exports or
            // ~/studio-work/exports) lets TranscriberTest run the real model on the JVM. Without it, skipped.
            val studioModels = providers.gradleProperty("studioModels")
            if (studioModels.isPresent) test.systemProperty("stevenpiano.studio.models", studioModels.get())
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // ONNX Runtime's 28.6 MB library travels deflated (about 10.6 MB) and Android extracts it at install:
        // the download grows by about 11 MB rather than 29 MB (docs/STUDIO_SPIKE.md › APK size).
        jniLibs {
            useLegacyPackaging = true
            // Studio's runtime (v1.7) ships for arm64-v8a alone, the tablets and phones of the last several years:
            // its libraries for the other three ABIs (libonnxruntime.so and its JNI, libonnxruntime4j_jni.so) are left
            // out, and every other native library keeps all four, so the app still installs everywhere 1.6.2 did.
            // Where the runtime can't load, Studio hides itself (studio/StudioAvailability.kt).
            excludes += listOf("**/armeabi-v7a/libonnxruntime*.so", "**/x86/libonnxruntime*.so", "**/x86_64/libonnxruntime*.so")
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

/**
 * ONNX Runtime 1.29.0 and newer merge a telemetry provider into the app (`ai.onnxruntime.TelemetryInitializer`,
 * a ContentProvider that starts Microsoft's 1DS uploader at every process start). The app stays on 1.28.0,
 * which has none; this check fails the build if a merged manifest ever carries that provider, or any other
 * provider from `ai.onnxruntime` (BUILD_SPEC.md › v1.7 — M23). `check` runs it for every variant.
 */
abstract class OnnxTelemetryCheck : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val file = manifest.get().asFile
        val text = file.readText()
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(file)
        val androidNs = "http://schemas.android.com/apk/res/android"
        val providers = document.getElementsByTagName("provider")
        val found = (0 until providers.length)
            .map { providers.item(it).attributes?.getNamedItemNS(androidNs, "name")?.nodeValue.orEmpty() }
            .filter { it.startsWith("ai.onnxruntime") }
        if (found.isNotEmpty() || "TelemetryInitializer" in text) {
            throw GradleException(
                "ONNX Runtime's telemetry is in the merged manifest (${found.ifEmpty { listOf("TelemetryInitializer") }.joinToString()}): " +
                    "keep com.microsoft.onnxruntime at 1.28.0 (BUILD_SPEC.md › v1.7 — M23).",
            )
        }
        report.get().asFile.writeText("No ONNX Runtime provider or telemetry in ${file.name}.\n")
    }
}

val checkOnnxTelemetry = tasks.register("checkOnnxTelemetry") {
    group = "verification"
    description = "Fails if ONNX Runtime's telemetry provider is in any variant's merged manifest."
}
tasks.named("check") { dependsOn(checkOnnxTelemetry) }

androidComponents {
    onVariants { variant ->
        val perVariant = tasks.register<OnnxTelemetryCheck>("check${variant.name.replaceFirstChar { it.uppercase() }}OnnxTelemetry") {
            group = "verification"
            manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            report.set(layout.buildDirectory.file("reports/onnx-telemetry/${variant.name}.txt"))
        }
        checkOnnxTelemetry.configure { dependsOn(perVariant) }
    }
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
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.zxing.core)
    testImplementation(libs.onnxruntime.jvm)
}
