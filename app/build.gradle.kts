@file:Suppress("UnstableApiUsage")

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("com.google.devtools.ksp")
    id("kotlin-parcelize")
}

setupApp()

val generatedLicenseAssets = layout.buildDirectory.dir("generated/assets/rootLicense")
val generateRootLicenseAsset by tasks.registering(Copy::class) {
    from(rootProject.layout.projectDirectory.file("LICENSE"))
    into(generatedLicenseAssets)
}

android {
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
    ksp {
        arg("room.incremental", "true")
        arg("room.schemaLocation", "$projectDir/schemas")
    }
    bundle {
        language {
            enableSplit = false
        }
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
        aidl = true
    }
    namespace = "io.nekohasekai.sagernet"
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Flutter's *debug* engine ships the Vulkan validation layer: 237 MB uncompressed, ~65 MB inside the APK,
            // second only to libflutter.so itself. It exists purely for graphics debugging and is absent from the
            // release engine jar (verified: 0 VkLayer entries there), so nothing can depend on it at runtime.
            excludes += setOf("**/libVkLayer_khronos_validation.so")
        }
    }
    androidResources {
        generateLocaleConfig = true
    }
    sourceSets.named("main") {
        assets.srcDir(generatedLicenseAssets)
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(generateRootLicenseAsset)
}

dependencies {

    implementation(project(":bboxmodel"))

    // B-box's own interface, built by `flutter build aar` into flutter/build/host/outputs/repo.
    // Every variant links the release AAR, not just release builds. The debug one ships the JIT engine plus Dart as
    // interpreted kernel, which costs ~150 MB of APK and buys hot reload — something this host never exercises,
    // because the Dart lives in the Flutter module and is developed there on its own.
    implementation("io.ericlee.bbox_ui:flutter_release:1.0")

    implementation(fileTree("libs"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.recyclerview:recyclerview:1.3.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.5.6")
    implementation("androidx.browser:browser:1.5.0")
    implementation("androidx.preference:preference-ktx:1.2.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.work:work-runtime-ktx:2.8.1")
    implementation("androidx.work:work-multiprocess:2.8.1")

    implementation("com.google.android.material:material:1.8.0")
    implementation("com.google.code.gson:gson:2.9.0")

    implementation("com.github.jenly1314:zxing-lite:2.1.1")
    // 0.24.x is built with Kotlin 2.2 (unreadable for the Kotlin 2.0 compiler/KSP) and 0.24.5+ needs compileSdk 36;
    // 0.23.7 is Kotlin 2.1 and only references stdlib members that 2.0.21 has, so the app keeps its own stdlib
    implementation("io.github.rosemoe:editor:0.23.7") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }

    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.3")
    implementation("org.yaml:snakeyaml:1.30")
    implementation("com.github.daniel-stoneuk:material-about-library:3.2.0-rc01")
    implementation("com.jakewharton:process-phoenix:2.1.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")

    implementation("com.simplecityapps:recyclerview-fastscroll:2.0.1") {
        exclude(group = "androidx.recyclerview")
        exclude(group = "androidx.appcompat")
    }

    implementation("androidx.room:room-runtime:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")
}

// `mobile_scanner` (pulled in by the B-box Flutter module, used by profiles.dart's QR scanner) asks for
// androidx.camera 1.6.1, whose aar metadata demands compileSdk 36 and AGP 8.9.1. This project builds with
// compileSdk 35 and AGP 8.8.1, so pin CameraX to the newest line that still accepts them (1.5.3 declares
// minCompileSdk=35 / minAndroidGradlePluginVersion=8.6.0). Same pattern as pinning rosemoe editor to 0.23.7.
configurations.configureEach {
    resolutionStrategy {
        // CameraX: see the comment above.
        force("androidx.camera:camera-core:1.5.3")
        force("androidx.camera:camera-camera2:1.5.3")
        force("androidx.camera:camera-lifecycle:1.5.3")
        force("androidx.camera:camera-view:1.5.3")
        // The B-box AARs were published alongside libraries whose POMs still ask for newer Kotlin artifacts
        // (mobile_scanner declares kotlin-stdlib 2.2.10 and kotlinx-coroutines 1.11.0, both metadata 2.2+).
        // Publishing does not rewrite those declarations, so pin them here to what this project's Kotlin 2.0.21
        // compiler can actually read — same idea as excluding kotlin-stdlib from rosemoe editor above.
        force("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
        force("org.jetbrains.kotlin:kotlin-stdlib-common:2.0.21")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.0.21")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.0.21")
        force("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
        force("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.6.4")
        force("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    }
}
