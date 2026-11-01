repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
    maven(url = "https://jitpack.io")
    // The AAR `flutter build aar` writes; see docs/bbox-flutter.md.
    // Also hosts the io.flutter engine artifacts (flutter_embedding_*, *_debug/profile/release)
    // copied from the Gradle cache so resolution works offline.
    maven { url = uri(rootProject.file("flutter/build/host/outputs/repo")) }
    // Fallback for the Flutter engine artifacts if the local repo is missing any.
    maven { url = uri("https://storage.flutter-io.cn/download.flutter.io") }
    maven { url = uri("https://storage.googleapis.com/download.flutter.io") }
}